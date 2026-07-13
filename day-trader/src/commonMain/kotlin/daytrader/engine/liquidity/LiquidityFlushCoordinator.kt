package daytrader.engine.liquidity

import daytrader.data.LiquidityBucketRepository
import daytrader.data.StrategyDeploymentRepository
import daytrader.domain.AUTO_LIQUIDITY_FLUSH_MAX_LOOPS
import daytrader.domain.DeploymentMarket
import daytrader.domain.LiquidityBucketLogic
import daytrader.domain.InstrumentOrderSizeRules
import daytrader.domain.orderSizeRules
import daytrader.execution.ExecutionManager
import daytrader.gateway.LiveQuote
import daytrader.gateway.WorkingOrder
import daytrader.presentation.liquidity.LiquidityAllocatorMapper
import daytrader.presentation.liquidity.distributeLiquidityByBayesianWinRateInLots
import daytrader.presentation.liquidity.LiquidityLotAllocationRow
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
        var openOrdersSnapshot = request.openOrders
        repeat(AUTO_LIQUIDITY_FLUSH_MAX_LOOPS) { index ->
            val loopIndex = index + 1
            val available = poolAvailable(currency, request.sessionDate)
            if (available <= 0) return@repeat

            val deploymentsSnapshot = deploymentRepository.deployments.value
                .filter { dep -> request.deployments.any { it.id == dep.id } }

            val eligibleRows = eligibleRows(
                deployments = deploymentsSnapshot,
                openOrders = openOrdersSnapshot,
                quotes = request.quotes,
                currency = currency,
            )
            if (eligibleRows.isEmpty()) {
                loopAudits += LiquidityFlushLoopAudit(
                    loopIndex = loopIndex,
                    eligibleCount = 0,
                    distributionCount = 0,
                )
                return@repeat
            }

            val distribution = distributeLiquidityByBayesianWinRateInLots(
                rows = eligibleRows.mapNotNull { row ->
                    val deployment = deploymentsSnapshot.find { it.id == row.deploymentId } ?: return@mapNotNull null
                    LiquidityLotAllocationRow(
                        deploymentId = row.deploymentId,
                        winDays = row.winDays,
                        lossDays = row.lossDays,
                        entryPrice = row.entryPrice,
                        orderSizeRules = deployment.instrument?.orderSizeRules()
                            ?: InstrumentOrderSizeRules.DEFAULT,
                        currentQuantity = row.currentQuantity,
                    )
                },
                available = available,
            )
            if (distribution.isEmpty()) {
                loopAudits += LiquidityFlushLoopAudit(
                    loopIndex = loopIndex,
                    eligibleCount = eligibleRows.size,
                    distributionCount = 0,
                )
                return@repeat
            }

            val debited = mutableMapOf<String, Int>()
            val skippedLot = mutableSetOf<String>()
            val skippedNotEligible = mutableSetOf<String>()
            val failedResize = mutableMapOf<String, String>()

            for ((deploymentId, dollarWeight) in distribution.toSortedMap()) {
                val deployment = deploymentRepository.deployments.value.find { it.id == deploymentId }
                    ?: deploymentsSnapshot.find { it.id == deploymentId }
                    ?: continue
                val freshOrders = openOrdersSnapshot
                val freshQuotes = request.quotes
                val row = LiquidityAllocatorMapper.buildRowForDeploymentFromDollars(
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
                val applyResult = applier.apply(
                    LiquidityAllocationApplyRequest(
                        deploymentId = deploymentId,
                        additionalQuantity = additionalQty,
                        deployment = deployment,
                        openOrders = freshOrders,
                        quotes = freshQuotes,
                        selectedCurrency = currency,
                        sessionDate = request.sessionDate,
                    )
                )
                when (applyResult) {
                    is LiquidityAllocationApplyResult.Success -> {
                        debited[deploymentId] = applyResult.debitedAmount
                        openOrdersSnapshot = LiquidityAllocatorMapper.openOrdersWithBracketQuantity(
                            openOrders = openOrdersSnapshot,
                            deployment = deploymentRepository.deployments.value
                                .find { it.id == deploymentId }
                                ?: deployment,
                            newQuantity = applyResult.newQuantity,
                        )
                    }
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
                eligibleCount = eligibleRows.size,
                distributionCount = distribution.size,
                debited = debited.toMap(),
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

    private fun eligibleRows(
        deployments: List<daytrader.domain.StrategyDeployment>,
        openOrders: List<WorkingOrder>,
        quotes: Map<String, LiveQuote>,
        currency: String,
    ) = LiquidityAllocatorMapper.buildRows(
        deployments = deployments,
        openOrders = openOrders,
        quotes = quotes,
        selectedCurrency = currency,
    )

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
