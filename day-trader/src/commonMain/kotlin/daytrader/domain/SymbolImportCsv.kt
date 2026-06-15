package daytrader.domain

data class SymbolImportRow(
    val symbol: String,
    val exchangeCode: String,
    val marketZoneId: String,
    val lineNumber: Int
)

data class SymbolImportParseError(
    val lineNumber: Int,
    val line: String,
    val message: String
)

data class SymbolImportParseResult(
    val rows: List<SymbolImportRow> = emptyList(),
    val errors: List<SymbolImportParseError> = emptyList()
) {
    val isValid: Boolean = rows.isNotEmpty() && errors.isEmpty()
}

object SymbolImportExchange {
    fun toMarketZoneId(exchange: String): String? = when (exchange.trim().uppercase()) {
        "US", "USA", "NASDAQ", "NYSE", "ARCA", "AMEX", "BATS", "SMART" -> RthMarketSessions.US.zoneId
        "UK", "GB", "GBP", "LSE", "LON", "LONDON", "EUR" -> RthMarketSessions.EUR.zoneId
        "HK", "HKG", "HKEX", "SEHK" -> RthMarketSessions.HK.zoneId
        else -> null
    }

    fun marketLabel(zoneId: String): String = RthMarketSessions.forZoneId(zoneId).label
}

object SymbolImportCsvParser {
    fun parse(text: String): SymbolImportParseResult {
        val rows = mutableListOf<SymbolImportRow>()
        val errors = mutableListOf<SymbolImportParseError>()
        val lines = text.lines()
        for ((index, rawLine) in lines.withIndex()) {
            val lineNumber = index + 1
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val parts = line.split(",").map { it.trim().removeSurrounding("\"") }
            if (parts.size < 2) {
                errors += SymbolImportParseError(lineNumber, rawLine, "Expected symbol,exchange")
                continue
            }
            val symbol = parts[0].trim().uppercase()
            val exchange = parts[1].trim()
            if (symbol.isBlank()) {
                errors += SymbolImportParseError(lineNumber, rawLine, "Symbol is blank")
                continue
            }
            if (exchange.isBlank()) {
                errors += SymbolImportParseError(lineNumber, rawLine, "Exchange is blank")
                continue
            }
            if (rows.isEmpty() && symbol.equals("SYMBOL", ignoreCase = true)) continue
            val zoneId = SymbolImportExchange.toMarketZoneId(exchange)
            if (zoneId == null) {
                errors += SymbolImportParseError(
                    lineNumber,
                    rawLine,
                    "Unknown exchange '$exchange' (use US, UK, or HK)"
                )
                continue
            }
            rows += SymbolImportRow(
                symbol = symbol,
                exchangeCode = exchange.uppercase(),
                marketZoneId = zoneId,
                lineNumber = lineNumber
            )
        }
        return SymbolImportParseResult(rows = rows, errors = errors)
    }
}
