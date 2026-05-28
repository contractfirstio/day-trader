package daytrader.diagnostics

/** Prefixes every console line with `[tag] ISO-timestamp` for cross-log correlation. */
object TimestampedConsoleLog {
    fun line(tag: String, message: String) {
        println("[$tag] ${LogTimestamps.now().at} $message")
    }

    fun multiline(tag: String, message: String) {
        val stamp = LogTimestamps.now().at
        message.lineSequence().forEach { line ->
            println("[$tag] $stamp $line")
        }
    }
}
