package daytrader.data.persistence

import daytrader.domain.LiquidityBucketCredit
import daytrader.domain.LiquidityBucketDebit
import daytrader.domain.LiquidityBucketState
import daytrader.domain.LiquidityCurrencyBucket

internal object LiquidityBucketPersistence {
    fun toDocument(state: LiquidityBucketState): LiquidityBucketsDocument =
        LiquidityBucketsDocument(
            buckets = state.buckets.values.map { it.toRecord() }
        )

    fun fromDocument(document: LiquidityBucketsDocument): LiquidityBucketState =
        LiquidityBucketState(
            buckets = document.buckets.associate { record ->
                record.currencyCode.uppercase() to record.toDomain()
            }
        )

    private fun LiquidityCurrencyBucket.toRecord(): LiquidityCurrencyBucketRecord =
        LiquidityCurrencyBucketRecord(
            currencyCode = currencyCode,
            sessionDate = sessionDate,
            available = available,
            credits = credits.map { credit ->
                LiquidityBucketCreditRecord(
                    sessionId = credit.sessionId,
                    deploymentId = credit.deploymentId,
                    symbol = credit.symbol,
                    amount = credit.amount,
                    sessionDate = credit.sessionDate,
                    outcome = credit.outcome,
                    creditedAtEpochMs = credit.creditedAtEpochMs
                )
            },
            debits = debits.map { debit ->
                LiquidityBucketDebitRecord(
                    deploymentId = debit.deploymentId,
                    symbol = debit.symbol,
                    amount = debit.amount,
                    sessionDate = debit.sessionDate,
                    debitedAtEpochMs = debit.debitedAtEpochMs
                )
            }
        )

    private fun LiquidityCurrencyBucketRecord.toDomain(): LiquidityCurrencyBucket =
        LiquidityCurrencyBucket(
            currencyCode = currencyCode.uppercase(),
            sessionDate = sessionDate,
            available = available,
            credits = credits.map { record ->
                LiquidityBucketCredit(
                    sessionId = record.sessionId,
                    deploymentId = record.deploymentId,
                    symbol = record.symbol,
                    amount = record.amount,
                    sessionDate = record.sessionDate,
                    outcome = record.outcome,
                    creditedAtEpochMs = record.creditedAtEpochMs
                )
            },
            debits = debits.map { record ->
                LiquidityBucketDebit(
                    deploymentId = record.deploymentId,
                    symbol = record.symbol,
                    amount = record.amount,
                    sessionDate = record.sessionDate,
                    debitedAtEpochMs = record.debitedAtEpochMs
                )
            }
        )
}
