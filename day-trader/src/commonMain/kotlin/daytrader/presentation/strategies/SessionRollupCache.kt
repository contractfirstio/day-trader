package daytrader.presentation.strategies

import daytrader.domain.SessionRollups
import daytrader.domain.StrategySession
import daytrader.domain.rollups

/**
 * Caches [SessionRollups] per scope and session-history fingerprint so list/summary mappers
 * do not recompute rollups for the same deployment on every UI refresh.
 */
class SessionRollupCache {
    private data class Key(val scope: String, val asOfSessionDate: String, val fingerprint: Long)

    private val cache = mutableMapOf<Key, SessionRollups>()

    fun rollups(scope: String, sessions: List<StrategySession>, asOfSessionDate: String): SessionRollups {
        val key = Key(scope, asOfSessionDate, fingerprint(sessions))
        return cache.getOrPut(key) { sessions.rollups(asOfSessionDate) }
    }

    fun rollupsForDeployment(
        deploymentId: String,
        closedSessions: List<StrategySession>,
        asOfSessionDate: String,
    ): SessionRollups = rollups(deploymentId, closedSessions, asOfSessionDate)

    fun rollupsForSummary(
        deploymentIds: List<String>,
        closedSessions: List<StrategySession>,
        asOfSessionDate: String,
    ): SessionRollups = rollups(
        scope = "summary:${deploymentIds.sorted().joinToString(",")}",
        sessions = closedSessions,
        asOfSessionDate = asOfSessionDate,
    )

    /** Drops cached rollups for [deploymentIds] and any summary scope that includes them. */
    fun invalidateDeployments(deploymentIds: Set<String>) {
        if (deploymentIds.isEmpty()) return
        cache.keys.removeIf { key ->
            deploymentIds.any { deploymentId ->
                key.scope == deploymentId ||
                    key.scope.startsWith("$deploymentId:") ||
                    (key.scope.startsWith(SUMMARY_SCOPE_PREFIX) &&
                        summaryScopeIncludes(deploymentId, key.scope))
            }
        }
    }

    fun clear() {
        cache.clear()
    }

    companion object {
        private const val SUMMARY_SCOPE_PREFIX = "summary:"

        fun fingerprint(sessions: List<StrategySession>): Long {
            var hash = 2166136261L
            for (session in sessions) {
                hash = fnvMix(hash, session.id.hashCode())
                hash = fnvMix(hash, session.status.hashCode())
                hash = fnvMix(hash, session.date.hashCode())
                hash = fnvMix(hash, session.pnl.toRawBits())
                hash = fnvMix(hash, session.trades)
                hash = fnvMix(hash, session.positionOpened?.hashCode() ?: 0)
                hash = fnvMix(hash, session.sessionTrades.size)
                hash = fnvMix(hash, session.touchTurnMilestones?.positionOpenedAt?.hashCode() ?: 0)
                hash = fnvMix(
                    hash,
                    session.touchTurnRunRecord?.decision?.outcome?.hashCode() ?: 0
                )
            }
            return hash
        }

        private fun summaryScopeIncludes(deploymentId: String, scope: String): Boolean {
            if (!scope.startsWith(SUMMARY_SCOPE_PREFIX)) return false
            return scope.removePrefix(SUMMARY_SCOPE_PREFIX)
                .split(',')
                .any { it == deploymentId }
        }

        private fun fnvMix(hash: Long, value: Int): Long =
            (hash xor value.toLong()) * 16777619L

        private fun fnvMix(hash: Long, value: Long): Long =
            (hash xor value) * 16777619L
    }
}
