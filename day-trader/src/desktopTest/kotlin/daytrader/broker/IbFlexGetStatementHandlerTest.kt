package daytrader.broker

import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class IbFlexGetStatementHandlerTest {
    @Test
    fun classify_treatsFlexQueryResponseAsReady() {
        val xml = """
            <FlexQueryResponse queryName="trades" type="AF">
              <FlexStatements count="1">
                <FlexStatement>
                  <Trades>
                    <Trade tradeID="1" symbol="AAPL" quantity="1" tradePrice="10" buySell="BUY" />
                  </Trades>
                </FlexStatement>
              </FlexStatements>
            </FlexQueryResponse>
        """.trimIndent()
        val document = parseXml(xml)
        val outcome = IbFlexGetStatementHandler.classify(document, xml)
        assertIs<IbFlexGetStatementHandler.Outcome.Ready>(outcome)
    }

    @Test
    fun classify_retriesInProgressStatus() {
        val xml = """
            <FlexStatementResponse>
              <Status>Fail</Status>
              <ErrorCode>1019</ErrorCode>
              <ErrorMessage>Statement generation in progress</ErrorMessage>
            </FlexStatementResponse>
        """.trimIndent()
        val document = parseXml(xml)
        assertEquals(
            IbFlexGetStatementHandler.Outcome.InProgress,
            IbFlexGetStatementHandler.classify(document, xml)
        )
    }

    @Test
    fun classify_retriesRateLimitedStatus() {
        val xml = """
            <FlexStatementResponse>
              <Status>Fail</Status>
              <ErrorCode>1018</ErrorCode>
              <ErrorMessage>Too many requests</ErrorMessage>
            </FlexStatementResponse>
        """.trimIndent()
        val document = parseXml(xml)
        assertEquals(
            IbFlexGetStatementHandler.Outcome.InProgress,
            IbFlexGetStatementHandler.classify(document, xml)
        )
    }

    private fun parseXml(xml: String) =
        DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(xml.byteInputStream())
            .documentElement
}
