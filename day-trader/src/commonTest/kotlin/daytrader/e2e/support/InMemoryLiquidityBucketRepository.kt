package daytrader.e2e.support

import daytrader.data.LiquidityBucketRepository
import daytrader.domain.LiquidityBucketLogic
import daytrader.domain.LiquidityBucketState
import daytrader.domain.TouchTurnSessionContext
import daytrader.domain.TouchTurnSessionOutcome
import daytrader.domain.resolveTouchTurnSessionOutcome
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class InMemoryLiquidityBucketRepository(
    initial: LiquidityBucketState = LiquidityBucketState(),
) : LiquidityBucketRepository {
    private val _state = MutableStateFlow(initial)
    override val state: StateFlow<LiquidityBucketState> = _state.asStateFlow()
    var flushInvocationCount: Int = 0
        private set

    override fun update(transform: (LiquidityBucketState) -> LiquidityBucketState) {
        _state.update(transform)
    }

    override fun flushPersistence() {
        flushInvocationCount++
    }

    override fun flushPersistenceBlocking() {
        flushInvocationCount++
    }

    override fun creditNoTradeSession(
        sessionId: String,
        deploymentId: String,
        symbol: String,
        currencyCode: String,
        sessionDate: String,
        maxDollars: Int,
        touchTurn: TouchTurnSessionContext?,
        creditedAtEpochMs: Long,
    ) {
        if (!LiquidityBucketLogic.isNoTradeCreditEligible(touchTurn, maxDollars)) return
        val outcome = touchTurn?.decisionOutcome
            ?: touchTurn?.let { resolveTouchTurnSessionOutcome(it) }
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
                creditedAtEpochMs = creditedAtEpochMs,
            )
        }
    }

    override fun debitAllocation(
        currencyCode: String,
        sessionDate: String,
        deploymentId: String,
        symbol: String,
        amount: Int,
        debitedAtEpochMs: Long,
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
                debitedAtEpochMs = debitedAtEpochMs,
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

    override fun clearCurrencyBucket(
        currencyCode: String,
        sessionDate: String,
    ): Result<Int> {
        var result: Result<Int> = Result.failure(IllegalStateException("not_updated"))
        update { state ->
            LiquidityBucketLogic.clearBucketForDate(
                state = state,
                currencyCode = currencyCode,
                sessionDate = sessionDate,
            ).also { cleared ->
                result = cleared.map { (_, amount) -> amount }
            }.getOrElse { return@update state }
                .first
        }
        return result
    }
}
