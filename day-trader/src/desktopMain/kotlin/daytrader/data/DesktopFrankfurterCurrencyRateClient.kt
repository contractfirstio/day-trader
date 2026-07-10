package daytrader.data

import daytrader.diagnostics.TimestampedConsoleLog
import daytrader.domain.CurrencyCodes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder

/**
 * Fetches spot FX rates from the free Frankfurter API (ECB reference rates).
 * Falls back to [StubCurrencyRateProvider] when the network request fails.
 */
class DesktopFrankfurterCurrencyRateClient(
    private val fallback: CurrencyRateProvider = StubCurrencyRateProvider(),
    private val baseUrl: String = DEFAULT_BASE_URL,
) : CurrencyRateProvider {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun ratesToTarget(fromCurrencies: Set<String>, toCurrency: String): Result<Map<String, Double>> {
        val target = CurrencyCodes.displayCurrency(toCurrency)
        val normalizedFrom = fromCurrencies.map { CurrencyCodes.displayCurrency(it) }.toSet()
        if (normalizedFrom.isEmpty()) return Result.success(emptyMap())
        return withContext(Dispatchers.IO) {
            runCatching {
                normalizedFrom.associateWith { from ->
                    if (from == target) {
                        1.0
                    } else {
                        fetchRate(from, target)
                    }
                }
            }.onFailure { error ->
                TimestampedConsoleLog.line("TradeFx", "Frankfurter fetch failed: ${error.message}")
            }.recoverCatching {
                TimestampedConsoleLog.line("TradeFx", "Using stub FX rates after Frankfurter failure")
                fallback.ratesToTarget(normalizedFrom, target).getOrThrow()
            }
        }
    }

    private fun fetchRate(fromCurrency: String, toCurrency: String): Double {
        val query = buildString {
            append(baseUrl)
            append("latest?from=")
            append(URLEncoder.encode(fromCurrency, Charsets.UTF_8.name()))
            append("&to=")
            append(URLEncoder.encode(toCurrency, Charsets.UTF_8.name()))
        }
        val connection = URI(query).toURL().openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = 10_000
        connection.readTimeout = 10_000
        val status = connection.responseCode
        val body = if (status in 200..299) {
            connection.inputStream.bufferedReader().use { it.readText() }
        } else {
            connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
        }
        if (status !in 200..299) {
            error("Frankfurter request failed ($status) for $fromCurrency→$toCurrency: $body")
        }
        val response = json.decodeFromString(FrankfurterLatestResponse.serializer(), body)
        return response.rates[toCurrency]
            ?: error("Frankfurter response missing $toCurrency rate for base ${response.base}")
    }

    @Serializable
    private data class FrankfurterLatestResponse(
        val amount: Double = 1.0,
        val base: String = "",
        val date: String = "",
        val rates: Map<String, Double> = emptyMap(),
    )

    companion object {
        const val DEFAULT_BASE_URL = "https://api.frankfurter.app/"
    }
}
