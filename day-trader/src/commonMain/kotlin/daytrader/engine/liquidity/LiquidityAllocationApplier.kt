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

/**
 * Debits the liquidity pool and upsizes a working Touch Turn bracket at the broker.
 *
 * Bracket resize uses [TouchTurnBracketResizer] — the same path as manual Orders/Strategies
 * amends — so IB template copy, transmit-on-modify, and deferred ack behavior apply here too.
 * Preview sizing uses [LiquidityAllocatorMapper.effectiveEntryQuantity] (session + broker);
 * the broker upsize baseline is validated separately via [LiquidityAllocatorMapper.brokerEntryQuantity].
 */

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
    val additionalQuantity: Int,
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
    private val bracketResizer = TouchTurnBracketResizer(
        executionManager = executionManager,
        deploymentRepository = deploymentRepository,
    )

    suspend fun apply(request: LiquidityAllocationApplyRequest): LiquidityAllocationApplyResult {
        if (executionManager == null) {
            return LiquidityAllocationApplyResult.Skipped(
                deploymentId = request.deploymentId,
                reason = LiquidityApplySkipReason.EXECUTION_NOT_AVAILABLE,
            )
        }
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
            allocationAdditionalQty = request.additionalQuantity,
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
        val symbolOrders = SymbolMarkets.openOrdersForDeployment(deployment, request.openOrders)
        val entryOrder = symbolOrders.firstOrNull { it.parentOrderId == 0 && it.remaining > 0 }
            ?: return LiquidityAllocationApplyResult.Skipped(
                deploymentId = request.deploymentId,
                reason = LiquidityApplySkipReason.NOT_ELIGIBLE,
            )
        val brokerQty = LiquidityAllocatorMapper.brokerEntryQuantity(entryOrder)
        if (row.previewQuantity <= brokerQty) {
            return LiquidityAllocationApplyResult.Skipped(
                deploymentId = request.deploymentId,
                reason = LiquidityApplySkipReason.PREVIEW_NOT_GREATER_THAN_CURRENT,
            )
        }

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

        return when (
            val amendResult = bracketResizer.amend(
                deploymentId = request.deploymentId,
                deployment = deployment,
                openOrders = request.openOrders,
                targetQuantity = row.previewQuantity,
            )
        ) {
            is TouchTurnBracketAmendResult.Success -> {
                liquidityBucketRepository.flushPersistence()
                LiquidityAllocationApplyResult.Success(
                    deploymentId = request.deploymentId,
                    debitedAmount = effectiveNotional,
                    newQuantity = amendResult.newQuantity,
                )
            }
            is TouchTurnBracketAmendResult.Skipped -> {
                liquidityBucketRepository.refundAllocation(
                    currencyCode = deployment.currencyCode,
                    sessionDate = request.sessionDate,
                    deploymentId = request.deploymentId,
                    amount = effectiveNotional,
                )
                LiquidityAllocationApplyResult.Failed(
                    deploymentId = request.deploymentId,
                    message = amendResult.reason,
                )
            }
            is TouchTurnBracketAmendResult.Failed -> {
                liquidityBucketRepository.refundAllocation(
                    currencyCode = deployment.currencyCode,
                    sessionDate = request.sessionDate,
                    deploymentId = request.deploymentId,
                    amount = effectiveNotional,
                )
                LiquidityAllocationApplyResult.Failed(
                    deploymentId = request.deploymentId,
                    message = amendResult.message,
                )
            }
        }
    }
}
