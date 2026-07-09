package daytrader.domain

import kotlinx.serialization.Serializable

/** One credit when a Touch Turn session stops without placing orders. */
@Serializable
data class LiquidityBucketCredit(
    val sessionId: String,
    val deploymentId: String,
    val symbol: String,
    val amount: Int,
    val sessionDate: String,
    val outcome: String,
    val creditedAtEpochMs: Long
)

/** Per-currency liquidity pool for a single trading day. */
@Serializable
data class LiquidityCurrencyBucket(
    val currencyCode: String,
    val sessionDate: String,
    val available: Int = 0,
    val credits: List<LiquidityBucketCredit> = emptyList(),
    val debits: List<LiquidityBucketDebit> = emptyList()
)

@Serializable
data class LiquidityBucketDebit(
    val deploymentId: String,
    val symbol: String,
    val amount: Int,
    val sessionDate: String,
    val debitedAtEpochMs: Long
)

@Serializable
data class LiquidityBucketState(
    val buckets: Map<String, LiquidityCurrencyBucket> = emptyMap()
)

object LiquidityBucketLogic {
    fun normalizeCurrency(currencyCode: String): String = currencyCode.trim().uppercase()

    fun isNoTradeCreditEligible(
        touchTurn: TouchTurnSessionContext?,
        maxDollars: Int
    ): Boolean {
        if (touchTurn == null || maxDollars <= 0) return false
        if (touchTurn.ordersPlacedForSession) return false
        val outcome = resolveTouchTurnSessionOutcome(touchTurn)
        return outcome.name.startsWith("NO_TRADE")
    }

    fun creditAmountForSession(maxDollars: Int): Int = maxDollars.coerceAtLeast(0)

    fun bucketForCurrency(state: LiquidityBucketState, currencyCode: String): LiquidityCurrencyBucket {
        val key = normalizeCurrency(currencyCode)
        return state.buckets[key] ?: LiquidityCurrencyBucket(currencyCode = key, sessionDate = "")
    }

    fun rollBucketForDate(
        bucket: LiquidityCurrencyBucket,
        sessionDate: String
    ): LiquidityCurrencyBucket =
        if (bucket.sessionDate == sessionDate) {
            bucket
        } else {
            LiquidityCurrencyBucket(currencyCode = bucket.currencyCode, sessionDate = sessionDate)
        }

    fun creditSession(
        state: LiquidityBucketState,
        currencyCode: String,
        sessionDate: String,
        sessionId: String,
        deploymentId: String,
        symbol: String,
        amount: Int,
        outcome: TouchTurnSessionOutcome,
        creditedAtEpochMs: Long
    ): LiquidityBucketState {
        if (amount <= 0) return state
        val key = normalizeCurrency(currencyCode)
        val rolled = rollBucketForDate(bucketForCurrency(state, key), sessionDate)
        if (rolled.credits.any { it.sessionId == sessionId }) return state
        val updated = rolled.copy(
            sessionDate = sessionDate,
            available = rolled.available + amount,
            credits = rolled.credits + LiquidityBucketCredit(
                sessionId = sessionId,
                deploymentId = deploymentId,
                symbol = symbol,
                amount = amount,
                sessionDate = sessionDate,
                outcome = outcome.name,
                creditedAtEpochMs = creditedAtEpochMs
            )
        )
        return state.copy(buckets = state.buckets + (key to updated))
    }

    fun debitAllocation(
        state: LiquidityBucketState,
        currencyCode: String,
        sessionDate: String,
        deploymentId: String,
        symbol: String,
        amount: Int,
        debitedAtEpochMs: Long
    ): Result<LiquidityBucketState> {
        if (amount <= 0) return Result.failure(IllegalArgumentException("allocation_must_be_positive"))
        val key = normalizeCurrency(currencyCode)
        val rolled = rollBucketForDate(bucketForCurrency(state, key), sessionDate)
        if (rolled.available < amount) {
            return Result.failure(IllegalArgumentException("insufficient_liquidity"))
        }
        val updated = rolled.copy(
            sessionDate = sessionDate,
            available = rolled.available - amount,
            debits = rolled.debits + LiquidityBucketDebit(
                deploymentId = deploymentId,
                symbol = symbol,
                amount = amount,
                sessionDate = sessionDate,
                debitedAtEpochMs = debitedAtEpochMs
            )
        )
        return Result.success(state.copy(buckets = state.buckets + (key to updated)))
    }

    /** Restores [amount] debited for an allocator apply when broker resize fails after debit. */
    fun refundAllocation(
        state: LiquidityBucketState,
        currencyCode: String,
        sessionDate: String,
        deploymentId: String,
        amount: Int,
    ): Result<LiquidityBucketState> {
        if (amount <= 0) return Result.failure(IllegalArgumentException("allocation_must_be_positive"))
        val key = normalizeCurrency(currencyCode)
        val rolled = rollBucketForDate(bucketForCurrency(state, key), sessionDate)
        val debitIndex = rolled.debits.indexOfLast { debit ->
            debit.deploymentId == deploymentId &&
                debit.amount == amount &&
                debit.sessionDate == sessionDate
        }
        if (debitIndex < 0) {
            return Result.failure(IllegalArgumentException("allocation_debit_not_found"))
        }
        val updated = rolled.copy(
            sessionDate = sessionDate,
            available = rolled.available + amount,
            debits = rolled.debits.filterIndexed { index, _ -> index != debitIndex },
        )
        return Result.success(state.copy(buckets = state.buckets + (key to updated)))
    }

    /** Discards today's liquidity pool for [currencyCode] (available balance and credit/debit history). */
    fun clearBucketForDate(
        state: LiquidityBucketState,
        currencyCode: String,
        sessionDate: String,
    ): Result<Pair<LiquidityBucketState, Int>> {
        val key = normalizeCurrency(currencyCode)
        val rolled = rollBucketForDate(bucketForCurrency(state, key), sessionDate)
        if (rolled.available <= 0 && rolled.credits.isEmpty() && rolled.debits.isEmpty()) {
            return Result.failure(IllegalStateException("nothing_to_clear"))
        }
        val clearedAmount = rolled.available
        val nextBuckets = state.buckets - key
        return Result.success(state.copy(buckets = nextBuckets) to clearedAmount)
    }

    fun currenciesWithActivity(state: LiquidityBucketState): List<String> =
        state.buckets.values
            .filter { it.available > 0 || it.credits.isNotEmpty() }
            .map { it.currencyCode }
            .distinct()
            .sorted()
}
