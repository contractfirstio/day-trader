package daytrader.broker

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IbFlexTradeParserTest {
    @Test
    fun parseTrades_readsSettledTradeAttributes() {
        val xml = """
            <FlexQueryResponse>
              <FlexStatements>
                <FlexStatement>
                  <Trades>
                    <Trade
                      tradeID="987654"
                      symbol="AAPL"
                      currency="USD"
                      tradeDate="20260601"
                      tradeTime="093001"
                      buySell="BUY"
                      quantity="10"
                      tradePrice="150.5"
                      ibCommission="-0.35"
                      fifoPnlRealized="12.5"
                      ibOrderID="42"
                    />
                  </Trades>
                </FlexStatement>
              </FlexStatements>
            </FlexQueryResponse>
        """.trimIndent()

        val trades = IbFlexTradeParser.parseTrades(xml)
        assertEquals(1, trades.size)
        val trade = trades.single()
        assertEquals("flex-987654", trade.execId)
        assertEquals("AAPL", trade.symbol)
        assertEquals("BOT", trade.side)
        assertEquals(10, trade.quantity)
        assertEquals(150.5, trade.price)
        assertEquals(0.35, trade.commission)
        assertEquals(12.5, trade.realizedPnL)
        assertEquals("2026-06-01 09:30:01", trade.time)
    }

    @Test
    fun parseTrades_usesStatementDateOnlyForSingleDayReports() {
        val xml = """
            <FlexQueryResponse>
              <FlexStatements>
                <FlexStatement fromDate="20260707" toDate="20260707">
                  <Trades>
                    <Trade tradeID="1" symbol="AAPL" quantity="1" tradePrice="10" buySell="BUY" />
                  </Trades>
                </FlexStatement>
              </FlexStatements>
            </FlexQueryResponse>
        """.trimIndent()
        assertEquals("2026-07-07", IbFlexTradeParser.parseTrades(xml).single().time)
    }

    @Test
    fun parseTrades_doesNotStampMultiDayStatementDateOntoTrades() {
        val xml = """
            <FlexQueryResponse>
              <FlexStatements>
                <FlexStatement fromDate="20260705" toDate="20260707">
                  <Trades>
                    <Trade tradeID="1" symbol="AAPL" quantity="1" tradePrice="10" buySell="BUY" />
                  </Trades>
                </FlexStatement>
              </FlexStatements>
            </FlexQueryResponse>
        """.trimIndent()
        assertEquals("", IbFlexTradeParser.parseTrades(xml).single().time)
    }

    @Test
    fun parseTrades_acceptsMinimalExecutionFieldSet() {
        val xml = """
            <FlexQueryResponse>
              <FlexStatements>
                <FlexStatement fromDate="20260707" toDate="20260707">
                  <Trades>
                    <Trade
                      tradeID="123"
                      symbol="MSFT"
                      quantity="5"
                      price="420.25"
                      ibCommission="-1.00"
                      fifoPnlRealized="8.75"
                      buySell="SELL"
                      ibOrderID="99"
                    />
                  </Trades>
                </FlexStatement>
              </FlexStatements>
            </FlexQueryResponse>
        """.trimIndent()

        val trade = IbFlexTradeParser.parseTrades(xml).single()
        assertEquals("flex-123", trade.execId)
        assertEquals("MSFT", trade.symbol)
        assertEquals("SLD", trade.side)
        assertEquals(5, trade.quantity)
        assertEquals(420.25, trade.price)
        assertEquals(1.0, trade.commission)
        assertEquals(8.75, trade.realizedPnL)
        assertEquals("2026-07-07", trade.time)
    }
}
