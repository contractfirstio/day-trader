package daytrader.replay

import java.nio.file.Files
import java.nio.file.Path

/**
 * Reads session capture files from an on-disk session directory.
 *
 * Expected layout (under `{broker-scope}/sessions/{deploymentId}/{sessionId}/`):
 * - `manifest.json`
 * - `application.jsonl`
 * - `historical.jsonl`
 * - `prices.jsonl`
 *
 * Optionally pass [ibPriceTicksJsonlPath] for high-fidelity IB tick capture
 * (`runs/.../ib-prices/{SYMBOL}.jsonl`).
 */
object SessionBundleDirectoryReader {
    fun readContents(
        sessionDirectoryPath: String,
        ibPriceTicksJsonlPath: String? = null
    ): SessionBundleContents {
        val dir = Path.of(sessionDirectoryPath)
        require(Files.isDirectory(dir)) { "Not a directory: $sessionDirectoryPath" }
        return SessionBundleContents(
            manifestJson = readFileIfExists(dir.resolve("manifest.json")),
            applicationJsonl = readFileIfExists(dir.resolve("application.jsonl")) ?: "",
            historicalJsonl = readFileIfExists(dir.resolve("historical.jsonl")) ?: "",
            pricesJsonl = readFileIfExists(dir.resolve("prices.jsonl")) ?: "",
            ibPriceTicksJsonl = ibPriceTicksJsonlPath?.let { path ->
                readFileIfExists(Path.of(path))
            }
        )
    }

    fun loadFromDirectory(
        sessionDirectoryPath: String,
        ibPriceTicksJsonlPath: String? = null
    ): Result<SessionBundle> {
        val contents = readContents(sessionDirectoryPath, ibPriceTicksJsonlPath)
        return SessionBundleLoader.load(contents)
    }

    private fun readFileIfExists(path: Path): String? {
        if (!Files.exists(path)) return null
        return Files.readString(path)
    }
}
