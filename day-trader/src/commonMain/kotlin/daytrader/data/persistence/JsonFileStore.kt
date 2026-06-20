package daytrader.data.persistence

import daytrader.domain.SessionTrade
import daytrader.platform.AppFileSystem
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
        readDeploymentsResult().value

    internal fun readDeploymentsResult(): JsonDocumentReader.Result<DeploymentsDocument> =
        readWithBackup<DeploymentsDocument>(AppDataFiles.DEPLOYMENTS, AppDataFiles.DEPLOYMENTS_BACKUP)

    fun writeDeployments(document: DeploymentsDocument) {
        backupIfPresent(AppDataFiles.DEPLOYMENTS, AppDataFiles.DEPLOYMENTS_BACKUP)
        write(AppDataFiles.DEPLOYMENTS, document)
    }

    internal fun readLegacyInstancesJson(): LegacyInstancesJsonDocument? =
        read<LegacyInstancesJsonDocument>(AppDataFiles.LEGACY_INSTANCES_JSON)

    fun readStrategiesScreen(): StrategiesScreenDocument? =
        readWithBackup<StrategiesScreenDocument>(
            AppDataFiles.STRATEGIES_SCREEN,
            AppDataFiles.STRATEGIES_SCREEN_BACKUP
        ).value

    fun writeStrategiesScreen(document: StrategiesScreenDocument) {
        backupIfPresent(AppDataFiles.STRATEGIES_SCREEN, AppDataFiles.STRATEGIES_SCREEN_BACKUP)
        write(AppDataFiles.STRATEGIES_SCREEN, document)
    }

    fun readReplaySettings(): ReplaySettingsDocument? =
        read<ReplaySettingsDocument>(AppDataFiles.REPLAY_SETTINGS)

    fun writeReplaySettings(document: ReplaySettingsDocument) {
        write(AppDataFiles.REPLAY_SETTINGS, document)
    }

    fun readWatchlists(): WatchlistsDocument? =
        readWithBackup<WatchlistsDocument>(AppDataFiles.WATCHLISTS, AppDataFiles.WATCHLISTS_BACKUP).value

    fun writeWatchlists(document: WatchlistsDocument) {
        backupIfPresent(AppDataFiles.WATCHLISTS, AppDataFiles.WATCHLISTS_BACKUP)
        write(AppDataFiles.WATCHLISTS, document)
    }

    fun readLiquidityBuckets(): LiquidityBucketsDocument? =
        readWithBackup<LiquidityBucketsDocument>(
            AppDataFiles.LIQUIDITY_BUCKETS,
            AppDataFiles.LIQUIDITY_BUCKETS_BACKUP
        ).value

    fun writeLiquidityBuckets(document: LiquidityBucketsDocument) {
        backupIfPresent(AppDataFiles.LIQUIDITY_BUCKETS, AppDataFiles.LIQUIDITY_BUCKETS_BACKUP)
        write(AppDataFiles.LIQUIDITY_BUCKETS, document)
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

    fun appendReversalScoreLine(relativePath: String, line: String) {
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

    internal inline fun <reified T> readWithBackup(
        fileName: String,
        backupFileName: String,
        readText: (String) -> String? = AppFileSystem::readText,
    ): JsonDocumentReader.Result<T> =
        JsonDocumentReader.read(
            json = json,
            fileName = fileName,
            backupFileName = backupFileName,
            readText = readText,
        )

    private inline fun <reified T> read(fileName: String): T? {
        val raw = AppFileSystem.readText(fileName) ?: return null
        return JsonDocumentReader.decode(json, raw)
    }

    private fun backupIfPresent(fileName: String, backupFileName: String) {
        val current = AppFileSystem.readText(fileName) ?: return
        AppFileSystem.writeTextAtomic(backupFileName, current)
    }

    private inline fun <reified T> write(fileName: String, document: T) {
        val encoded = json.encodeToString(serializer<T>(), document)
        AppFileSystem.writeTextAtomic(fileName, encoded)
    }
}
