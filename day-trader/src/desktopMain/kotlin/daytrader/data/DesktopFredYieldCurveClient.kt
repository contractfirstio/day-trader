package daytrader.data

import daytrader.domain.ReversalScoreYieldCurveSnapshot
import daytrader.diagnostics.ReversalScoreLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder

/**
 * Fetches 10Y and 2Y Treasury yields from the free FRED API.
 *
 * Set `DAY_TRADER_FRED_API_KEY` to your FRED API key. Without a key, [fetchYieldCurveSnapshot]
 * falls back to [StubMacroYieldDataProvider].
 */
class DesktopFredYieldCurveClient(
    private val apiKey: String? = System.getenv("DAY_TRADER_FRED_API_KEY")?.trim()?.takeIf { it.isNotBlank() },
    private val fallback: MacroYieldDataProvider = StubMacroYieldDataProvider(),
    private val observationLimit: Int = 252
) : MacroYieldDataProvider {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun fetchYieldCurveSnapshot(): Result<ReversalScoreYieldCurveSnapshot> {
        val key = apiKey
        ReversalScoreLog.fredFetchStarted(hasApiKey = !key.isNullOrBlank())
        if (key.isNullOrBlank()) {
            ReversalScoreLog.fredUsingStub("DAY_TRADER_FRED_API_KEY not set")
            return fallback.fetchYieldCurveSnapshot()
        }
        return withContext(Dispatchers.IO) {
            runCatching {
                val tenYear = fetchSeries(SERIES_10Y, key)
                val twoYear = fetchSeries(SERIES_2Y, key)
                val aligned = alignSeries(tenYear, twoYear)
                if (aligned.isEmpty()) {
                    error("No overlapping Treasury yield observations from FRED (DGS10=${tenYear.size} DGS2=${twoYear.size})")
                }
                val spreads = aligned.map { (ten, two) -> ten - two }
                val latest = aligned.last()
                ReversalScoreYieldCurveSnapshot(
                    tenYearYield = latest.first,
                    twoYearYield = latest.second,
                    spread = latest.first - latest.second,
                    spreadHistory = spreads
                )
            }.onFailure { error ->
                ReversalScoreLog.fredFetchFailed(error)
            }
        }
    }

    private fun fetchSeries(seriesId: String, apiKey: String): List<Pair<String, Double>> {
        val query = buildString {
            append("https://api.stlouisfed.org/fred/series/observations?")
            append("series_id=${URLEncoder.encode(seriesId, Charsets.UTF_8.name())}")
            append("&api_key=${URLEncoder.encode(apiKey, Charsets.UTF_8.name())}")
            append("&file_type=json")
            append("&sort_order=asc")
            append("&limit=$observationLimit")
        }
        val connection = URI(query).toURL().openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = 15_000
        connection.readTimeout = 15_000
        val status = connection.responseCode
        val body = if (status in 200..299) {
            connection.inputStream.bufferedReader().use { it.readText() }
        } else {
            connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
        }
        if (status !in 200..299) {
            val error = IllegalStateException("FRED request failed ($status) for $seriesId: $body")
            ReversalScoreLog.fredFetchFailed(error, httpStatus = status, responseBodyPreview = body)
            throw error
        }
        val response = json.decodeFromString(FredObservationsResponse.serializer(), body)
        val observations = response.observations.mapNotNull { observation ->
            val value = observation.value?.toDoubleOrNull() ?: return@mapNotNull null
            observation.date to value
        }
        val last = observations.lastOrNull()
        ReversalScoreLog.fredSeriesFetched(
            seriesId = seriesId,
            observationCount = observations.size,
            lastDate = last?.first,
            lastValue = last?.second
        )
        return observations
    }

    private fun alignSeries(
        tenYear: List<Pair<String, Double>>,
        twoYear: List<Pair<String, Double>>
    ): List<Pair<Double, Double>> {
        val twoByDate = twoYear.toMap()
        return tenYear.mapNotNull { (date, ten) ->
            val two = twoByDate[date] ?: return@mapNotNull null
            ten to two
        }
    }

    @Serializable
    private data class FredObservationsResponse(
        val observations: List<FredObservation> = emptyList()
    )

    @Serializable
    private data class FredObservation(
        val date: String,
        @SerialName("value") val value: String? = null
    )

    private companion object {
        const val SERIES_10Y = "DGS10"
        const val SERIES_2Y = "DGS2"
    }
}
