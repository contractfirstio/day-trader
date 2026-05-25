package daytrader.data.persistence

import daytrader.platform.AppFileSystem
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
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
        write(AppDataFiles.DEPLOYMENTS, document)
    }

    internal fun readLegacyInstancesJson(): LegacyInstancesJsonDocument? =
        read<LegacyInstancesJsonDocument>(AppDataFiles.LEGACY_INSTANCES_JSON)

    fun readStrategiesScreen(): StrategiesScreenDocument? =
        read<StrategiesScreenDocument>(AppDataFiles.STRATEGIES_SCREEN)

    fun writeStrategiesScreen(document: StrategiesScreenDocument) {
        write(AppDataFiles.STRATEGIES_SCREEN, document)
    }

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
