package daytrader.data.persistence

import daytrader.domain.DeploymentStatus
import daytrader.domain.InstrumentIdentity
import daytrader.domain.MarketSource
import daytrader.domain.StrategyType
import daytrader.domain.TouchTurnRuleConfig
import daytrader.domain.TouchTurnRuleEnables
import daytrader.domain.withNewConfigurableRulesDisabled
import daytrader.domain.defaultStrategyDeployment
import kotlin.test.Test
import kotlin.test.assertEquals

class DeploymentPersistenceTest {
    @Test
    fun configurationRoundTrip_persistsMarketAndCompanyFields() {
        val original = defaultStrategyDeployment(
            strategyType = StrategyType.TOUCH_AND_TURN_SCALPER,
            symbol = "VOD",
            maxDollars = 500,
            marketZoneId = "Europe/London",
            currencyCode = "GBP",
            marketSource = MarketSource.IB,
            companyName = "Vodafone Group PLC"
        )

        val record = DeploymentPersistence.toRecord(original)
        val restored = DeploymentPersistence.toDomain(record)

        assertEquals("Europe/London", restored.marketZoneId)
        assertEquals("GBP", restored.currencyCode)
        assertEquals(MarketSource.IB, restored.marketSource)
        assertEquals("Vodafone Group PLC", restored.companyName)
        assertEquals("VOD", restored.symbol)
        assertEquals(500, restored.maxDollars)

        assertEquals("Europe/London", record.configuration.marketZoneId)
        assertEquals("GBP", record.configuration.currencyCode)
        assertEquals("ib", record.configuration.marketSource)
        assertEquals("Vodafone Group PLC", record.configuration.companyName)
    }

    @Test
    fun configurationRoundTrip_persistsAutoStartFields() {
        val original = defaultStrategyDeployment(
            strategyType = StrategyType.QUICK_FLIP_SCALPER,
            symbol = "SPY",
            maxDollars = 250
        ).copy(
            autoStartOnMarketOpen = true,
            lastAutoStartSessionDate = "2026-05-22",
            status = DeploymentStatus.STOPPED
        )

        val restored = DeploymentPersistence.toDomain(DeploymentPersistence.toRecord(original))

        assertEquals(true, restored.autoStartOnMarketOpen)
        assertEquals("2026-05-22", restored.lastAutoStartSessionDate)
    }

    @Test
    fun configurationRoundTrip_persistsInstrumentIdentity() {
        val identity = InstrumentIdentity(
            symbol = "VOD",
            exchange = "SMART",
            primaryExch = "LSE",
            currency = "GBP",
            conId = 12345L
        )
        val original = defaultStrategyDeployment(
            strategyType = StrategyType.TOUCH_AND_TURN_SCALPER,
            symbol = "VOD",
            maxDollars = 500,
            marketZoneId = "Europe/London",
            currencyCode = "GBP",
            marketSource = MarketSource.IB,
            companyName = "Vodafone Group PLC",
            instrument = identity
        )

        val restored = DeploymentPersistence.toDomain(DeploymentPersistence.toRecord(original))

        assertEquals(identity, restored.instrument)
    }

    @Test
    fun configurationRoundTrip_persistsOpenDeadlineEnable() {
        val rules = TouchTurnRuleConfig.DEFAULT.copy(
            enables = TouchTurnRuleEnables.DEFAULT.copy(openDeadline = true)
        )
        val original = defaultStrategyDeployment(
            strategyType = StrategyType.TOUCH_AND_TURN_SCALPER,
            symbol = "0700",
            maxDollars = 500
        ).copy(touchTurnRules = rules)

        val record = DeploymentPersistence.toRecord(original)
        val restored = DeploymentPersistence.toDomain(record)

        assertEquals(true, restored.touchTurnRules.enables.openDeadline)
        assertEquals(true, record.configuration.touchTurnRules?.enableOpenDeadline)
    }

    @Test
    fun configurationRoundTrip_persistsTouchTurnRuleEnables() {
        val rules = TouchTurnRuleConfig.DEFAULT.copy(
            enables = TouchTurnRuleEnables.DEFAULT.copy(
                liquidityRangeDailyAtr = false,
                adjustableTrailingStop = false
            )
        )
        val original = defaultStrategyDeployment(
            strategyType = StrategyType.TOUCH_AND_TURN_SCALPER,
            symbol = "AAPL",
            maxDollars = 500
        ).copy(touchTurnRules = rules)

        val restored = DeploymentPersistence.toDomain(DeploymentPersistence.toRecord(original))

        assertEquals(false, restored.touchTurnRules?.enables?.liquidityRangeDailyAtr)
        assertEquals(false, restored.touchTurnRules?.enables?.adjustableTrailingStop)
        assertEquals(false, restored.touchTurnRules?.enables?.openDeadline)
    }

    @Test
    fun configurationRoundTrip_migratesLegacyEnableOpenDeadlineTrue() {
        val legacyRecord = TouchTurnRuleConfigRecord(
            enableOpenDeadline = true
        )
        val restored = TouchTurnRuleConfigPersistence.toDomain(legacyRecord)
        assertEquals(true, restored.enables.openDeadline)

        val migrated = restored.withNewConfigurableRulesDisabled()
        assertEquals(false, migrated.enables.openDeadline)
    }

    @Test
    fun configurationRoundTrip_defaultTouchTurnRulesPersistNewRuleDefaults() {
        val original = defaultStrategyDeployment(
            strategyType = StrategyType.TOUCH_AND_TURN_SCALPER,
            symbol = "AAPL",
            maxDollars = 500,
            brokerKind = daytrader.gateway.BrokerKind.EMULATOR
        )
        val record = DeploymentPersistence.toRecord(original).configuration.touchTurnRules
        assertEquals(false, record?.enableOpenDeadline)
        assertEquals(90, record?.stopAfterOpenMinutes)
    }
}
