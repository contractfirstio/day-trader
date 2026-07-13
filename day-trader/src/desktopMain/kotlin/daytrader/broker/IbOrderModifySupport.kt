package daytrader.broker

import com.ib.client.Contract
import com.ib.client.Decimal
import com.ib.client.Order
import daytrader.domain.TouchTurnBracketOrderIds

/**
 * Builds IB bracket resize submissions from live [Order] templates captured in [openOrder].
 * Modifying only quantity (plus transmit flags) preserves fields IB expects (TIF, prices, trail attachments).
 */
internal object IbOrderModifySupport {
    fun copyTemplate(source: Order): Order {
        val copy = Order()
        copy.orderId(source.orderId())
        copy.clientId(source.clientId())
        if (source.permId() > 0L) copy.permId(source.permId())
        val action = source.getAction()
        if (!action.isNullOrBlank()) copy.action(action)
        val orderType = source.getOrderType()
        if (!orderType.isNullOrBlank()) copy.orderType(orderType)
        copy.totalQuantity(source.totalQuantity())
        copy.tif(source.tif())
        copy.lmtPrice(source.lmtPrice())
        copy.auxPrice(source.auxPrice())
        copy.parentId(source.parentId())
        val account = source.account()
        if (!account.isNullOrBlank()) copy.account(account)
        copy.outsideRth(source.outsideRth())
        copy.transmit(source.transmit())
        copy.goodTillDate(source.goodTillDate().orEmpty())
        if (source.triggerPrice() > 0.0) copy.triggerPrice(source.triggerPrice())
        if (source.adjustedStopPrice() > 0.0) copy.adjustedStopPrice(source.adjustedStopPrice())
        if (source.adjustedTrailingAmount() > 0.0) copy.adjustedTrailingAmount(source.adjustedTrailingAmount())
        source.adjustedOrderType()?.let { copy.adjustedOrderType(it) }
        copy.adjustableTrailingUnit(source.adjustableTrailingUnit())
        if (source.trailStopPrice() > 0.0) copy.trailStopPrice(source.trailStopPrice())
        return copy
    }

    fun buildResizeSubmission(
        symbol: String,
        contract: Contract,
        orderIds: TouchTurnBracketOrderIds,
        templatesByOrderId: Map<Int, Order>,
        targetQuantity: Int,
    ): IbTouchTurnBracketSubmission? {
        if (targetQuantity <= 0) return null
        val parentTemplate = templatesByOrderId[orderIds.parentOrderId] ?: return null
        val takeProfitTemplate = templatesByOrderId[orderIds.takeProfitOrderId] ?: return null
        val stopTemplate = templatesByOrderId[orderIds.stopLossOrderId] ?: return null
        val hasAdjustableStop = orderIds.adjustableStopOrderId != null
        val adjustableStop = orderIds.adjustableStopOrderId?.let { adjustableId ->
            templatesByOrderId[adjustableId]?.let { template ->
                copyTemplate(template).apply {
                    totalQuantity(Decimal.get(targetQuantity.toLong()))
                    parentId(orderIds.stopLossOrderId)
                    transmit(true)
                }
            }
        }
        if (hasAdjustableStop && adjustableStop == null) return null

        val parent = copyTemplate(parentTemplate).apply {
            totalQuantity(Decimal.get(targetQuantity.toLong()))
            parentId(0)
            transmit(true)
        }
        val takeProfit = copyTemplate(takeProfitTemplate).apply {
            totalQuantity(Decimal.get(targetQuantity.toLong()))
            parentId(orderIds.parentOrderId)
            transmit(true)
        }
        val stopLoss = copyTemplate(stopTemplate).apply {
            totalQuantity(Decimal.get(targetQuantity.toLong()))
            parentId(orderIds.parentOrderId)
            transmit(true)
        }

        return IbTouchTurnBracketSubmission(
            symbol = symbol,
            contract = contract,
            parentOrderId = orderIds.parentOrderId,
            takeProfitOrderId = orderIds.takeProfitOrderId,
            stopLossOrderId = orderIds.stopLossOrderId,
            adjustableStopOrderId = orderIds.adjustableStopOrderId,
            parent = parent,
            takeProfit = takeProfit,
            stopLoss = stopLoss,
            adjustableStop = adjustableStop,
        )
    }
}
