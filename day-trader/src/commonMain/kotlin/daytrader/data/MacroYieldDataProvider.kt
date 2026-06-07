package daytrader.data

import daytrader.domain.ReversalScoreYieldCurveSnapshot

/**
 * Provides Treasury yield curve data for macro reversal score inputs.
 */
interface MacroYieldDataProvider {
    suspend fun fetchYieldCurveSnapshot(): Result<ReversalScoreYieldCurveSnapshot>
}

/**
 * Stub used in tests and when FRED is unavailable.
 */
class StubMacroYieldDataProvider(
    private val snapshot: ReversalScoreYieldCurveSnapshot = defaultSnapshot()
) : MacroYieldDataProvider {
    override suspend fun fetchYieldCurveSnapshot(): Result<ReversalScoreYieldCurveSnapshot> {
        daytrader.diagnostics.ReversalScoreLog.fredUsingStub("StubMacroYieldDataProvider")
        return Result.success(snapshot)
    }

    companion object {
        fun defaultSnapshot(): ReversalScoreYieldCurveSnapshot {
            val history = List(252) { index -> 0.35 + (index % 20) * 0.01 - 0.05 }
            return ReversalScoreYieldCurveSnapshot(
                tenYearYield = 4.25,
                twoYearYield = 3.95,
                spread = 0.30,
                spreadHistory = history
            )
        }
    }
}
