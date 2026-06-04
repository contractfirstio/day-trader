package daytrader.data.persistence

import daytrader.domain.StrategyType
import kotlinx.serialization.Serializable

@Serializable
data class DeploymentsDocument(
    val deployments: List<DeploymentRecord> = emptyList()
)

@Serializable
data class DeploymentRecord(
    val id: String,
    val strategy: StrategyType,
    val status: String,
    val configuration: ConfigurationRecord,
    val live: LiveRecord,
    val sessionHistory: List<SessionHistoryRecord> = emptyList(),
    val touchTurnSession: TouchTurnSessionRecord? = null,
    val touchTurnPrepare: TouchTurnSessionPrepareRecord? = null
)

@Serializable
data class TouchTurnSessionPrepareRecord(
    val sessionDateIso: String,
    val preparedAtEpochMillis: Long,
    val instrumentKey: String,
    val marketZoneId: String,
    val currencyCode: String,
    val atr14: Double,
    val volumeSma20: Double,
    val todayOpeningBarPending: Boolean = false,
    val firstCandle: OhlcBarRecord? = null,
    val checks: List<TouchTurnPrepareCheckRecord> = emptyList(),
    val overallStatus: String = "FAIL"
)

@Serializable
data class TouchTurnPrepareCheckRecord(
    val id: String,
    val status: String,
    val label: String,
    val detail: String? = null
)

@Serializable
data class TouchTurnSessionRecord(
    val sessionDate: String,
    val status: String,
    val openingBarTime: String? = null,
    val candle: OhlcBarRecord? = null,
    val setup: TouchTurnBracketSetupRecord? = null,
    val errorMessage: String? = null,
    val currencyCode: String = "USD",
    val marketZoneId: String = "America/New_York",
    val adr14: Double? = null,
    val rangeThreshold: Double = 0.0,
    val entryOrdersPermitted: Boolean? = null,
    val ordersPlacedForSession: Boolean = false,
    val noPositionBracketCancelOutcome: String? = null,
    val milestones: TouchTurnMilestoneTimestampsRecord? = null,
    val decisionOutcome: String? = null,
    val plannedQuantity: Int? = null,
    val plannedBracket: TouchTurnPlannedBracketRecord? = null
)

@Serializable
data class TouchTurnMilestoneTimestampsRecord(
    val startingSessionAt: String? = null,
    val dataReadyAt: String? = null,
    val dataFailedAt: String? = null,
    val barClosedAt: String? = null,
    val liquidityEvaluatedAt: String? = null,
    val closeConfirmedAt: String? = null,
    val ordersPlacedAt: String? = null,
    val positionOpenedAt: String? = null,
    val closingSessionAt: String? = null
)

@Serializable
data class OhlcBarRecord(
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val time: String? = null,
    val volume: Double = 0.0
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
data class TouchTurnRuleConfigRecord(
    val atrLiquidityRatio: Double = 0.25,
    val volumeExhaustionRatio: Double = 1.5,
    val atrLookbackPeriods: Int = 14,
    val volumeSmaPeriods: Int = 20,
    val closeConfirmationMinDistanceRatioOfRange: Double = 0.15,
    val closePositionShortMax: Double = 0.35,
    val closePositionLongMin: Double = 0.65,
    val barLiveDivergenceMaxRatioOfRange: Double = 0.25,
    val entryTouchBufferRatioOfRange: Double = 0.05,
    val minStopDistance: Double = 0.05,
    val takeProfitFibRatioGreen: Double = 0.382,
    val takeProfitFibRatioRed: Double = 0.382,
    val closeConfirmationAfterCloseMs: Long = 60_000L,
    val closedBarRefetchSettleMs: Long = 3_000L,
    val volumeBufferObservationMs: Long = 60_000L
)

@Serializable
data class ConfigurationRecord(
    val symbol: String,
    val maxAtRisk: Int,
    val autoStartOnMarketOpen: Boolean = false,
    val lastAutoStartSessionDate: String? = null,
    val marketZoneId: String? = null,
    val currencyCode: String = "USD",
    val marketSource: String? = null,
    val companyName: String? = null,
    val instrument: InstrumentIdentityRecord? = null,
    val touchTurnRules: TouchTurnRuleConfigRecord? = null
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
data class SessionHistoryRecord(
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
    val sessionTrades: List<SessionTradeRecord> = emptyList(),
    val touchTurnMilestones: TouchTurnMilestoneTimestampsRecord? = null,
    val touchTurnStartedBy: String? = null,
    val touchTurnRunRecord: TouchTurnRunRecordRecord? = null
)

@Serializable
data class TouchTurnRunRecordRecord(
    val runContext: TouchTurnRunContextRecord,
    val marketInputs: TouchTurnRunMarketInputsRecord,
    val decision: TouchTurnSessionDecisionRecord,
    val stopEvent: TouchTurnStopEventRecord,
    val milestones: TouchTurnMilestoneTimestampsRecord
)

@Serializable
data class TouchTurnRunContextRecord(
    val maxDollars: Int,
    val startedBy: String,
    val brokerId: String,
    val brokerKind: String? = null
)

@Serializable
data class TouchTurnRunMarketInputsRecord(
    val openingBar: OhlcBarRecord? = null,
    val adr14: Double? = null,
    val atr14: Double? = null,
    val volumeSma20: Double? = null,
    val volumeCheck: TouchTurnVolumeCheckRecord? = null,
    val currencyCode: String = "USD",
    val marketZoneId: String = "America/New_York",
    val dataErrorMessage: String? = null
)

@Serializable
data class TouchTurnVolumeCheckRecord(
    val phase: String,
    val openingBarVolume: Double,
    val volumeSma20: Double,
    val exhaustionThreshold: Double,
    val volumeExhausted: Boolean,
    val volumeRatio: Double? = null,
    val exhaustionRatio: Double = 1.5,
    val barTime: String? = null
)

@Serializable
data class TouchTurnSessionDecisionRecord(
    val outcome: String,
    val plannedQuantity: Int? = null,
    val plannedBracket: TouchTurnPlannedBracketRecord? = null,
    val executedLegs: List<String> = emptyList()
)

@Serializable
data class TouchTurnPlannedBracketRecord(
    val side: String,
    val entry: Double,
    val stopLoss: Double,
    val takeProfit: Double
)

@Serializable
data class TouchTurnStopEventRecord(
    val stopTrigger: String,
    val stopErrorMessage: String? = null,
    val brokerUnrealizedPnLAtStop: Double? = null
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
    val selectedDeploymentId: String? = null,
    /** Legacy key from pre-refactor `strategies-screen.json`; cleared on next save. */
    val selectedInstanceId: String? = null,
    val detailTab: String = "configuration",
    val globalAutoStartEnabled: Boolean = true,
    val tradingPanelDismissedRecapSessionId: Map<String, String> = emptyMap(),
)
