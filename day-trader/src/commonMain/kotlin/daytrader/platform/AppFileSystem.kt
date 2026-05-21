package daytrader.platform

expect object AppFileSystem {
    fun appDataDirectory(): String
    fun ensureAppDataDirectory()
    fun readText(fileName: String): String?
    fun writeTextAtomic(fileName: String, content: String)
}
