package daytrader.broker

import daytrader.data.persistence.AppDataFiles
import daytrader.platform.AppFileSystem
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

@Serializable
internal data class IbGatewaySettingsRecord(
    val host: String = "127.0.0.1",
    val port: Int = 4001,
    val clientId: Int = 1,
    val accountCode: String = ""
)

internal object IbGatewaySettingsStore {
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = false
    }

    fun load(): IbGatewayConfig {
        val persisted = readPersisted()
        return IbGatewayConfig(
            host = envOrPersisted("DAY_TRADER_IB_HOST", persisted?.host) ?: "127.0.0.1",
            port = envOrPersistedInt("DAY_TRADER_IB_PORT", persisted?.port) ?: 4001,
            clientId = envOrPersistedInt("DAY_TRADER_IB_CLIENT_ID", persisted?.clientId) ?: 1,
            accountCode = System.getenv("DAY_TRADER_IB_ACCOUNT") ?: persisted?.accountCode.orEmpty()
        )
    }

    fun save(config: IbGatewayConfig) {
        val record = IbGatewaySettingsRecord(
            host = config.host.trim(),
            port = config.port,
            clientId = config.clientId,
            accountCode = config.accountCode.trim()
        )
        AppFileSystem.writeApplicationRootTextAtomic(
            AppDataFiles.IB_GATEWAY_SETTINGS,
            json.encodeToString(IbGatewaySettingsRecord.serializer(), record)
        )
    }

    private fun readPersisted(): IbGatewaySettingsRecord? {
        val raw = AppFileSystem.readApplicationRootText(AppDataFiles.IB_GATEWAY_SETTINGS) ?: return null
        return try {
            json.decodeFromString(IbGatewaySettingsRecord.serializer(), raw)
        } catch (_: SerializationException) {
            null
        }
    }

    private fun envOrPersisted(name: String, persisted: String?): String? =
        System.getenv(name) ?: persisted

    private fun envOrPersistedInt(name: String, persisted: Int?): Int? =
        System.getenv(name)?.toIntOrNull() ?: persisted
}
