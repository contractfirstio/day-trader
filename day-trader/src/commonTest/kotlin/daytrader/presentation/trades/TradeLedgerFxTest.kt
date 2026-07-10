package daytrader.presentation.trades

import daytrader.gateway.BrokerFill
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TradeLedgerFxTest {
    @Test
    fun summarizeNormalized_convertsMixedCurrenciesToHkd() {
        val fills = listOf(
            fill(execId = "usd", symbol = "AAPL", currency = "USD", realizedPnL = 100.0, commission = 1.0),
            fill(execId = "hkd", symbol = "0700", currency = "HKD", realizedPnL = 50.0, commission = 5.0),
        )
        val summary = TradeLedgerFilter.summarizeNormalized(
            fills = fills,
            targetCurrency = "HKD",
            ratesToTarget = mapOf("USD" to 7.8),
        )
        assertEquals(830.0, summary.realizedPnL)
        assertEquals(12.8, summary.commission)
        assertEquals(setOf("HKD"), summary.currencies)
        assertEquals(setOf("USD", "HKD"), summary.sourceCurrencies)
        assertEquals("HKD", summary.normalizedToCurrency)
        assertTrue(summary.fxConversionComplete)
    }

    @Test
    fun summarizeNormalized_usesHkMarketCurrencyWhenFillCurrencyIsWrong() {
        val fills = listOf(
            fill(
                execId = "hk-wrong-ccy",
                symbol = "0700",
                currency = "USD",
                realizedPnL = 100.0,
            ),
            fill(
                execId = "us",
                symbol = "AAPL",
                currency = "USD",
                realizedPnL = 10.0,
            ),
        )
        val summary = TradeLedgerFilter.summarizeNormalized(
            fills = fills,
            targetCurrency = "HKD",
            ratesToTarget = mapOf("USD" to 7.8),
        )
        assertEquals(178.0, summary.realizedPnL)
        assertEquals(setOf("HKD", "USD"), summary.sourceCurrencies)
    }

    @Test
    fun summarizeNormalized_marksIncompleteWhenRateMissing() {
        val fills = listOf(
            fill(execId = "eur", symbol = "VOD", currency = "GBP", realizedPnL = 10.0),
        )
        val summary = TradeLedgerFilter.summarizeNormalized(
            fills = fills,
            targetCurrency = "HKD",
            ratesToTarget = emptyMap(),
        )
        assertEquals(null, summary.realizedPnL)
        assertFalse(summary.fxConversionComplete)
    }

    @Test
    fun currenciesNeedingRates_excludesTargetCurrency() {
        val fills = listOf(
            fill(execId = "hkd", symbol = "0700", currency = "HKD"),
            fill(execId = "usd", symbol = "AAPL", currency = "USD"),
        )
        assertEquals(setOf("USD"), TradeLedgerFx.currenciesNeedingRates(fills, "HKD"))
    }

    private fun fill(
        execId: String,
        symbol: String = "TEST",
        currency: String,
        realizedPnL: Double? = null,
        commission: Double? = null,
    ) = BrokerFill(
        execId = execId,
        orderId = 1,
        permId = 1L,
        parentOrderId = 0,
        symbol = symbol,
        side = "BOT",
        quantity = 10,
        price = 100.0,
        time = "2026-07-07",
        currency = currency,
        commission = commission,
        realizedPnL = realizedPnL,
    )
}
