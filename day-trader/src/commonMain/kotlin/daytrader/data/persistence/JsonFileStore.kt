package daytrader.data.persistence

import daytrader.platform.AppFileSystem
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

object JsonFileStore {
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }

    fun readStrategyInstances(): StrategyInstancesDocument? =
        read(AppDataFiles.STRATEGY_INSTANCES, StrategyInstancesDocument.serializer())

    fun writeStrategyInstances(document: StrategyInstancesDocument) {
        write(AppDataFiles.STRATEGY_INSTANCES, document, StrategyInstancesDocument.serializer())
    }

    fun readStrategiesAppState(): StrategiesAppStateDocument? =
        read(AppDataFiles.STRATEGIES_APP_STATE, StrategiesAppStateDocument.serializer())

    fun writeStrategiesAppState(document: StrategiesAppStateDocument) {
        write(AppDataFiles.STRATEGIES_APP_STATE, document, StrategiesAppStateDocument.serializer())
    }

    private fun <T> read(fileName: String, deserializer: kotlinx.serialization.DeserializationStrategy<T>): T? {
        val raw = AppFileSystem.readText(fileName) ?: return null
        return try {
            json.decodeFromString(deserializer, raw)
        } catch (_: SerializationException) {
            null
        }
    }

    private fun <T> write(
        fileName: String,
        document: T,
        serializer: kotlinx.serialization.SerializationStrategy<T>
    ) {
        val encoded = json.encodeToString(serializer, document)
        AppFileSystem.writeTextAtomic(fileName, encoded)
    }
}
