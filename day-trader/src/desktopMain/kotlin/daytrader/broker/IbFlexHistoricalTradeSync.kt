package daytrader.broker

import daytrader.data.HistoricalTradeSync
import daytrader.gateway.BrokerFill
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.w3c.dom.Element
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Pulls settled account trades from an Activity Flex Query (Trades section).
 * Configure token + query ID in IB Gateway settings (Client Portal → Flex Queries).
 */
class IbFlexHistoricalTradeSync(
    private val config: IbGatewayConfig,
) : HistoricalTradeSync {

    override suspend fun fetchTrades(): Result<List<BrokerFill>> = withContext(Dispatchers.IO) {
        runCatching {
            val token = config.flexToken.trim()
            val queryId = config.flexTradesQueryId.trim()
            require(token.isNotBlank()) { "Flex token is not configured." }
            require(queryId.isNotBlank()) { "Flex trades query ID is not configured." }
            IbFlexSyncLog.info("SendRequest queryId=$queryId")
            val referenceCode = requestReferenceCode(token, queryId)
            IbFlexSyncLog.info("GetStatement referenceCode=$referenceCode")
            val xml = pollStatementXml(token, referenceCode)
            val trades = IbFlexTradeParser.parseTrades(xml)
            IbFlexSyncLog.info("Parsed ${trades.size} trade(s) from Flex statement")
            trades
        }.onFailure { error ->
            IbFlexSyncLog.error(error.message ?: error.toString())
        }
    }

    private suspend fun requestReferenceCode(token: String, queryId: String): String {
        repeat(MAX_SEND_ATTEMPTS) { attempt ->
            if (attempt > 0) delay(RATE_LIMIT_RETRY_MS)
            val xml = httpGet(
                "$FLEX_BASE/SendRequest",
                mapOf("t" to token, "q" to queryId, "v" to FLEX_VERSION)
            )
            val document = parseXml(xml)
            val status = document.getElementsByTagName("Status").item(0)?.textContent
            if (status == "Success") {
                return document.getElementsByTagName("ReferenceCode").item(0)?.textContent?.trim()
                    ?: error("Flex SendRequest succeeded but returned no reference code.")
            }
            val code = document.getElementsByTagName("ErrorCode").item(0)?.textContent
            if (code == RATE_LIMITED && attempt < MAX_SEND_ATTEMPTS - 1) {
                IbFlexSyncLog.info("SendRequest rate-limited (1018); retrying in ${RATE_LIMIT_RETRY_MS}ms")
                return@repeat
            }
            val message = document.getElementsByTagName("ErrorMessage").item(0)?.textContent
            error("Flex SendRequest failed (${code ?: "unknown"}): ${message ?: xml.take(200)}")
        }
        error("Flex SendRequest failed after rate-limit retries.")
    }

    private suspend fun pollStatementXml(token: String, referenceCode: String): String {
        repeat(MAX_POLL_ATTEMPTS) { attempt ->
            if (attempt > 0) delay(POLL_DELAY_MS)
            val xml = httpGet(
                "$FLEX_BASE/GetStatement",
                mapOf("t" to token, "q" to referenceCode, "v" to FLEX_VERSION)
            )
            val document = parseXml(xml)
            when (val outcome = IbFlexGetStatementHandler.classify(document, xml)) {
                is IbFlexGetStatementHandler.Outcome.Ready -> return outcome.xml
                IbFlexGetStatementHandler.Outcome.InProgress -> return@repeat
                is IbFlexGetStatementHandler.Outcome.Failed ->
                    error(
                        "Flex GetStatement failed (${outcome.code ?: "unknown"}): " +
                            "${outcome.message ?: outcome.raw}"
                    )
            }
        }
        error("Flex statement generation timed out after ${MAX_POLL_ATTEMPTS * POLL_DELAY_MS / 1000}s.")
    }

    private fun httpGet(baseUrl: String, params: Map<String, String>): String {
        val query = params.entries.joinToString("&") { (key, value) ->
            "${URLEncoder.encode(key, Charsets.UTF_8.name())}=${URLEncoder.encode(value, Charsets.UTF_8.name())}"
        }
        val connection = URI("$baseUrl?$query").toURL().openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.setRequestProperty("User-Agent", USER_AGENT)
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        val status = connection.responseCode
        val body = if (status in 200..299) {
            connection.inputStream.bufferedReader().use { it.readText() }
        } else {
            connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
        }
        if (status !in 200..299) {
            error("Flex HTTP $status: ${body.take(300)}")
        }
        return body
    }

    private fun parseXml(xml: String): Element =
        DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(xml.byteInputStream())
            .documentElement

    companion object {
        private const val FLEX_BASE = "https://ndcdyn.interactivebrokers.com/AccountManagement/FlexWebService"
        private const val FLEX_VERSION = "3"
        private const val USER_AGENT = "DayTrader/1.0"
        private const val CONNECT_TIMEOUT_MS = 20_000
        private const val READ_TIMEOUT_MS = 60_000
        private const val POLL_DELAY_MS = 2_000L
        private const val RATE_LIMIT_RETRY_MS = 5_000L
        private const val MAX_POLL_ATTEMPTS = 15
        private const val MAX_SEND_ATTEMPTS = 3
        private const val RATE_LIMITED = "1018"
    }
}
