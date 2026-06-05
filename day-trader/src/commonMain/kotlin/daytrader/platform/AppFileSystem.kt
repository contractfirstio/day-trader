package daytrader.platform

import daytrader.gateway.BrokerKind

expect object AppFileSystem {
    /** Selects the on-disk scope for this process (IB vs emulator). Call before any persistence I/O. */
    fun configureDataScope(kind: BrokerKind)

    fun currentDataScope(): BrokerKind

    fun appDataDirectory(): String
    /** App data root before broker scope segment (parent of `emulator/`, `paper-live-ib/`, etc.). */
    fun applicationDataRoot(): String
    fun ensureAppDataDirectory()
    fun readText(fileName: String): String?
    fun writeTextAtomic(fileName: String, content: String)
    /** Appends a single line (caller supplies trailing newline if needed). */
    fun appendLine(fileName: String, line: String)
    fun deleteIfExists(fileName: String)
    /** Absolute path for a relative app-data file (e.g. diagnostics). */
    fun dataFilePath(fileName: String): String
}
