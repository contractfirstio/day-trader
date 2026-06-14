package daytrader.domain

import daytrader.gateway.BrokerGateway

object DeploymentSymbolResolver {
    suspend fun resolveForImport(
        symbol: String,
        expectedZoneId: String,
        gateway: BrokerGateway?,
        connected: Boolean
    ): Result<ResolvedInstrument> {
        val trimmed = symbol.trim().uppercase()
        if (trimmed.isBlank()) {
            return Result.failure(IllegalArgumentException("Symbol is blank"))
        }
        val resolution = if (gateway != null && connected) {
            gateway.resolveInstrument(trimmed).fold(
                onSuccess = { it },
                onFailure = { error ->
                    InstrumentResolution(listOf(DeploymentMarket.fromSymbolHeuristic(trimmed)))
                }
            )
        } else {
            InstrumentResolution(listOf(DeploymentMarket.fromSymbolHeuristic(trimmed)))
        }
        val candidates = InstrumentListingCandidates.prepareForUi(resolution.candidates)
        val matching = candidates.filter {
            DeploymentMarket.zonesMatch(it.marketZoneId, expectedZoneId)
        }
        val picked = when {
            matching.size == 1 -> matching.first()
            matching.isNotEmpty() -> matching.first()
            candidates.size == 1 -> candidates.first()
            candidates.isEmpty() -> {
                val heuristic = DeploymentMarket.fromSymbolHeuristic(trimmed)
                if (DeploymentMarket.zonesMatch(heuristic.marketZoneId, expectedZoneId)) {
                    heuristic
                } else {
                    return Result.failure(
                        IllegalStateException(
                            "No ${SymbolImportExchange.marketLabel(expectedZoneId)} listing found for $trimmed"
                        )
                    )
                }
            }
            else -> {
                return Result.failure(
                    IllegalStateException(
                        "Multiple listings for $trimmed; none match ${SymbolImportExchange.marketLabel(expectedZoneId)}"
                    )
                )
            }
        }
        if (!DeploymentMarket.zonesMatch(picked.marketZoneId, expectedZoneId)) {
            return Result.failure(
                IllegalStateException(
                    "Resolved ${SymbolImportExchange.marketLabel(picked.marketZoneId)} listing for $trimmed, " +
                        "expected ${SymbolImportExchange.marketLabel(expectedZoneId)}"
                )
            )
        }
        return Result.success(picked)
    }
}
