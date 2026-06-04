package daytrader.engine.touchturn

import daytrader.broker.SymbolMarkets
import daytrader.domain.DeploymentMarket
import daytrader.gateway.AccountPosition
import daytrader.domain.RthMarketSessions
import daytrader.domain.StrategyDeployment
import daytrader.domain.StrategyType
import daytrader.domain.TouchTurnLogic
import daytrader.domain.TouchTurnPrepareCheck
import daytrader.domain.TouchTurnPrepareCheckId
import daytrader.domain.TouchTurnPrepareOverallStatus
import daytrader.domain.TouchTurnPrepareStatus
import daytrader.domain.TouchTurnSessionPrepare
import daytrader.domain.effectiveTouchTurnRules
import daytrader.domain.TouchTurnSignalContext
import daytrader.gateway.BrokerGateway
import daytrader.gateway.GatewayConnectionState
import daytrader.gateway.LiveQuote
import daytrader.marketdata.MarketDataProvider
import daytrader.gateway.BrokerKind

internal object TouchTurnPrepareRunner {
    suspend fun run(
        deployment: StrategyDeployment,
        sessionDateIso: String,
        marketData: MarketDataProvider,
        quotes: Map<String, LiveQuote>,
        brokerPositions: List<AccountPosition>,
        marketGateway: BrokerGateway?,
        brokerKind: BrokerKind,
        nowEpochMillis: Long
    ): TouchTurnSessionPrepare {
        val zoneId = DeploymentMarket.effectiveZoneId(deployment)
        val currency = DeploymentMarket.effectiveCurrencyCode(deployment)
        val instrument = DeploymentMarket.effectiveInstrument(deployment)
        val checks = mutableListOf<TouchTurnPrepareCheck>()

        checks += ibConnectedCheck(marketGateway, brokerKind)
        checks += flatPositionCheck(deployment, brokerPositions, brokerKind)
        checks += marketListingCheck(deployment, zoneId, currency, instrument)

        marketData.ensureStreaming(deployment.symbol, instrument)
        val signalResult = marketData.fetchTouchTurnSignalContext(
            symbol = deployment.symbol,
            instrument = instrument,
            isClosedBarRefetch = false,
            marketZoneId = zoneId,
            allowMissingTodayOpeningBar = true,
            rules = deployment.effectiveTouchTurnRules()
        )
        checks += historicalBootstrapCheck(signalResult)
        val context = signalResult.getOrNull()
        if (context != null) {
            checks += openingBarTimeCheck(context, zoneId)
            checks += liveBidAskCheck(deployment.symbol, quotes, brokerKind)
        } else {
            checks += liveBidAskCheck(deployment.symbol, quotes, brokerKind)
        }

        val overall = TouchTurnSessionPrepare.overallFromChecks(checks)
        return TouchTurnSessionPrepare(
            sessionDateIso = sessionDateIso,
            preparedAtEpochMillis = nowEpochMillis,
            instrumentKey = instrument.dedupeKey(),
            marketZoneId = zoneId,
            currencyCode = currency,
            signalContext = context ?: TouchTurnSignalContext(
                firstCandle = daytrader.domain.OhlcBar(
                    open = 0.0,
                    high = 0.0,
                    low = 0.0,
                    close = 0.0,
                    time = null,
                    volume = 0.0
                ),
                atr14 = 0.0,
                volumeSma20 = 0.0
            ),
            checks = checks,
            overallStatus = overall.name
        )
    }

    private fun ibConnectedCheck(
        marketGateway: BrokerGateway?,
        brokerKind: BrokerKind
    ): TouchTurnPrepareCheck {
        val connected = when (brokerKind) {
            BrokerKind.EMULATOR -> true
            BrokerKind.REPLAY -> true
            BrokerKind.EMULATOR_LIVE_IB_MARKET_DATA,
            BrokerKind.INTERACTIVE_BROKERS ->
                marketGateway?.connectionState?.value is GatewayConnectionState.Connected
        }
        return check(
            id = TouchTurnPrepareCheckId.IB_CONNECTED,
            status = if (connected) TouchTurnPrepareStatus.PASS else TouchTurnPrepareStatus.FAIL,
            label = "IB / market data connected",
            detail = if (connected) null else "Connect or reconnect before the opening bar"
        )
    }

    private fun flatPositionCheck(
        deployment: StrategyDeployment,
        brokerPositions: List<AccountPosition>,
        brokerKind: BrokerKind
    ): TouchTurnPrepareCheck {
        if (brokerKind == BrokerKind.EMULATOR) {
            return check(
                id = TouchTurnPrepareCheckId.FLAT_POSITION,
                status = TouchTurnPrepareStatus.PASS,
                label = "Flat position",
                detail = "Emulator mode (no IB position gate)"
            )
        }
        val blocking = SymbolMarkets.findOpenPosition(deployment, brokerPositions)
        return check(
            id = TouchTurnPrepareCheckId.FLAT_POSITION,
            status = if (blocking == null) TouchTurnPrepareStatus.PASS else TouchTurnPrepareStatus.FAIL,
            label = "Flat position",
            detail = blocking?.let { "Open ${it.quantity} @ ${it.symbol}" }
        )
    }

    private fun marketListingCheck(
        deployment: StrategyDeployment,
        zoneId: String,
        currency: String,
        instrument: daytrader.domain.InstrumentIdentity
    ): TouchTurnPrepareCheck {
        val session = RthMarketSessions.forZoneId(zoneId)
        val listingOk = when (zoneId) {
            RthMarketSessions.EUR.zoneId ->
                currency == "GBP" && !instrument.primaryExch.isNullOrBlank()
            RthMarketSessions.HK.zoneId -> currency == "HKD"
            else -> currency == "USD"
        }
        return check(
            id = TouchTurnPrepareCheckId.MARKET_LISTING,
            status = if (listingOk) TouchTurnPrepareStatus.PASS else TouchTurnPrepareStatus.WARN,
            label = "Market & listing",
            detail = "${session.label} · $currency · ${instrument.primaryExch ?: instrument.exchange}"
        )
    }

    private fun historicalBootstrapCheck(
        result: Result<TouchTurnSignalContext>
    ): TouchTurnPrepareCheck = result.fold(
        onSuccess = { ctx ->
            val metrics = "ATR14=${formatNum(ctx.atr14)} volumeSma20=${formatNum(ctx.volumeSma20)}"
            if (ctx.todayOpeningBarPending) {
                check(
                    id = TouchTurnPrepareCheckId.HISTORICAL_BOOTSTRAP,
                    status = TouchTurnPrepareStatus.WARN,
                    label = "Historical bootstrap",
                    detail = "$metrics — opening bar loads at RTH open"
                )
            } else {
                check(
                    id = TouchTurnPrepareCheckId.HISTORICAL_BOOTSTRAP,
                    status = TouchTurnPrepareStatus.PASS,
                    label = "Historical bootstrap",
                    detail = metrics
                )
            }
        },
        onFailure = { error ->
            check(
                id = TouchTurnPrepareCheckId.HISTORICAL_BOOTSTRAP,
                status = TouchTurnPrepareStatus.FAIL,
                label = "Historical bootstrap",
                detail = error.message ?: "Failed to load 15m history"
            )
        }
    )

    private fun openingBarTimeCheck(
        context: TouchTurnSignalContext,
        zoneId: String
    ): TouchTurnPrepareCheck {
        if (context.todayOpeningBarPending) {
            val session = RthMarketSessions.forZoneId(zoneId)
            val expectedOpen = "%02d:%02d".format(session.openHour, session.openMinute)
            return check(
                id = TouchTurnPrepareCheckId.OPENING_BAR_TIME,
                status = TouchTurnPrepareStatus.WARN,
                label = "Opening bar time",
                detail = "Not available until RTH open ($expectedOpen ${session.label})"
            )
        }
        val barTime = context.firstCandle.time
        val session = RthMarketSessions.forZoneId(zoneId)
        val expectedOpen = "%02d:%02d".format(session.openHour, session.openMinute)
        val normalized = barTime?.let { TouchTurnLogic.normalizeIbBarTimeToMarketZone(it, zoneId) }
        val matchesScheduled = normalized?.contains(expectedOpen) == true
        return check(
            id = TouchTurnPrepareCheckId.OPENING_BAR_TIME,
            status = when {
                barTime.isNullOrBlank() -> TouchTurnPrepareStatus.FAIL
                matchesScheduled -> TouchTurnPrepareStatus.PASS
                else -> TouchTurnPrepareStatus.WARN
            },
            label = "Opening bar time",
            detail = normalized ?: barTime ?: "missing"
        )
    }

    private fun liveBidAskCheck(
        symbol: String,
        quotes: Map<String, LiveQuote>,
        brokerKind: BrokerKind
    ): TouchTurnPrepareCheck {
        if (brokerKind == BrokerKind.EMULATOR) {
            return check(
                id = TouchTurnPrepareCheckId.LIVE_BID_ASK,
                status = TouchTurnPrepareStatus.WARN,
                label = "Live bid / ask",
                detail = "Not required in emulator-only mode"
            )
        }
        val norm = SymbolMarkets.normalizeSymbol(symbol)
        val quote = quotes[norm]
        val hasBidAsk = quote?.bid != null && quote.ask != null
        return check(
            id = TouchTurnPrepareCheckId.LIVE_BID_ASK,
            status = if (hasBidAsk) TouchTurnPrepareStatus.PASS else TouchTurnPrepareStatus.WARN,
            label = "Live bid / ask",
            detail = when {
                hasBidAsk -> "bid=${quote?.bid} ask=${quote?.ask}"
                quote?.last != null -> "Last only — confirm before bar close"
                else -> "No streaming quote yet"
            }
        )
    }

    private fun check(
        id: TouchTurnPrepareCheckId,
        status: TouchTurnPrepareStatus,
        label: String,
        detail: String?
    ): TouchTurnPrepareCheck = TouchTurnPrepareCheck(
        id = id.name,
        status = status.name,
        label = label,
        detail = detail
    )

    private fun formatNum(value: Double): String =
        if (value >= 1_000) "%.0f".format(value) else "%.2f".format(value)
}
