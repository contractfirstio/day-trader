package daytrader.platform

import java.awt.FileDialog
import java.io.File

object DesktopFolderPicker {
    fun pickDirectory(title: String = "Select session folder"): String? {
        val dialog = FileDialog(null as java.awt.Frame?, title, FileDialog.LOAD)
        dialog.isVisible = true
        val directory = dialog.directory ?: return null
        val file = dialog.file
        return if (file.isNullOrBlank()) {
            directory.trimEnd('/', '\\')
        } else {
            File(directory, file).absolutePath
        }
    }
}
