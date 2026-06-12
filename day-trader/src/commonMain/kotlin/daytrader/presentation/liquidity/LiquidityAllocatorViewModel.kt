package daytrader.presentation.liquidity

import daytrader.broker.SymbolMarkets
import daytrader.data.LiquidityBucketRepository
import daytrader.data.OpenOrderRepository
import daytrader.data.StrategyDeploymentRepository
import daytrader.domain.DeploymentStatus
import daytrader.domain.LiquidityBucketLogic
import daytrader.domain.StrategyDeployment
import daytrader.domain.isTouchTurn
import daytrader.execution.ExecutionManager
import daytrader.gateway.BrokerGateway
import daytrader.gateway.LiveQuote
import daytrader.gateway.WorkingOrder
import daytrader.platform.currentSessionDateIso
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class LiquidityAllocatorViewModel(
    private val deploymentRepository: StrategyDeploymentRepository,
    private val openOrderRepository: OpenOrderRepository,
    private val liquidityBucketRepository: LiquidityBucketRepository,
    private val brokerGateway: BrokerGateway?,
    private val executionManager: ExecutionManager?,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) {
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
            refreshQuotes(deployments)
            publishUi()
        }.launchIn(scope)

        brokerGateway?.quotes?.onEach { quotes ->
            latestQuotes = quotes
            publishUi()
        }?.launchIn(scope)
    }

    fun onCurrencySelected(currencyCode: String) {
        selectedCurrency = LiquidityBucketLogic.normalizeCurrency(currencyCode)
        allocations.clear()
        applyErrors.clear()
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
        val sessionDate = currentSessionDateIso()
        val currency = LiquidityBucketLogic.normalizeCurrency(selectedCurrency)
        val bucket = LiquidityBucketLogic.rollBucketForDate(
            LiquidityBucketLogic.bucketForCurrency(latestBucketState, currency),
            sessionDate
        )
        val rows = LiquidityAllocatorMapper.buildUiState(
            deployments = latestDeployments,
            openOrders = latestOpenOrders,
            quotes = latestQuotes,
            bucketState = latestBucketState,
            sessionDate = sessionDate,
            selectedCurrency = currency,
            allocations = emptyMap(),
            applyingDeploymentIds = emptySet(),
            applyErrors = emptyMap()
        ).rows
        if (rows.isEmpty() || bucket.available <= 0) return
        val perRow = bucket.available / rows.size
        val remainder = bucket.available % rows.size
        allocations.clear()
        rows.forEachIndexed { index, row ->
            val amount = perRow + if (index == 0) remainder else 0
            if (amount > 0) allocations[row.deploymentId] = amount
        }
        applyErrors.clear()
        publishUi()
    }

    fun applyRow(deploymentId: String) {
        val allocation = allocations[deploymentId] ?: return
        if (allocation <= 0) return
        scope.launch { applyInternal(deploymentId, allocation) }
    }

    fun applyAll() {
        val pending = allocations.filterValues { it > 0 }
        if (pending.isEmpty()) return
        scope.launch {
            pending.forEach { (deploymentId, amount) ->
                applyInternal(deploymentId, amount)
            }
        }
    }

    private suspend fun applyInternal(deploymentId: String, allocationDollars: Int) {
        val execution = executionManager ?: run {
            setApplyError(deploymentId, "Execution not available")
            return
        }
        val deployment = latestDeployments.find { it.id == deploymentId } ?: return
        if (!deployment.isTouchTurn || deployment.status != DeploymentStatus.RUNNING) {
            setApplyError(deploymentId, "Session no longer active")
            return
        }
        val row = LiquidityAllocatorMapper.buildUiState(
            deployments = listOf(deployment),
            openOrders = latestOpenOrders,
            quotes = latestQuotes,
            bucketState = latestBucketState,
            sessionDate = currentSessionDateIso(),
            selectedCurrency = selectedCurrency,
            allocations = mapOf(deploymentId to allocationDollars),
            applyingDeploymentIds = emptySet(),
            applyErrors = emptyMap()
        ).rows.singleOrNull() ?: run {
            setApplyError(deploymentId, "Entry order no longer eligible")
            return
        }
        val resizeRequest = LiquidityAllocatorMapper.buildResizeRequest(
            deployment = deployment,
            openOrders = latestOpenOrders,
            newQuantity = row.previewQuantity
        ) ?: run {
            setApplyError(deploymentId, "Could not build resize request")
            return
        }
        applyingDeploymentIds.add(deploymentId)
        applyErrors.remove(deploymentId)
        publishUi()

        val sessionDate = deployment.touchTurnSession?.sessionDate ?: currentSessionDateIso()
        val debitResult = liquidityBucketRepository.debitAllocation(
            currencyCode = deployment.currencyCode,
            sessionDate = sessionDate,
            deploymentId = deploymentId,
            symbol = deployment.symbol,
            amount = allocationDollars
        )
        if (debitResult.isFailure) {
            applyingDeploymentIds.remove(deploymentId)
            setApplyError(deploymentId, debitResult.exceptionOrNull()?.message ?: "Debit failed")
            return
        }

        val resizeResult = execution.resizeTouchTurnBracket(resizeRequest)
        applyingDeploymentIds.remove(deploymentId)
        if (resizeResult.isFailure) {
            liquidityBucketRepository.creditNoTradeSession(
                sessionId = "refund-${deploymentId}-${System.currentTimeMillis()}",
                deploymentId = deploymentId,
                symbol = deployment.symbol,
                currencyCode = deployment.currencyCode,
                sessionDate = sessionDate,
                maxDollars = allocationDollars,
                touchTurn = deployment.touchTurnSession?.copy(ordersPlacedForSession = false),
                creditedAtEpochMs = System.currentTimeMillis()
            )
            setApplyError(deploymentId, resizeResult.exceptionOrNull()?.message ?: "Resize failed")
            return
        }

        deploymentRepository.update(deploymentId) { current ->
            current.withTouchTurnBracketQuantity(row.previewQuantity)
        }
        allocations.remove(deploymentId)
        applyErrors.remove(deploymentId)
        liquidityBucketRepository.flushPersistence()
        publishUi()
    }

    private fun setApplyError(deploymentId: String, message: String) {
        applyErrors[deploymentId] = message
        publishUi()
    }

    private fun refreshQuotes(deployments: List<StrategyDeployment>) {
        val gateway = brokerGateway ?: return
        deployments
            .filter { it.isTouchTurn && it.status == DeploymentStatus.RUNNING }
            .forEach { deployment ->
                val norm = SymbolMarkets.normalizeSymbol(deployment.symbol)
                if (latestQuotes[norm] == null) {
                    latestQuotes = gateway.quotes.value
                }
            }
    }

    private fun publishUi() {
        val sessionDate = currentSessionDateIso()
        val state = LiquidityAllocatorMapper.buildUiState(
            deployments = latestDeployments,
            openOrders = latestOpenOrders,
            quotes = latestQuotes,
            bucketState = latestBucketState,
            sessionDate = sessionDate,
            selectedCurrency = selectedCurrency,
            allocations = allocations.toMap(),
            applyingDeploymentIds = applyingDeploymentIds.toSet(),
            applyErrors = applyErrors.toMap()
        )
        if (state.currencyOptions.isNotEmpty() &&
            state.currencyOptions.none { it.currencyCode == selectedCurrency }
        ) {
            selectedCurrency = state.currencyOptions.first().currencyCode
        }
        _uiState.value = state.copy(selectedCurrency = selectedCurrency)
    }
}

private fun StrategyDeployment.withTouchTurnBracketQuantity(quantity: Int): StrategyDeployment {
    val session = touchTurnSession ?: return this
    return copy(touchTurnSession = session.copy(plannedQuantity = quantity))
}
