package daytrader.data.persistence

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer

/**
 * Reads JSON documents with optional backup fallback when the primary file is missing or corrupt.
 */
internal object JsonDocumentReader {
    enum class Source {
        PRIMARY,
        BACKUP,
        MISSING,
    }

    data class Result<T>(
        val value: T?,
        val source: Source,
    )

    inline fun <reified T> read(
        json: Json,
        fileName: String,
        backupFileName: String?,
        readText: (String) -> String?,
    ): Result<T> {
        decodePrimary<T>(json, fileName, readText)?.let { document ->
            return Result(document, Source.PRIMARY)
        }
        if (backupFileName != null) {
            decode<T>(json, readText(backupFileName))?.let { document ->
                return Result(document, Source.BACKUP)
            }
        }
        return Result(null, Source.MISSING)
    }

    inline fun <reified T> decodePrimary(
        json: Json,
        fileName: String,
        readText: (String) -> String?,
    ): T? = decode(json, readText(fileName))

    inline fun <reified T> decode(json: Json, raw: String?): T? {
        if (raw == null) return null
        return try {
            json.decodeFromString(serializer<T>(), raw)
        } catch (_: SerializationException) {
            null
        }
    }
}
