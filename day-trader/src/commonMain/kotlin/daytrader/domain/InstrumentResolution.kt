package daytrader.domain

data class InstrumentResolution(
    val candidates: List<ResolvedInstrument>
) {
    val requiresSelection: Boolean get() = candidates.size > 1

    fun singleOrNull(): ResolvedInstrument? = candidates.singleOrNull()
}
