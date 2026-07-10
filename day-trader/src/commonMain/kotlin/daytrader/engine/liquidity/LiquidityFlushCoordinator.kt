package daytrader.engine.liquidity

import daytrader.broker.SymbolMarkets
import daytrader.data.LiquidityBucketRepository
import daytrader.data.StrategyDeploymentRepository
import daytrader.domain.AUTO_LIQUIDITY_FLUSH_MAX_LOOPS
import daytrader.domain.DeploymentMarket
import daytrader.domain.LiquidityBucketLogic
import daytrader.domain.LiquidityEntryProximityGuard
import daytrader.execution.ExecutionManager
import daytrader.gateway.LiveQuote
import daytrader.gateway.WorkingOrder
import daytrader.presentation.liquidity.LiquidityAllocatorMapper
import daytrader.presentation.liquidity.distributeLiquidityByBayesianWinRate
import daytrader.presentation.strategies.SessionRollupCache
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class LiquidityFlushCoordinator(
    private val liquidityBucketRepository: LiquidityBucketRepository,
    private val executionManager: ExecutionManager?,
    private val deploymentRepository: StrategyDeploymentRepository,
) {
    private val applier = LiquidityAllocationApplier(
        liquidityBucketRepository = liquidityBucketRepository,
        executionManager = executionManager,
        deploymentRepository = deploymentRepository,
    )
    private val bucketMutexes = ConcurrentHashMap<String, Mutex>()
    private val flushInFlight = ConcurrentHashMap<String, Boolean>()

    suspend fun applyAllocation(
        request: LiquidityAllocationApplyRequest,
        sessionRollupCache: SessionRollupCache? = null,
    ): LiquidityAllocationApplyResult {
        val key = mutexKey(request.sessionDate, request.selectedCurrency)
        return mutexFor(key).withLock {
            applier.apply(
                request.copy(sessionRollupCache = sessionRollupCache),
            )
        }
    }

    suspend fun flush(request: LiquidityFlushRequest): LiquidityFlushAudit {
        val currency = LiquidityBucketLogic.normalizeCurrency(request.currencyCode)
        val key = mutexKey(request.sessionDate, currency)
        if (!request.enabled) {
            return emptyAudit(request, skippedDisabled = true)
        }
        if (flushInFlight.putIfAbsent(key, true) != null) {
            return emptyAudit(request, skippedInFlight = true)
        }
        return try {
            mutexFor(key).withLock {
                flushUnderLock(request, currency)
            }
        } finally {
            flushInFlight.remove(key)
        }
    }

    private suspend fun flushUnderLock(
        request: LiquidityFlushRequest,
        currency: String,
    ): LiquidityFlushAudit {
        val startingAvailable = poolAvailable(currency, request.sessionDate)
        if (startingAvailable <= 0) {
            return LiquidityFlushAudit(
                currencyCode = currency,
                sessionDate = request.sessionDate,
                startingPoolAvailable = startingAvailable,
                remainingPoolAvailable = startingAvailable,
                skippedEmptyPool = true,
            )
        }

        val loopAudits = mutableListOf<LiquidityFlushLoopAudit>()
        repeat(AUTO_LIQUIDITY_FLUSH_MAX_LOOPS) { index ->
            val loopIndex = index + 1
            val available = poolAvailable(currency, request.sessionDate)
            if (available <= 0) return@repeat

            val eligibleRows = eligibleRowsWithQuotes(
                deployments = request.deployments,
                openOrders = request.openOrders,
                quotes = request.quotes,
                currency = currency,
            )
            if (eligibleRows.isEmpty()) return@repeat

            val distribution = distributeLiquidityByBayesianWinRate(
                rows = eligibleRows.map { row ->
                    row.deploymentId to (row.winDays to row.lossDays)
                },
                available = available,
            )
            if (distribution.isEmpty()) return@repeat

            val debited = mutableMapOf<String, Int>()
            val skippedProximity = mutableSetOf<String>()
            val skippedLot = mutableSetOf<String>()
            val skippedNotEligible = mutableSetOf<String>()
            val failedResize = mutableMapOf<String, String>()

            for ((deploymentId, dollarWeight) in distribution.toSortedMap()) {
                val deployment = request.deployments.find { it.id == deploymentId } ?: continue
                val freshOrders = request.openOrders
                val freshQuotes = request.quotes
                val row = LiquidityAllocatorMapper.buildRowForDeployment(
                    deployment = deployment,
                    openOrders = freshOrders,
                    quotes = freshQuotes,
                    selectedCurrency = currency,
                    allocationDollars = dollarWeight,
                )
                if (row == null) {
                    skippedNotEligible.add(deploymentId)
                    continue
                }
                val additionalQty = row.previewQuantity - row.currentQuantity
                if (additionalQty <= 0) {
                    skippedLot.add(deploymentId)
                    continue
                }
                if (LiquidityEntryProximityGuard.shouldSkipResize(row.entryTouchable)) {
                    skippedProximity.add(deploymentId)
                    continue
                }
                val applyResult = applier.apply(
                    LiquidityAllocationApplyRequest(
                        deploymentId = deploymentId,
                        allocationDollars = dollarWeight,
                        deployment = deployment,
                        openOrders = freshOrders,
                        quotes = freshQuotes,
                        selectedCurrency = currency,
                        sessionDate = request.sessionDate,
                    )
                )
                when (applyResult) {
                    is LiquidityAllocationApplyResult.Success ->
                        debited[deploymentId] = applyResult.debitedAmount
                    is LiquidityAllocationApplyResult.Skipped -> when (applyResult.reason) {
                        LiquidityApplySkipReason.NO_ADDITIONAL_QUANTITY,
                        LiquidityApplySkipReason.PREVIEW_NOT_GREATER_THAN_CURRENT ->
                            skippedLot.add(deploymentId)
                        LiquidityApplySkipReason.NOT_ELIGIBLE,
                        LiquidityApplySkipReason.SESSION_NOT_ACTIVE ->
                            skippedNotEligible.add(deploymentId)
                        LiquidityApplySkipReason.EXECUTION_NOT_AVAILABLE ->
                            failedResize[deploymentId] = "execution_not_available"
                    }
                    is LiquidityAllocationApplyResult.Failed ->
                        failedResize[deploymentId] = applyResult.message
                }
            }

            loopAudits += LiquidityFlushLoopAudit(
                loopIndex = loopIndex,
                debited = debited.toMap(),
                skippedProximity = skippedProximity.toSet(),
                skippedLot = skippedLot.toSet(),
                skippedNotEligible = skippedNotEligible.toSet(),
                failedResize = failedResize.toMap(),
            )
        }

        val remaining = poolAvailable(currency, request.sessionDate)
        return LiquidityFlushAudit(
            currencyCode = currency,
            sessionDate = request.sessionDate,
            startingPoolAvailable = startingAvailable,
            remainingPoolAvailable = remaining,
            loops = loopAudits,
        )
    }

    private fun eligibleRowsWithQuotes(
        deployments: List<daytrader.domain.StrategyDeployment>,
        openOrders: List<WorkingOrder>,
        quotes: Map<String, LiveQuote>,
        currency: String,
    ) = LiquidityAllocatorMapper.buildRows(
        deployments = deployments,
        openOrders = openOrders,
        quotes = quotes,
        selectedCurrency = currency,
    ).filter { row ->
        val quote = quotes[SymbolMarkets.normalizeSymbol(row.symbol)]
        quote?.bid != null && quote.ask != null
    }

    private fun poolAvailable(currency: String, sessionDate: String): Int {
        val bucket = LiquidityBucketLogic.rollBucketForDate(
            LiquidityBucketLogic.bucketForCurrency(liquidityBucketRepository.state.value, currency),
            sessionDate,
        )
        return bucket.available
    }

    private fun emptyAudit(
        request: LiquidityFlushRequest,
        skippedDisabled: Boolean = false,
        skippedInFlight: Boolean = false,
    ): LiquidityFlushAudit {
        val currency = LiquidityBucketLogic.normalizeCurrency(request.currencyCode)
        val available = poolAvailable(currency, request.sessionDate)
        return LiquidityFlushAudit(
            currencyCode = currency,
            sessionDate = request.sessionDate,
            startingPoolAvailable = available,
            remainingPoolAvailable = available,
            skippedDisabled = skippedDisabled,
            skippedInFlight = skippedInFlight,
        )
    }

    private fun mutexKey(sessionDate: String, currencyCode: String): String =
        "$sessionDate:${LiquidityBucketLogic.normalizeCurrency(currencyCode)}"

    private fun mutexFor(key: String): Mutex =
        bucketMutexes.getOrPut(key) { Mutex() }
}

fun deploymentsForZone(
    deployments: List<daytrader.domain.StrategyDeployment>,
    zoneId: String,
): List<daytrader.domain.StrategyDeployment> =
    deployments.filter { DeploymentMarket.effectiveZoneId(it) == zoneId }

fun activeCurrenciesForDeployments(
    deployments: List<daytrader.domain.StrategyDeployment>,
): Set<String> =
    deployments.map { LiquidityBucketLogic.normalizeCurrency(it.currencyCode) }.toSet()
