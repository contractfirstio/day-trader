package daytrader.broker

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ExpectedRoundTripCommissionTest {
    @Test
    fun hk_smallNotional_usesIbMinimumPerLeg() {
        // 100 shares @ 10 HKD = 1,000 notional → IB min 18/leg dominates
        val rt = ExpectedRoundTripCommission.estimateRoundTrip(
            quantity = 100,
            entryPrice = 10.0,
            exitPrice = 10.5,
            currency = "HKD",
            primaryExch = "SEHK",
            exchange = "SEHK",
        )
        // entry @10: IB 18 + levies 0.13 + stamp 1; exit @10.5: IB 18 + levies 0.13 + stamp 2
        assertEquals(39.26, rt, 0.02)
    }

    @Test
    fun hk_midNotional_matchesHistoricalBallpark() {
        // ~9k @ 8.56 ≈ Jul 22 00939 starter size; RT should land ~195–250 HKD
        val rt = ExpectedRoundTripCommission.estimateRoundTrip(
            quantity = 9_000,
            entryPrice = 8.56,
            exitPrice = 8.56,
            currency = "HKD",
            primaryExch = "SEHK",
            exchange = "SEHK",
        )
        assertTrue(rt in 195.0..260.0, "expected ballpark RT commission, got $rt")
    }

    @Test
    fun hk_largeNotional_scalesWithStampAndPctFees() {
        // ~149k @ 8.56 ≈ full-pool flush counterfactual
        val rt = ExpectedRoundTripCommission.estimateRoundTrip(
            quantity = 149_000,
            entryPrice = 8.56,
            exitPrice = 8.56,
            currency = "HKD",
            primaryExch = "SEHK",
            exchange = "SEHK",
        )
        assertEquals(4151.78, rt, 1.0)
    }

    @Test
    fun us_limitRoundTrip_usesTieredMinimums() {
        val rt = ExpectedRoundTripCommission.estimateRoundTrip(
            quantity = 10,
            entryPrice = 100.0,
            exitPrice = 110.0,
            currency = "USD",
            primaryExch = "NASDAQ",
            exchange = "SMART",
            entryOrderType = "LMT",
            exitOrderType = "LMT",
        )
        assertEquals(0.70, rt, 0.001)
    }

    @Test
    fun us_stopExit_addsTakerExchangeFee() {
        val rt = ExpectedRoundTripCommission.estimateRoundTrip(
            quantity = 1_000,
            entryPrice = 100.0,
            exitPrice = 95.0,
            currency = "USD",
            primaryExch = "NASDAQ",
            exchange = "SMART",
            entryOrderType = "LMT",
            exitOrderType = "STP",
        )
        // LMT 1000 * 0.0035 = 3.50; STP 1000 * 0.0065 = 6.50
        assertEquals(10.00, rt, 0.001)
    }

    @Test
    fun unsupportedMarket_returnsZero() {
        val rt = ExpectedRoundTripCommission.estimateRoundTrip(
            quantity = 100,
            entryPrice = 100.0,
            exitPrice = 101.0,
            currency = "GBP",
            primaryExch = "LSE",
            exchange = "LSE",
        )
        assertEquals(0.0, rt, 0.0)
    }
}
