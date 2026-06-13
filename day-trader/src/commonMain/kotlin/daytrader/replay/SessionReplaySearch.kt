package daytrader.replay

/**
 * Wildcard search for replay session picker entries.
 *
 * Supports `*` (any substring) and `?` (single character). Without wildcards, matches
 * case-insensitively against symbol, company name, deployment id, session id, and label.
 */
object SessionReplaySearch {
    fun matches(
        query: String,
        symbol: String?,
        companyName: String?,
        deploymentId: String,
        sessionId: String,
        label: String
    ): Boolean {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return true
        return searchableFields(symbol, companyName, deploymentId, sessionId, label)
            .any { field -> wildcardMatches(trimmed, field) }
    }

    fun wildcardMatches(query: String, candidate: String): Boolean {
        val pattern = query.trim()
        if (pattern.isEmpty()) return true
        val text = candidate.trim()
        if (text.isEmpty()) return false
        return if (pattern.contains('*') || pattern.contains('?')) {
            wildcardToRegex(pattern).containsMatchIn(text)
        } else {
            text.contains(pattern, ignoreCase = true)
        }
    }

    internal fun wildcardToRegex(pattern: String): Regex {
        val builder = StringBuilder("(?i)")
        for (ch in pattern) {
            when (ch) {
                '*' -> builder.append(".*")
                '?' -> builder.append('.')
                else -> {
                    if (ch in ".[]{}()+^$\\|") builder.append('\\')
                    builder.append(ch)
                }
            }
        }
        return Regex(builder.toString())
    }

    private fun searchableFields(
        symbol: String?,
        companyName: String?,
        deploymentId: String,
        sessionId: String,
        label: String
    ): List<String> = buildList {
        symbol?.let { add(it) }
        companyName?.let { add(it) }
        add(deploymentId)
        add(sessionId)
        add(label)
    }
}
