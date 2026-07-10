package daytrader.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking

class StubCurrencyRateProviderTest {
    @Test
    fun ratesToTarget_returnsConfiguredUsdToHkdRate() = runBlocking {
        val provider = StubCurrencyRateProvider(mapOf("USD" to 7.8))
        val rates = provider.ratesToTarget(setOf("USD"), "HKD").getOrThrow()
        assertEquals(7.8, rates.getValue("USD"))
    }
}
