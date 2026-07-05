package daytrader.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LiquidityBucketLogicTest {
    @Test
    fun creditSession_onlyOncePerSessionId() {
        var state = LiquidityBucketState()
        state = LiquidityBucketLogic.creditSession(
            state = state,
            currencyCode = "USD",
            sessionDate = "2026-06-12",
            sessionId = "s1",
            deploymentId = "d1",
            symbol = "AAPL",
            amount = 500,
            outcome = TouchTurnSessionOutcome.NO_TRADE_DOJI,
            creditedAtEpochMs = 1L
        )
        state = LiquidityBucketLogic.creditSession(
            state = state,
            currencyCode = "USD",
            sessionDate = "2026-06-12",
            sessionId = "s1",
            deploymentId = "d1",
            symbol = "AAPL",
            amount = 500,
            outcome = TouchTurnSessionOutcome.NO_TRADE_DOJI,
            creditedAtEpochMs = 2L
        )
        assertEquals(500, LiquidityBucketLogic.bucketForCurrency(state, "USD").available)
    }

    @Test
    fun debitAllocation_respectsCurrencyPartition() {
        var state = LiquidityBucketLogic.creditSession(
            state = LiquidityBucketState(),
            currencyCode = "USD",
            sessionDate = "2026-06-12",
            sessionId = "s1",
            deploymentId = "d1",
            symbol = "AAPL",
            amount = 500,
            outcome = TouchTurnSessionOutcome.NO_TRADE_DOJI,
            creditedAtEpochMs = 1L
        )
        val debit = LiquidityBucketLogic.debitAllocation(
            state = state,
            currencyCode = "USD",
            sessionDate = "2026-06-12",
            deploymentId = "d2",
            symbol = "MSFT",
            amount = 200,
            debitedAtEpochMs = 2L
        )
        assertTrue(debit.isSuccess)
        assertEquals(300, LiquidityBucketLogic.bucketForCurrency(debit.getOrThrow(), "USD").available)
        val gbpDebit = LiquidityBucketLogic.debitAllocation(
            state = debit.getOrThrow(),
            currencyCode = "GBP",
            sessionDate = "2026-06-12",
            deploymentId = "d3",
            symbol = "BARC",
            amount = 100,
            debitedAtEpochMs = 3L
        )
        assertTrue(gbpDebit.isFailure)
    }

    @Test
    fun refundAllocation_restoresDebitedAmountForMatchingDeployment() {
        var state = LiquidityBucketLogic.creditSession(
            state = LiquidityBucketState(),
            currencyCode = "USD",
            sessionDate = "2026-06-12",
            sessionId = "s1",
            deploymentId = "d1",
            symbol = "AAPL",
            amount = 500,
            outcome = TouchTurnSessionOutcome.NO_TRADE_DOJI,
            creditedAtEpochMs = 1L,
        )
        val debited = LiquidityBucketLogic.debitAllocation(
            state = state,
            currencyCode = "USD",
            sessionDate = "2026-06-12",
            deploymentId = "dep-e2e-1",
            symbol = "AAPL",
            amount = 200,
            debitedAtEpochMs = 2L,
        ).getOrThrow()
        assertEquals(300, LiquidityBucketLogic.bucketForCurrency(debited, "USD").available)

        val refunded = LiquidityBucketLogic.refundAllocation(
            state = debited,
            currencyCode = "USD",
            sessionDate = "2026-06-12",
            deploymentId = "dep-e2e-1",
            amount = 200,
        ).getOrThrow()
        assertEquals(500, LiquidityBucketLogic.bucketForCurrency(refunded, "USD").available)
        assertTrue(LiquidityBucketLogic.bucketForCurrency(refunded, "USD").debits.isEmpty())
    }

    @Test
    fun isNoTradeCreditEligible_falseWhenOrdersPlaced() {
        val touchTurn = TouchTurnSessionContext(
            sessionDate = "2026-06-12",
            status = TouchTurnCandleStatus.READY,
            ordersPlacedForSession = true,
            decisionOutcome = TouchTurnSessionOutcome.TRADE_BRACKET_SUBMITTED
        )
        assertFalse(LiquidityBucketLogic.isNoTradeCreditEligible(touchTurn, 500))
    }
}
