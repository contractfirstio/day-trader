package daytrader.broker

import com.ib.client.Contract
import com.ib.client.Decimal
import com.ib.client.EClientSocket
import com.ib.client.Order
import com.ib.client.Types
import daytrader.domain.TouchTurnOrderPlan
import daytrader.domain.TouchTurnOrderRole
import daytrader.domain.TouchTurnPlannedOrder

/**
 * Builds a Touch Turn bracket for IB: parent entry LMT, take-profit LMT child, stop STP child.
 * [IbTouchTurnBracketSubmission] must be sent with three separate paced [placeOrder] calls.
 */
internal object IbTouchTurnBracketPlacer {
    private const val LEG_COUNT = 3

    fun build(
        client: EClientSocket,
        config: IbGatewayConfig,
        plan: TouchTurnOrderPlan,
        allocateOrderIds: (Int) -> Int?
    ): IbTouchTurnBracketSubmission? {
        if (!client.isConnected) {
            IbGatewayLog.touchTurnBracketSkipped("Not connected")
            return null
        }
        val entry = plan.orders.firstOrNull { it.role == TouchTurnOrderRole.ENTRY }
        val takeProfit = plan.orders.firstOrNull { it.role == TouchTurnOrderRole.TAKE_PROFIT }
        val stopLoss = plan.orders.firstOrNull { it.role == TouchTurnOrderRole.STOP_LOSS }
        if (entry == null || takeProfit == null || stopLoss == null) {
            IbGatewayLog.touchTurnBracketSkipped("Missing bracket leg in plan")
            return null
        }
        if (entry.quantity <= 0) {
            IbGatewayLog.touchTurnBracketSkipped("Invalid quantity")
            return null
        }

        val parentOrderId = allocateOrderIds(LEG_COUNT) ?: run {
            IbGatewayLog.touchTurnBracketSkipped("Order id not ready (await nextValidId)")
            return null
        }
        val takeProfitOrderId = parentOrderId + 1
        val stopLossOrderId = parentOrderId + 2

        val symbol = SymbolMarkets.normalizeSymbol(plan.symbol)
        val contract = contractFor(plan, symbol)

        return IbTouchTurnBracketSubmission(
            symbol = symbol,
            contract = contract,
            parentOrderId = parentOrderId,
            takeProfitOrderId = takeProfitOrderId,
            stopLossOrderId = stopLossOrderId,
            parent = buildOrder(config, entry, parentOrderId, parentOrderId = 0, transmit = false),
            takeProfit = buildOrder(config, takeProfit, takeProfitOrderId, parentOrderId = parentOrderId, transmit = false),
            stopLoss = buildOrder(config, stopLoss, stopLossOrderId, parentOrderId = parentOrderId, transmit = true)
        )
    }

    private fun contractFor(plan: TouchTurnOrderPlan, symbol: String): Contract {
        val contract = IbContractMapper.stockForHistorical(symbol)
        val currency = plan.currencyCode.ifBlank { SymbolMarkets.currencyCode(symbol) }
        if (currency.isNotBlank()) {
            contract.currency(currency)
        }
        return contract
    }

    private fun buildOrder(
        config: IbGatewayConfig,
        planned: TouchTurnPlannedOrder,
        orderId: Int,
        parentOrderId: Int,
        transmit: Boolean
    ): Order {
        val order = Order()
        order.orderId(orderId)
        order.clientId(config.clientId)
        order.action(planned.action)
        order.orderType(planned.orderType)
        order.totalQuantity(Decimal.get(planned.quantity.toLong()))
        // Touch Turn: always DAY — never GTC/GTD (goodTillDate must stay empty for DAY).
        order.tif(Types.TimeInForce.DAY)
        order.goodTillDate("")
        order.outsideRth(false)
        order.transmit(transmit)
        if (parentOrderId > 0) {
            order.parentId(parentOrderId)
        }
        if (config.accountCode.isNotBlank()) {
            order.account(config.accountCode)
        }
        when (planned.orderType.uppercase()) {
            "LMT" -> order.lmtPrice(planned.price)
            "STP", "STP LMT" -> order.auxPrice(planned.price)
            else -> order.lmtPrice(planned.price)
        }
        return order
    }
}

internal data class IbTouchTurnBracketSubmission(
    val symbol: String,
    val contract: Contract,
    val parentOrderId: Int,
    val takeProfitOrderId: Int,
    val stopLossOrderId: Int,
    val parent: Order,
    val takeProfit: Order,
    val stopLoss: Order
)
