package daytrader.platform

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

actual object AppFileSystem {
    private const val APP_FOLDER_NAME = "Day Trader"
    private val envOverride = System.getenv("DAY_TRADER_DATA_DIR")

    actual fun appDataDirectory(): String {
        if (!envOverride.isNullOrBlank()) return envOverride
        val home = System.getProperty("user.home") ?: error("user.home is not set")
        val os = System.getProperty("os.name").orEmpty().lowercase()
        return when {
            os.contains("mac") || os.contains("darwin") ->
                "$home/Library/Application Support/$APP_FOLDER_NAME"
            os.contains("win") -> {
                val appData = System.getenv("APPDATA") ?: "$home\\AppData\\Roaming"
                "$appData\\$APP_FOLDER_NAME"
            }
            else -> "$home/.local/share/day-trader"
        }
    }

    actual fun ensureAppDataDirectory() {
        Files.createDirectories(Path.of(appDataDirectory()))
    }

    actual fun readText(fileName: String): String? {
        val path = Path.of(appDataDirectory(), fileName)
        if (!Files.exists(path)) return null
        return Files.readString(path)
    }

    actual fun writeTextAtomic(fileName: String, content: String) {
        ensureAppDataDirectory()
        val target = Path.of(appDataDirectory(), fileName)
        val temp = Path.of(appDataDirectory(), "$fileName.tmp")
        Files.writeString(
            temp,
            content,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE
        )
        Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }
}
