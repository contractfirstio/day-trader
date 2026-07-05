package daytrader.data

import daytrader.data.persistence.DebouncedFileWriter
import daytrader.data.persistence.DeferredFileHydration
import daytrader.data.persistence.JsonFileStore
import daytrader.data.persistence.LiquidityBucketPersistence
import daytrader.data.persistence.launchDeferredFileHydration
import daytrader.domain.LiquidityBucketLogic
import daytrader.domain.LiquidityBucketState
import daytrader.domain.TouchTurnSessionOutcome
import daytrader.domain.TouchTurnSessionContext
import daytrader.platform.AppFileSystem
import daytrader.platform.currentSessionDateIso
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

interface LiquidityBucketRepository {
    val state: StateFlow<LiquidityBucketState>
    fun update(transform: (LiquidityBucketState) -> LiquidityBucketState)
    fun flushPersistence()
    fun flushPersistenceBlocking()
    fun creditNoTradeSession(
        sessionId: String,
        deploymentId: String,
        symbol: String,
        currencyCode: String,
        sessionDate: String,
        maxDollars: Int,
        touchTurn: TouchTurnSessionContext?,
        creditedAtEpochMs: Long = System.currentTimeMillis()
    )
    fun debitAllocation(
        currencyCode: String,
        sessionDate: String,
        deploymentId: String,
        symbol: String,
        amount: Int,
        debitedAtEpochMs: Long = System.currentTimeMillis()
    ): Result<Unit>
    fun refundAllocation(
        currencyCode: String,
        sessionDate: String,
        deploymentId: String,
        amount: Int,
    ): Result<Unit>
}

class FileLiquidityBucketRepository(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) : LiquidityBucketRepository {
    private val writer = DebouncedFileWriter<LiquidityBucketState>(scope) { state ->
        JsonFileStore.writeLiquidityBuckets(LiquidityBucketPersistence.toDocument(state))
    }
    private val hydration = DeferredFileHydration()

    private val _state = MutableStateFlow(LiquidityBucketState())
    override val state: StateFlow<LiquidityBucketState> = _state.asStateFlow()

    init {
        scope.launchDeferredFileHydration(hydration) {
            _state.value = loadInitial()
        }
    }

    override fun update(transform: (LiquidityBucketState) -> LiquidityBucketState) {
        _state.update(transform)
        writer.schedule(_state.value)
    }

    override fun flushPersistence() {
        writer.flush(_state.value)
    }

    override fun flushPersistenceBlocking() {
        writer.flushBlocking(_state.value)
    }

    override fun creditNoTradeSession(
        sessionId: String,
        deploymentId: String,
        symbol: String,
        currencyCode: String,
        sessionDate: String,
        maxDollars: Int,
        touchTurn: TouchTurnSessionContext?,
        creditedAtEpochMs: Long
    ) {
        if (!LiquidityBucketLogic.isNoTradeCreditEligible(touchTurn, maxDollars)) return
        val outcome = touchTurn?.decisionOutcome
            ?: touchTurn?.let { daytrader.domain.resolveTouchTurnSessionOutcome(it) }
            ?: TouchTurnSessionOutcome.NO_TRADE_ORDER_REJECTED
        val amount = LiquidityBucketLogic.creditAmountForSession(maxDollars)
        update { state ->
            LiquidityBucketLogic.creditSession(
                state = state,
                currencyCode = currencyCode,
                sessionDate = sessionDate,
                sessionId = sessionId,
                deploymentId = deploymentId,
                symbol = symbol,
                amount = amount,
                outcome = outcome,
                creditedAtEpochMs = creditedAtEpochMs
            )
        }
    }

    override fun debitAllocation(
        currencyCode: String,
        sessionDate: String,
        deploymentId: String,
        symbol: String,
        amount: Int,
        debitedAtEpochMs: Long
    ): Result<Unit> {
        var result: Result<Unit> = Result.failure(IllegalStateException("not_updated"))
        update { state ->
            LiquidityBucketLogic.debitAllocation(
                state = state,
                currencyCode = currencyCode,
                sessionDate = sessionDate,
                deploymentId = deploymentId,
                symbol = symbol,
                amount = amount,
                debitedAtEpochMs = debitedAtEpochMs
            ).also { result = it.map { } }
                .getOrElse { return@update state }
        }
        return result
    }

    override fun refundAllocation(
        currencyCode: String,
        sessionDate: String,
        deploymentId: String,
        amount: Int,
    ): Result<Unit> {
        var result: Result<Unit> = Result.failure(IllegalStateException("not_updated"))
        update { state ->
            LiquidityBucketLogic.refundAllocation(
                state = state,
                currencyCode = currencyCode,
                sessionDate = sessionDate,
                deploymentId = deploymentId,
                amount = amount,
            ).also { result = it.map { } }
                .getOrElse { return@update state }
        }
        return result
    }

    private fun loadInitial(): LiquidityBucketState {
        AppFileSystem.ensureAppDataDirectory()
        return JsonFileStore.readLiquidityBuckets()
            ?.let(LiquidityBucketPersistence::fromDocument)
            ?: LiquidityBucketState()
    }
}
