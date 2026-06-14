package daytrader.platform

import java.awt.FileDialog
import java.io.File

actual object PlatformFilePicker {
    actual fun pickCsvFile(title: String): String? {
        val dialog = FileDialog(null as java.awt.Frame?, title, FileDialog.LOAD)
        dialog.file = "*.csv"
        dialog.isVisible = true
        val directory = dialog.directory ?: return null
        val file = dialog.file ?: return null
        return File(directory, file).absolutePath
    }

    actual fun readText(path: String): String? =
        runCatching { File(path).readText() }.getOrNull()
}
