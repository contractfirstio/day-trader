package daytrader.data.persistence

import daytrader.domain.StrategyType
import kotlinx.serialization.Serializable

@Serializable
data class InstancesDocument(
    val instances: List<InstanceRecord> = emptyList()
)

@Serializable
data class InstanceRecord(
    val id: String,
    val strategy: StrategyType,
    val status: String,
    val configuration: ConfigurationRecord,
    val live: LiveRecord,
    val performance: List<PerformanceDayRecord> = emptyList(),
    val touchTurnSession: TouchTurnSessionRecord? = null
)

@Serializable
data class TouchTurnSessionRecord(
    val sessionDate: String,
    val status: String,
    val candle: OhlcBarRecord? = null,
    val setup: TouchTurnBracketSetupRecord? = null,
    val errorMessage: String? = null,
    val currencyCode: String = "USD",
    val marketZoneId: String = "America/New_York",
    val adr14: Double? = null,
    val rangeThreshold: Double = 0.0,
    val entryOrdersPermitted: Boolean? = null,
    val ordersPlacedForSession: Boolean = false,
    val noPositionBracketCancelOutcome: String? = null
)

@Serializable
data class OhlcBarRecord(
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val time: String? = null
)

@Serializable
data class TouchTurnBracketSetupRecord(
    val range: Double,
    val rangeThreshold: Double,
    val isLiquidityCandle: Boolean,
    val entry: Double,
    val stopLoss: Double,
    val takeProfit: Double,
    val candleColor: String? = null,
    val side: String? = null
)

@Serializable
data class ConfigurationRecord(
    val symbol: String,
    val maxAtRisk: Int,
    val autoStartOnMarketOpen: Boolean = false,
    val lastAutoStartSessionDate: String? = null
)

@Serializable
data class LiveRecord(
    val state: String,
    val side: String,
    val quantity: Int = 0,
    val entry: Double? = null,
    val stop: Double? = null,
    val target: Double? = null,
    val market: Double? = null,
    val orderStatus: String = "—",
    val updatedAt: String = "—"
)

@Serializable
data class PerformanceDayRecord(
    val id: String,
    val date: String,
    val startedAt: String = "",
    val stoppedAt: String = "",
    val pnl: Double,
    val trades: Int,
    val maxAtRisk: Int,
    val status: String,
    val hadLiquidityCandle: Boolean? = null,
    val ordersPlacedForCandle: Boolean? = null,
    val positionOpened: Boolean? = null,
    val sessionTrades: List<SessionTradeRecord> = emptyList()
)

@Serializable
data class SessionTradeRecord(
    val execId: String,
    val orderId: Int,
    val permId: Long,
    val parentOrderId: Int = 0,
    val side: String,
    val quantity: Int,
    val price: Double,
    val time: String,
    val currency: String = "USD",
    val commission: Double? = null,
    val realizedPnL: Double? = null
)

@Serializable
data class StrategiesScreenDocument(
    val selectedInstanceId: String? = null,
    val detailTab: String = "configuration",
    val globalAutoStartEnabled: Boolean = true
)
