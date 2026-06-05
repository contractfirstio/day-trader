package daytrader.domain

data class StrategyDeployment(
    val id: String,
    val strategyType: StrategyType,
    val status: DeploymentStatus,
    val symbol: String,
    /** RTH session zone (US / London LSE / HK). Null → legacy symbol inference at runtime. */
    val marketZoneId: String? = null,
    /** Quote/settlement currency from IB or user (USD, GBP, HKD). */
    val currencyCode: String = "USD",
    val marketSource: MarketSource = MarketSource.LEGACY_INFERRED,
    /** Company name from IB contract details when resolved at create time. */
    val companyName: String? = null,
    /** IB listing chosen at deploy time; null → [InstrumentIdentity.heuristic] at runtime. */
    val instrument: InstrumentIdentity? = null,
    val maxDollars: Int,
    /** When true, the instance is started automatically at RTH open for the symbol's market. */
    val autoStartOnMarketOpen: Boolean = false,
    /** Session date (ISO) of the last automatic market-open start; prevents duplicate starts same day. */
    val lastAutoStartSessionDate: String? = null,
    val sessionHistory: List<StrategySession> = emptyList(),
    val live: ActiveExecution = ActiveExecution(),
    /** First 15-minute RTH candle + derived bracket levels for the active session (Touch Turn only). */
    val touchTurnSession: TouchTurnSessionContext? = null,
    /**
     * Pre-flight bootstrap + checks for today's session (Touch Turn only). Not a running session;
     * consumed by Start when [TouchTurnSessionPrepare.isValidForStart].
     */
    val touchTurnPrepare: TouchTurnSessionPrepare? = null,
    /** Touch Turn entry-gate thresholds; [TouchTurnRuleConfig.DEFAULT] when unset in legacy records. */
    val touchTurnRules: TouchTurnRuleConfig = TouchTurnRuleConfig.DEFAULT
)
