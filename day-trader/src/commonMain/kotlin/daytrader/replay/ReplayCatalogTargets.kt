package daytrader.replay

/** Resolves which captured sessions to include in a batch what-if replay run. */
object ReplayCatalogTargets {
    fun resolve(
        catalog: List<ReplayCaptureRef>,
        seedDirectoryPaths: List<String>,
        loadBundle: (String) -> Result<SessionBundle>
    ): List<ReplayCaptureRef> {
        if (catalog.isNotEmpty()) return catalog.distinctBy { it.directoryPath }
        val fromSeeds = seedDirectoryPaths.distinct().mapNotNull { path ->
            loadBundle(path).getOrNull()?.toCaptureRef(path)
        }
        if (fromSeeds.isNotEmpty()) return fromSeeds
        return emptyList()
    }

    private fun SessionBundle.toCaptureRef(directoryPath: String): ReplayCaptureRef =
        ReplayCaptureRef(
            directoryPath = directoryPath,
            deploymentId = deploymentId,
            symbol = symbol,
            sessionDate = sessionDate,
            sessionStartedEpochMs = timeline.sessionStartedEpochMs
        )
}
