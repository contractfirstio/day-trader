package daytrader.data.persistence

import kotlinx.serialization.Serializable

@Serializable
data class LiquidityBucketsDocument(
    val version: Int = 1,
    val buckets: List<LiquidityCurrencyBucketRecord> = emptyList()
)

@Serializable
data class LiquidityCurrencyBucketRecord(
    val currencyCode: String,
    val sessionDate: String,
    val available: Int = 0,
    val credits: List<LiquidityBucketCreditRecord> = emptyList(),
    val debits: List<LiquidityBucketDebitRecord> = emptyList()
)

@Serializable
data class LiquidityBucketCreditRecord(
    val sessionId: String,
    val deploymentId: String,
    val symbol: String,
    val amount: Int,
    val sessionDate: String,
    val outcome: String,
    val creditedAtEpochMs: Long
)

@Serializable
data class LiquidityBucketDebitRecord(
    val deploymentId: String,
    val symbol: String,
    val amount: Int,
    val sessionDate: String,
    val debitedAtEpochMs: Long
)
