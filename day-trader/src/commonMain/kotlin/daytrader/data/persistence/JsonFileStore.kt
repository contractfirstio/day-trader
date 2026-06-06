package daytrader.data.persistence

import daytrader.domain.SessionTrade
import daytrader.platform.AppFileSystem
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.serializer

object JsonFileStore {
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = false
    }

    fun readDeployments(): DeploymentsDocument? =
        read<DeploymentsDocument>(AppDataFiles.DEPLOYMENTS)

    fun writeDeployments(document: DeploymentsDocument) {
        backupDeploymentsIfPresent()
        write(AppDataFiles.DEPLOYMENTS, document)
    }

    private fun backupDeploymentsIfPresent() {
        val current = AppFileSystem.readText(AppDataFiles.DEPLOYMENTS) ?: return
        AppFileSystem.writeTextAtomic(AppDataFiles.DEPLOYMENTS_BACKUP, current)
    }

    internal fun readLegacyInstancesJson(): LegacyInstancesJsonDocument? =
        read<LegacyInstancesJsonDocument>(AppDataFiles.LEGACY_INSTANCES_JSON)

    fun readStrategiesScreen(): StrategiesScreenDocument? =
        read<StrategiesScreenDocument>(AppDataFiles.STRATEGIES_SCREEN)

    fun writeStrategiesScreen(document: StrategiesScreenDocument) {
        write(AppDataFiles.STRATEGIES_SCREEN, document)
    }

    fun readWatchlists(): WatchlistsDocument? =
        read<WatchlistsDocument>(AppDataFiles.WATCHLISTS)

    fun writeWatchlists(document: WatchlistsDocument) {
        write(AppDataFiles.WATCHLISTS, document)
    }

    fun appendSessionTraceLine(relativePath: String, line: String) {
        AppFileSystem.appendLine(relativePath, "$line\n")
    }

    fun appendSessionPriceLine(relativePath: String, line: String) {
        AppFileSystem.appendLine(relativePath, "$line\n")
    }

    fun appendSessionHistoricalLine(relativePath: String, line: String) {
        AppFileSystem.appendLine(relativePath, "$line\n")
    }

    fun writeSessionFile(relativePath: String, content: String) {
        AppFileSystem.writeTextAtomic(relativePath, content)
    }

    fun appendIbPriceTickLine(relativePath: String, line: String) {
        AppFileSystem.appendLine(relativePath, "$line\n")
    }

    fun appendEmulatorEngineLine(relativePath: String, line: String) {
        AppFileSystem.appendLine(relativePath, "$line\n")
    }

    fun appendEmulatorPriceLine(relativePath: String, line: String) {
        AppFileSystem.appendLine(relativePath, "$line\n")
    }

    fun appendExecutionGatewayLine(relativePath: String, line: String) {
        AppFileSystem.appendLine(relativePath, "$line\n")
    }

    fun encodeSessionTradesForTrace(trades: List<SessionTrade>): JsonElement =
        json.encodeToJsonElement(
            trades.map { trade ->
                SessionTradeRecord(
                    execId = trade.execId,
                    orderId = trade.orderId,
                    permId = trade.permId,
                    parentOrderId = trade.parentOrderId,
                    side = trade.side,
                    quantity = trade.quantity,
                    price = trade.price,
                    time = trade.time,
                    currency = trade.currency,
                    commission = trade.commission,
                    realizedPnL = trade.realizedPnL
                )
            }
        )

    internal fun readLegacyStrategyInstances(): LegacyDeploymentsDocument? =
        read<LegacyDeploymentsDocument>(AppDataFiles.LEGACY_STRATEGY_INSTANCES)

    internal fun readLegacyStrategiesScreen(): LegacyStrategiesScreenDocument? =
        read<LegacyStrategiesScreenDocument>(AppDataFiles.LEGACY_STRATEGIES_APP_STATE)

    private inline fun <reified T> read(fileName: String): T? {
        val raw = AppFileSystem.readText(fileName) ?: return null
        return try {
            json.decodeFromString(serializer<T>(), raw)
        } catch (_: SerializationException) {
            null
        }
    }

    private inline fun <reified T> write(fileName: String, document: T) {
        val encoded = json.encodeToString(serializer<T>(), document)
        AppFileSystem.writeTextAtomic(fileName, encoded)
    }
}
