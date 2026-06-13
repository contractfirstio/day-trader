package daytrader.domain

/**
 * Counts qualified rejections (bounces) off the fade extreme during the opening 15m bar.
 *
 * Long (red): bounces off [OhlcBar.low]. Short (green): bounces off [OhlcBar.high].
 * A bounce = enter the touch zone at the extreme, then recover at least [recoveryRatioOfRange] away.
 */
object TouchTurnExtremeBounceEvaluator {

    enum class Phase {
        AWAY,
        TOUCHING,
        RECOVERED
    }

    data class Result(
        val bounceCount: Int,
        val requiredBounces: Int,
        val passed: Boolean,
        val sampleCount: Int
    ) {
        val dataAvailable: Boolean get() = sampleCount > 0
    }

    fun evaluate(
        setup: TouchTurnBracketSetup,
        bar: OhlcBar,
        samples: List<TouchTurnOpeningBarPriceSample>,
        rules: TouchTurnRuleConfig = TouchTurnRuleConfig.DEFAULT
    ): Result {
        val required = rules.requiredExtremeBounceCount
        if (!rules.enables.bounceRejection) {
            return Result(
                bounceCount = 0,
                requiredBounces = required,
                passed = true,
                sampleCount = samples.size
            )
        }
        if (setup.candleColor == FirstCandleColor.DOJI) {
            return Result(
                bounceCount = 0,
                requiredBounces = required,
                passed = false,
                sampleCount = samples.size
            )
        }
        val range = bar.range
        if (range <= 0.0) {
            return Result(
                bounceCount = 0,
                requiredBounces = required,
                passed = false,
                sampleCount = samples.size
            )
        }
        val touchBand = range * rules.bounceTouchZoneRatioOfRange
        val recoveryBand = range * rules.bounceRecoveryRatioOfRange
        val ordered = samples
            .filter { it.price > 0.0 }
            .sortedBy { it.epochMs }
        val bounceCount = countBounces(
            setup = setup,
            bar = bar,
            orderedSamples = ordered,
            touchBand = touchBand,
            recoveryBand = recoveryBand
        )
        return Result(
            bounceCount = bounceCount,
            requiredBounces = required,
            passed = bounceCount >= required,
            sampleCount = ordered.size
        )
    }

    internal fun countBounces(
        setup: TouchTurnBracketSetup,
        bar: OhlcBar,
        orderedSamples: List<TouchTurnOpeningBarPriceSample>,
        touchBand: Double,
        recoveryBand: Double
    ): Int {
        if (orderedSamples.isEmpty()) return 0
        var bounces = 0
        var phase = Phase.AWAY
        for (sample in orderedSamples) {
            val price = sample.price
            when (setup.candleColor) {
                FirstCandleColor.RED -> {
                    val touching = price <= bar.low + touchBand
                    val recovered = price >= bar.low + recoveryBand
                    phase = advanceLongPhase(phase, touching, recovered) { bounces++ }
                }
                FirstCandleColor.GREEN -> {
                    val touching = price >= bar.high - touchBand
                    val recovered = price <= bar.high - recoveryBand
                    phase = advanceShortPhase(phase, touching, recovered) { bounces++ }
                }
                FirstCandleColor.DOJI -> return 0
            }
        }
        return bounces
    }

    private inline fun advanceLongPhase(
        phase: Phase,
        touching: Boolean,
        recovered: Boolean,
        onBounce: () -> Unit
    ): Phase = when (phase) {
        Phase.AWAY -> if (touching) Phase.TOUCHING else Phase.AWAY
        Phase.TOUCHING -> when {
            recovered -> {
                onBounce()
                Phase.RECOVERED
            }
            touching -> Phase.TOUCHING
            else -> Phase.AWAY
        }
        Phase.RECOVERED -> if (touching) Phase.TOUCHING else Phase.RECOVERED
    }

    private inline fun advanceShortPhase(
        phase: Phase,
        touching: Boolean,
        recovered: Boolean,
        onBounce: () -> Unit
    ): Phase = when (phase) {
        Phase.AWAY -> if (touching) Phase.TOUCHING else Phase.AWAY
        Phase.TOUCHING -> when {
            recovered -> {
                onBounce()
                Phase.RECOVERED
            }
            touching -> Phase.TOUCHING
            else -> Phase.AWAY
        }
        Phase.RECOVERED -> if (touching) Phase.TOUCHING else Phase.RECOVERED
    }

    fun samplesFromMidPrices(
        epochMs: List<Long>,
        prices: List<Double>
    ): List<TouchTurnOpeningBarPriceSample> {
        if (epochMs.size != prices.size) return emptyList()
        return epochMs.zip(prices) { at, price ->
            TouchTurnOpeningBarPriceSample(epochMs = at, price = price)
        }.filter { it.price > 0.0 }
    }

    fun filterSamplesToBarWindow(
        samples: List<TouchTurnOpeningBarPriceSample>,
        barStartEpochMs: Long,
        barEndEpochMs: Long
    ): List<TouchTurnOpeningBarPriceSample> = samples.filter {
        it.epochMs in barStartEpochMs..barEndEpochMs
    }
}
