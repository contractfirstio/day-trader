package daytrader.broker

import com.ib.client.Contract
import com.ib.client.ContractDetails
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class IbMarketRuleRulesTest {
    @Test
    fun marketRuleIdForContract_singleId() {
        val details = ContractDetails()
        details.marketRuleIds("2431")
        assertEquals(2431, IbMarketRuleRules.marketRuleIdForContract(details))
    }

    @Test
    fun marketRuleIdForContract_matchesExchangeIndex() {
        val contract = Contract()
        contract.exchange("SEHK")
        contract.primaryExch("SEHK")
        val details = ContractDetails()
        details.contract(contract)
        details.validExchanges("SMART,SEHK")
        details.marketRuleIds("26,2431")
        assertEquals(2431, IbMarketRuleRules.marketRuleIdForContract(details))
    }

    @Test
    fun marketRuleIdForContract_blankReturnsNull() {
        val details = ContractDetails()
        assertNull(IbMarketRuleRules.marketRuleIdForContract(details))
    }
}
