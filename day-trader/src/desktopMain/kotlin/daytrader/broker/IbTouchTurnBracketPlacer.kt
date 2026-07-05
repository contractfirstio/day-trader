package daytrader.broker

import com.ib.client.Contract
import com.ib.client.Decimal
import com.ib.client.EClientSocket
import com.ib.client.Order
import com.ib.client.OrderType
import com.ib.client.Types
import daytrader.domain.TouchTurnBracketOrderIds
import daytrader.domain.TouchTurnOrderPlan
import daytrader.domain.TouchTurnOrderRole
import daytrader.domain.TouchTurnPlannedOrder
import daytrader.domain.InstrumentPriceTick

/**
 * Builds a Touch Turn bracket for IB: parent entry LMT or STP (invert), take-profit LMT child,
 * stop STP child, and optional adjustable-stop attachment that converts the stop to TRAIL at
 * [TouchTurnPlannedOrder.trailTriggerPrice].
 * Parent and children are sent together in one pacer job (last leg transmit=true); see
 * [IbTouchTurnBracketCoordinator].
 */
internal object IbTouchTurnBracketPlacer {
    private const val BRACKET_LEG_COUNT = 3
    private const val ADJUSTABLE_STOP_LEG_COUNT = 4
    /** IB [Order.adjustableTrailingUnit]: 0 = nominal amount, 100 = percent. */
    private const val TRAIL_UNIT_NOMINAL_AMOUNT = 0

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

        val hasAdjustableStop = stopLoss.trailTriggerPrice != null && stopLoss.trailArmStopPrice != null
        val legCount = if (hasAdjustableStop) ADJUSTABLE_STOP_LEG_COUNT else BRACKET_LEG_COUNT
        val parentOrderId = allocateOrderIds(legCount) ?: run {
            IbGatewayLog.touchTurnBracketSkipped("Order id not ready (await nextValidId)")
            return null
        }
        val takeProfitOrderId = parentOrderId + 1
        val stopLossOrderId = parentOrderId + 2
        val adjustableStopOrderId = if (hasAdjustableStop) parentOrderId + 3 else null

        val symbol = SymbolMarkets.normalizeSymbol(plan.symbol)
        val contract = contractFor(plan, symbol)

        val stopTransmit = !hasAdjustableStop
        val adjustableStop = adjustableStopOrderId?.let { adjId ->
            buildAdjustableStopOrder(
                config = config,
                plan = plan,
                stopLoss = stopLoss,
                orderId = adjId,
                stopLossOrderId = stopLossOrderId,
                transmit = true
            )
        }

        return IbTouchTurnBracketSubmission(
            symbol = symbol,
            contract = contract,
            parentOrderId = parentOrderId,
            takeProfitOrderId = takeProfitOrderId,
            stopLossOrderId = stopLossOrderId,
            adjustableStopOrderId = adjustableStopOrderId,
            parent = buildOrder(config, plan, entry, parentOrderId, parentOrderId = 0, transmit = false),
            takeProfit = buildOrder(config, plan, takeProfit, takeProfitOrderId, parentOrderId = parentOrderId, transmit = false),
            stopLoss = buildOrder(config, plan, stopLoss, stopLossOrderId, parentOrderId = parentOrderId, transmit = stopTransmit),
            adjustableStop = adjustableStop
        )
    }

    fun buildResize(
        config: IbGatewayConfig,
        plan: TouchTurnOrderPlan,
        orderIds: TouchTurnBracketOrderIds
    ): IbTouchTurnBracketSubmission? {
        val entry = plan.orders.firstOrNull { it.role == TouchTurnOrderRole.ENTRY }
        val takeProfit = plan.orders.firstOrNull { it.role == TouchTurnOrderRole.TAKE_PROFIT }
        val stopLoss = plan.orders.firstOrNull { it.role == TouchTurnOrderRole.STOP_LOSS }
        if (entry == null || takeProfit == null || stopLoss == null) {
            IbGatewayLog.touchTurnBracketSkipped("Missing bracket leg in resize plan")
            return null
        }
        if (entry.quantity <= 0) {
            IbGatewayLog.touchTurnBracketSkipped("Invalid resize quantity")
            return null
        }
        val symbol = SymbolMarkets.normalizeSymbol(plan.symbol)
        val contract = contractFor(plan, symbol)
        val hasAdjustableStop = stopLoss.trailTriggerPrice != null &&
            stopLoss.trailArmStopPrice != null &&
            orderIds.adjustableStopOrderId != null
        val adjustableStop = orderIds.adjustableStopOrderId?.let { adjId ->
            buildAdjustableStopOrder(
                config = config,
                plan = plan,
                stopLoss = stopLoss,
                orderId = adjId,
                stopLossOrderId = orderIds.stopLossOrderId,
                transmit = false
            )
        }
        return IbTouchTurnBracketSubmission(
            symbol = symbol,
            contract = contract,
            parentOrderId = orderIds.parentOrderId,
            takeProfitOrderId = orderIds.takeProfitOrderId,
            stopLossOrderId = orderIds.stopLossOrderId,
            adjustableStopOrderId = orderIds.adjustableStopOrderId,
            parent = buildOrder(config, plan, entry, orderIds.parentOrderId, parentOrderId = 0, transmit = false),
            takeProfit = buildOrder(
                config,
                plan,
                takeProfit,
                orderIds.takeProfitOrderId,
                parentOrderId = orderIds.parentOrderId,
                transmit = false
            ),
            stopLoss = buildOrder(
                config,
                plan,
                stopLoss,
                orderIds.stopLossOrderId,
                parentOrderId = orderIds.parentOrderId,
                transmit = !hasAdjustableStop
            ),
            adjustableStop = adjustableStop?.let { order ->
                order.transmit(true)
                order
            }
        )
    }

    private fun contractFor(plan: TouchTurnOrderPlan, symbol: String): Contract {
        val contract = IbContractMapper.contractForSymbol(symbol, plan.instrument)
        val currency = plan.currencyCode.ifBlank { SymbolMarkets.currencyCode(symbol) }
        if (currency.isNotBlank() && contract.currency().isNullOrBlank()) {
            contract.currency(currency)
        }
        return contract
    }

    private fun buildOrder(
        config: IbGatewayConfig,
        plan: TouchTurnOrderPlan,
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
        applyTimeInForce(order, planned.timeInForce)
        order.outsideRth(false)
        order.transmit(transmit)
        if (parentOrderId > 0) {
            order.parentId(parentOrderId)
        }
        if (config.accountCode.isNotBlank()) {
            order.account(config.accountCode)
        }
        val roundedPrice = InstrumentPriceTick.roundForInstrument(planned.price, plan.instrument, plan.symbol)
        when (planned.orderType.uppercase()) {
            "LMT" -> order.lmtPrice(roundedPrice)
            "STP", "STP LMT" -> order.auxPrice(roundedPrice)
            "MKT" -> Unit
            else -> order.lmtPrice(roundedPrice)
        }
        return order
    }

    private fun buildAdjustableStopOrder(
        config: IbGatewayConfig,
        plan: TouchTurnOrderPlan,
        stopLoss: TouchTurnPlannedOrder,
        orderId: Int,
        stopLossOrderId: Int,
        transmit: Boolean
    ): Order {
        val triggerPrice = stopLoss.trailTriggerPrice!!
        val roundedStop = InstrumentPriceTick.roundForInstrument(stopLoss.price, plan.instrument, plan.symbol)
        val roundedTrigger = InstrumentPriceTick.roundForInstrument(triggerPrice, plan.instrument, plan.symbol)
        val roundedArmStop = InstrumentPriceTick.roundForInstrument(
            stopLoss.trailArmStopPrice ?: stopLoss.price,
            plan.instrument,
            plan.symbol
        )
        val order = Order()
        order.orderId(orderId)
        order.clientId(config.clientId)
        order.action(stopLoss.action)
        order.orderType("STP")
        order.totalQuantity(Decimal.get(stopLoss.quantity.toLong()))
        applyTimeInForce(order, stopLoss.timeInForce)
        order.outsideRth(false)
        order.transmit(transmit)
        order.parentId(stopLossOrderId)
        order.auxPrice(roundedStop)
        order.triggerPrice(roundedTrigger)
        order.adjustedOrderType(OrderType.TRAIL)
        order.adjustedStopPrice(roundedArmStop)
        order.adjustableTrailingUnit(TRAIL_UNIT_NOMINAL_AMOUNT)
        order.adjustedTrailingAmount(0.0)
        if (config.accountCode.isNotBlank()) {
            order.account(config.accountCode)
        }
        return order
    }

    private fun applyTimeInForce(order: Order, timeInForce: String) {
        when (timeInForce.uppercase()) {
            "GTC" -> {
                order.tif(Types.TimeInForce.GTC)
                order.goodTillDate("")
            }
            else -> {
                order.tif(Types.TimeInForce.DAY)
                order.goodTillDate("")
            }
        }
    }
}

internal data class IbTouchTurnBracketSubmission(
    val symbol: String,
    val contract: Contract,
    val parentOrderId: Int,
    val takeProfitOrderId: Int,
    val stopLossOrderId: Int,
    val adjustableStopOrderId: Int?,
    val parent: Order,
    val takeProfit: Order,
    val stopLoss: Order,
    val adjustableStop: Order?
)
