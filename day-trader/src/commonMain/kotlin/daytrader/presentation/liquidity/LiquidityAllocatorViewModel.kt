package daytrader.presentation.liquidity

import daytrader.broker.SymbolMarkets
import daytrader.data.LiquidityBucketRepository
import daytrader.data.OpenOrderRepository
import daytrader.data.StrategyDeploymentRepository
import daytrader.domain.LiquidityBucketLogic
import daytrader.domain.StrategyDeployment
import daytrader.domain.isTouchTurn
import daytrader.engine.liquidity.LiquidityAllocationApplyRequest
import daytrader.engine.liquidity.LiquidityAllocationApplyResult
import daytrader.engine.liquidity.LiquidityFlushCoordinator
import daytrader.execution.ExecutionManager
import daytrader.gateway.BrokerGateway
import daytrader.gateway.LiveQuote
import daytrader.gateway.WorkingOrder
import daytrader.platform.currentSessionDateIso
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.flow.update
import daytrader.presentation.strategies.SessionRollupCache
import daytrader.presentation.navigation.AppScreen
import daytrader.presentation.ui.UiCoroutineScopes
import daytrader.presentation.ui.launchUiAction
import daytrader.presentation.ui.safeUiEmit
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

@OptIn(FlowPreview::class)
class LiquidityAllocatorViewModel(
    private val deploymentRepository: StrategyDeploymentRepository,
    private val openOrderRepository: OpenOrderRepository,
    private val liquidityBucketRepository: LiquidityBucketRepository,
    private val brokerGateway: BrokerGateway?,
    private val executionManager: ExecutionManager?,
    private val skipQuoteUiRefresh: () -> Boolean = { false },
    private val flushCoordinator: LiquidityFlushCoordinator = LiquidityFlushCoordinator(
        liquidityBucketRepository = liquidityBucketRepository,
        executionManager = executionManager,
        deploymentRepository = deploymentRepository,
    ),
    scope: CoroutineScope = UiCoroutineScopes.forScreen(AppScreen.LIQUIDITY, "LiquidityAllocatorViewModel"),
) {
    private val scope = scope
    private val _uiState = MutableStateFlow(LiquidityAllocatorUiState())
    val uiState: StateFlow<LiquidityAllocatorUiState> = _uiState.asStateFlow()

    private var selectedCurrency = "USD"
    private val allocations = mutableMapOf<String, Int>()
    private val applyingDeploymentIds = mutableSetOf<String>()
    private val applyErrors = mutableMapOf<String, String>()

    private var latestDeployments: List<StrategyDeployment> = emptyList()
    private var latestOpenOrders: List<WorkingOrder> = emptyList()
    private var latestQuotes: Map<String, LiveQuote> = emptyMap()
    private var latestBucketState = daytrader.domain.LiquidityBucketState()
    private val sessionRollupCache = SessionRollupCache()
    private var globalMessage: String? = null

    init {
        combine(
            deploymentRepository.deployments,
            openOrderRepository.openOrders,
            liquidityBucketRepository.state
        ) { deployments, openOrders, bucketState ->
            Triple(deployments, openOrders, bucketState)
        }.onEach { (deployments, openOrders, bucketState) ->
            latestDeployments = deployments
            latestOpenOrders = openOrders
            latestBucketState = bucketState
            sessionRollupCache.clear()
            refreshQuotes(deployments)
            publishUi()
        }.launchIn(scope)

        brokerGateway?.quotes?.let { quotesFlow ->
            quotesFlow
                .onEach { latestQuotes = it }
                .launchIn(scope)
            quotesFlow
                .sample(QUOTE_UI_REFRESH_INTERVAL_MS.milliseconds)
                .onEach {
                    if (!skipQuoteUiRefresh()) {
                        publishUi()
                    }
                }
                .launchIn(scope)
        }
    }

    fun onCurrencySelected(currencyCode: String) {
        selectedCurrency = LiquidityBucketLogic.normalizeCurrency(currencyCode)
        allocations.clear()
        applyErrors.clear()
        globalMessage = null
        publishUi()
    }

    fun onAllocationChanged(deploymentId: String, dollars: Int) {
        val sanitized = dollars.coerceAtLeast(0)
        if (sanitized == 0) {
            allocations.remove(deploymentId)
        } else {
            allocations[deploymentId] = sanitized
        }
        applyErrors.remove(deploymentId)
        publishUi()
    }

    fun distributeEvenly() {
        val context = distributionContext() ?: return
        val perRow = context.available / context.rows.size
        val remainder = context.available % context.rows.size
        applyDistribution(
            context.rows.mapIndexed { index, row ->
                row.deploymentId to (perRow + if (index == 0) remainder else 0)
            }.toMap()
        )
    }

    fun distributeByWinRate() {
        val context = distributionContext() ?: return
        applyDistribution(
            distributeLiquidityByBayesianWinRate(
                rows = context.rows.map { row ->
                    row.deploymentId to (row.winDays to row.lossDays)
                },
                available = context.available,
            )
        )
    }

    private data class DistributionContext(
        val available: Int,
        val rows: List<LiquidityAllocatorRowUi>,
    )

    private fun distributionContext(): DistributionContext? {
        val sessionDate = currentSessionDateIso()
        val currency = LiquidityBucketLogic.normalizeCurrency(selectedCurrency)
        val bucket = LiquidityBucketLogic.rollBucketForDate(
            LiquidityBucketLogic.bucketForCurrency(latestBucketState, currency),
            sessionDate
        )
        val rows = LiquidityAllocatorMapper.buildRows(
            deployments = latestDeployments,
            openOrders = latestOpenOrders,
            quotes = latestQuotes,
            selectedCurrency = currency,
            sessionRollupCache = sessionRollupCache,
        )
        if (rows.isEmpty() || bucket.available <= 0) return null
        return DistributionContext(available = bucket.available, rows = rows)
    }

    private fun applyDistribution(distribution: Map<String, Int>) {
        allocations.clear()
        distribution.forEach { (deploymentId, amount) ->
            if (amount > 0) allocations[deploymentId] = amount
        }
        applyErrors.clear()
        publishUi()
    }

    fun applyRow(deploymentId: String) {
        val allocation = allocations[deploymentId] ?: return
        if (allocation <= 0) return
        scope.launchUiAction(AppScreen.LIQUIDITY, "applyRow") { applyInternal(deploymentId, allocation) }
    }

    fun applyAll() {
        val pending = allocations.filterValues { it > 0 }
        if (pending.isEmpty()) return
        scope.launchUiAction(AppScreen.LIQUIDITY, "applyAll") {
            pending.forEach { (deploymentId, amount) ->
                applyInternal(deploymentId, amount)
            }
        }
    }

    fun clearSelectedCurrencyLiquidity() {
        scope.launchUiAction(AppScreen.LIQUIDITY, "clearLiquidity") {
            clearSelectedCurrencyLiquidityInternal()
        }
    }

    private fun clearSelectedCurrencyLiquidityInternal() {
        val currency = LiquidityBucketLogic.normalizeCurrency(selectedCurrency)
        val sessionDate = currentSessionDateIso()
        val result = liquidityBucketRepository.clearCurrencyBucket(
            currencyCode = currency,
            sessionDate = sessionDate,
        )
        if (result.isFailure) {
            globalMessage = result.exceptionOrNull()?.message ?: "Clear failed"
            publishUi()
            return
        }
        val clearedAmount = result.getOrThrow()
        clearPendingStateForCurrency(currency)
        globalMessage = "Cleared ${daytrader.presentation.Formatters.maxAtRisk(clearedAmount)} $currency liquidity"
        liquidityBucketRepository.flushPersistence()
        publishUi()
    }

    private fun clearPendingStateForCurrency(currency: String) {
        val deploymentIds = latestDeployments
            .filter { LiquidityBucketLogic.normalizeCurrency(it.currencyCode) == currency }
            .map { it.id }
            .toSet()
        deploymentIds.forEach { deploymentId ->
            allocations.remove(deploymentId)
            applyErrors.remove(deploymentId)
        }
    }

    private suspend fun applyInternal(deploymentId: String, allocationDollars: Int) {
        val deployment = latestDeployments.find { it.id == deploymentId } ?: return
        applyingDeploymentIds.add(deploymentId)
        applyErrors.remove(deploymentId)
        publishUi()

        val sessionDate = deployment.touchTurnSession?.sessionDate ?: currentSessionDateIso()
        val result = flushCoordinator.applyAllocation(
            LiquidityAllocationApplyRequest(
                deploymentId = deploymentId,
                allocationDollars = allocationDollars,
                deployment = deployment,
                openOrders = latestOpenOrders,
                quotes = latestQuotes,
                selectedCurrency = selectedCurrency,
                sessionDate = sessionDate,
            ),
            sessionRollupCache = sessionRollupCache,
        )
        applyingDeploymentIds.remove(deploymentId)
        when (result) {
            is LiquidityAllocationApplyResult.Success -> {
                allocations.remove(deploymentId)
                applyErrors.remove(deploymentId)
            }
            is LiquidityAllocationApplyResult.Skipped -> setApplyError(
                deploymentId,
                when (result.reason) {
                    daytrader.engine.liquidity.LiquidityApplySkipReason.EXECUTION_NOT_AVAILABLE ->
                        "Execution not available"
                    daytrader.engine.liquidity.LiquidityApplySkipReason.SESSION_NOT_ACTIVE ->
                        "Session no longer active"
                    daytrader.engine.liquidity.LiquidityApplySkipReason.NOT_ELIGIBLE ->
                        "Entry order no longer eligible"
                    daytrader.engine.liquidity.LiquidityApplySkipReason.NO_ADDITIONAL_QUANTITY ->
                        "Allocation too small for a board lot"
                    daytrader.engine.liquidity.LiquidityApplySkipReason.PREVIEW_NOT_GREATER_THAN_CURRENT ->
                        "Allocation too small to increase size"
                },
            )
            is LiquidityAllocationApplyResult.Failed -> setApplyError(deploymentId, result.message)
        }
        publishUi()
    }

    private fun setApplyError(deploymentId: String, message: String) {
        applyErrors[deploymentId] = message
        publishUi()
    }

    private fun refreshQuotes(deployments: List<StrategyDeployment>) {
        val gateway = brokerGateway ?: return
        deployments
            .filter { it.isTouchTurn && it.status == daytrader.domain.DeploymentStatus.RUNNING }
            .forEach { deployment ->
                val norm = SymbolMarkets.normalizeSymbol(deployment.symbol)
                if (latestQuotes[norm] == null) {
                    latestQuotes = gateway.quotes.value
                }
            }
    }

    private fun publishUi() {
        safeUiEmit(AppScreen.LIQUIDITY, "publishUi") {
            val sessionDate = currentSessionDateIso()
            val built = LiquidityAllocatorMapper.buildUiState(
                deployments = latestDeployments,
                openOrders = latestOpenOrders,
                quotes = latestQuotes,
                bucketState = latestBucketState,
                sessionDate = sessionDate,
                selectedCurrency = selectedCurrency,
                allocations = allocations.toMap(),
                applyingDeploymentIds = applyingDeploymentIds.toSet(),
                applyErrors = applyErrors.toMap(),
                sessionRollupCache = sessionRollupCache,
            )
            if (built.currencyOptions.isNotEmpty() &&
                built.currencyOptions.none { it.currencyCode == selectedCurrency }
            ) {
                selectedCurrency = built.currencyOptions.first().currencyCode
            }
            val next = built.copy(
                selectedCurrency = selectedCurrency,
                globalMessage = globalMessage,
            )
            val prev = _uiState.value
            if (next.copy(lastUpdatedEpochMs = 0L) == prev.copy(lastUpdatedEpochMs = 0L)) return@safeUiEmit
            _uiState.value = next.copy(lastUpdatedEpochMs = System.currentTimeMillis())
        }
    }

    private companion object {
        const val QUOTE_UI_REFRESH_INTERVAL_MS = 100L
    }
}
