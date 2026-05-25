package daytrader.platform

import daytrader.gateway.BrokerKind

expect object AppFileSystem {
    /** Selects the on-disk scope for this process (IB vs emulator). Call before any persistence I/O. */
    fun configureDataScope(kind: BrokerKind)

    fun appDataDirectory(): String
    fun ensureAppDataDirectory()
    fun readText(fileName: String): String?
    fun writeTextAtomic(fileName: String, content: String)
    fun deleteIfExists(fileName: String)
}
