package daytrader.engine.liquidity

import daytrader.broker.SymbolMarkets
import daytrader.data.LiquidityBucketRepository
import daytrader.data.StrategyDeploymentRepository
import daytrader.domain.DeploymentStatus
import daytrader.domain.StrategyDeployment
import daytrader.domain.isTouchTurn
import daytrader.execution.ExecutionManager
import daytrader.gateway.LiveQuote
import daytrader.gateway.WorkingOrder
import daytrader.presentation.liquidity.LiquidityAllocatorMapper
import daytrader.presentation.strategies.SessionRollupCache
import kotlin.math.roundToInt

enum class LiquidityApplySkipReason {
    EXECUTION_NOT_AVAILABLE,
    SESSION_NOT_ACTIVE,
    NOT_ELIGIBLE,
    NO_ADDITIONAL_QUANTITY,
    PREVIEW_NOT_GREATER_THAN_CURRENT,
}

sealed interface LiquidityAllocationApplyResult {
    data class Success(
        val deploymentId: String,
        val debitedAmount: Int,
        val newQuantity: Int,
    ) : LiquidityAllocationApplyResult

    data class Skipped(
        val deploymentId: String,
        val reason: LiquidityApplySkipReason,
    ) : LiquidityAllocationApplyResult

    data class Failed(
        val deploymentId: String,
        val message: String,
    ) : LiquidityAllocationApplyResult
}

data class LiquidityAllocationApplyRequest(
    val deploymentId: String,
    val allocationDollars: Int,
    val deployment: StrategyDeployment,
    val openOrders: List<WorkingOrder>,
    val quotes: Map<String, LiveQuote>,
    val selectedCurrency: String,
    val sessionDate: String,
    val sessionRollupCache: SessionRollupCache? = null,
)

fun effectiveAllocationNotional(additionalQty: Int, entryPrice: Double): Int =
    (additionalQty * entryPrice).roundToInt()

class LiquidityAllocationApplier(
    private val liquidityBucketRepository: LiquidityBucketRepository,
    private val executionManager: ExecutionManager?,
    private val deploymentRepository: StrategyDeploymentRepository,
) {
    suspend fun apply(request: LiquidityAllocationApplyRequest): LiquidityAllocationApplyResult {
        val execution = executionManager ?: return LiquidityAllocationApplyResult.Skipped(
            deploymentId = request.deploymentId,
            reason = LiquidityApplySkipReason.EXECUTION_NOT_AVAILABLE,
        )
        val deployment = request.deployment
        if (!deployment.isTouchTurn || deployment.status != DeploymentStatus.RUNNING) {
            return LiquidityAllocationApplyResult.Skipped(
                deploymentId = request.deploymentId,
                reason = LiquidityApplySkipReason.SESSION_NOT_ACTIVE,
            )
        }
        val row = LiquidityAllocatorMapper.buildRowForDeployment(
            deployment = deployment,
            openOrders = request.openOrders,
            quotes = request.quotes,
            selectedCurrency = request.selectedCurrency,
            allocationDollars = request.allocationDollars,
            sessionRollupCache = request.sessionRollupCache,
        ) ?: return LiquidityAllocationApplyResult.Skipped(
            deploymentId = request.deploymentId,
            reason = LiquidityApplySkipReason.NOT_ELIGIBLE,
        )
        val additionalQty = row.previewQuantity - row.currentQuantity
        if (additionalQty <= 0) {
            return LiquidityAllocationApplyResult.Skipped(
                deploymentId = request.deploymentId,
                reason = LiquidityApplySkipReason.NO_ADDITIONAL_QUANTITY,
            )
        }
        if (row.previewQuantity <= row.currentQuantity) {
            return LiquidityAllocationApplyResult.Skipped(
                deploymentId = request.deploymentId,
                reason = LiquidityApplySkipReason.PREVIEW_NOT_GREATER_THAN_CURRENT,
            )
        }
        val resizeRequest = LiquidityAllocatorMapper.buildResizeRequest(
            deployment = deployment,
            openOrders = request.openOrders,
            newQuantity = row.previewQuantity,
        ) ?: return LiquidityAllocationApplyResult.Failed(
            deploymentId = request.deploymentId,
            message = "Could not build resize request",
        )

        val effectiveNotional = effectiveAllocationNotional(additionalQty, row.entryPrice)
        val debitResult = liquidityBucketRepository.debitAllocation(
            currencyCode = deployment.currencyCode,
            sessionDate = request.sessionDate,
            deploymentId = request.deploymentId,
            symbol = deployment.symbol,
            amount = effectiveNotional,
        )
        if (debitResult.isFailure) {
            return LiquidityAllocationApplyResult.Failed(
                deploymentId = request.deploymentId,
                message = debitResult.exceptionOrNull()?.message ?: "Debit failed",
            )
        }

        val resizeResult = execution.resizeTouchTurnBracket(resizeRequest)
        if (resizeResult.isFailure) {
            liquidityBucketRepository.refundAllocation(
                currencyCode = deployment.currencyCode,
                sessionDate = request.sessionDate,
                deploymentId = request.deploymentId,
                amount = effectiveNotional,
            )
            return LiquidityAllocationApplyResult.Failed(
                deploymentId = request.deploymentId,
                message = resizeResult.exceptionOrNull()?.message ?: "Resize failed",
            )
        }

        deploymentRepository.update(request.deploymentId) { current ->
            current.withTouchTurnBracketQuantity(row.previewQuantity)
        }
        liquidityBucketRepository.flushPersistence()
        return LiquidityAllocationApplyResult.Success(
            deploymentId = request.deploymentId,
            debitedAmount = effectiveNotional,
            newQuantity = row.previewQuantity,
        )
    }
}

private fun StrategyDeployment.withTouchTurnBracketQuantity(quantity: Int): StrategyDeployment {
    val session = touchTurnSession ?: return this
    return copy(touchTurnSession = session.copy(plannedQuantity = quantity))
}
