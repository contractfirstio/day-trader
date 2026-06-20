package daytrader.data.persistence

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.serialization.json.Json

class JsonDocumentReaderTest {
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = false
    }

    private val validDeployments = """
        {
          "deployments": []
        }
    """.trimIndent()

    @Test
    fun read_primaryFile_returnsPrimarySource() {
        val files = mapOf(AppDataFiles.DEPLOYMENTS to validDeployments)
        val result = JsonDocumentReader.read<DeploymentsDocument>(
            json = json,
            fileName = AppDataFiles.DEPLOYMENTS,
            backupFileName = AppDataFiles.DEPLOYMENTS_BACKUP,
            readText = files::get,
        )
        assertEquals(JsonDocumentReader.Source.PRIMARY, result.source)
        assertEquals(0, result.value?.deployments?.size)
    }

    @Test
    fun read_corruptPrimary_fallsBackToBackup() {
        val files = mapOf(
            AppDataFiles.DEPLOYMENTS to "{not-json",
            AppDataFiles.DEPLOYMENTS_BACKUP to validDeployments,
        )
        val result = JsonDocumentReader.read<DeploymentsDocument>(
            json = json,
            fileName = AppDataFiles.DEPLOYMENTS,
            backupFileName = AppDataFiles.DEPLOYMENTS_BACKUP,
            readText = files::get,
        )
        assertEquals(JsonDocumentReader.Source.BACKUP, result.source)
        assertEquals(0, result.value?.deployments?.size)
    }

    @Test
    fun read_missingPrimary_usesBackup() {
        val files = mapOf(AppDataFiles.DEPLOYMENTS_BACKUP to validDeployments)
        val result = JsonDocumentReader.read<DeploymentsDocument>(
            json = json,
            fileName = AppDataFiles.DEPLOYMENTS,
            backupFileName = AppDataFiles.DEPLOYMENTS_BACKUP,
            readText = files::get,
        )
        assertEquals(JsonDocumentReader.Source.BACKUP, result.source)
    }

    @Test
    fun read_missingBoth_returnsMissing() {
        val result = JsonDocumentReader.read<DeploymentsDocument>(
            json = json,
            fileName = AppDataFiles.DEPLOYMENTS,
            backupFileName = AppDataFiles.DEPLOYMENTS_BACKUP,
            readText = { null },
        )
        assertEquals(JsonDocumentReader.Source.MISSING, result.source)
        assertNull(result.value)
    }

    @Test
    fun read_corruptPrimaryAndBackup_returnsMissing() {
        val files = mapOf(
            AppDataFiles.DEPLOYMENTS to "{bad",
            AppDataFiles.DEPLOYMENTS_BACKUP to "[also-bad",
        )
        val result = JsonDocumentReader.read<DeploymentsDocument>(
            json = json,
            fileName = AppDataFiles.DEPLOYMENTS,
            backupFileName = AppDataFiles.DEPLOYMENTS_BACKUP,
            readText = files::get,
        )
        assertEquals(JsonDocumentReader.Source.MISSING, result.source)
        assertNull(result.value)
    }
}
