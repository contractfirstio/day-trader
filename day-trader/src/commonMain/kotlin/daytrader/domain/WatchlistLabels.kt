package daytrader.domain

object WatchlistLabels {
    fun normalizeName(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return null
        return trimmed
            .split(Regex("\\s+"))
            .joinToString(" ") { word ->
                word.lowercase().replaceFirstChar { char ->
                    if (char.isLowerCase()) char.titlecase() else char.toString()
                }
            }
    }

    fun findById(labels: List<WatchlistLabel>, id: String): WatchlistLabel? =
        labels.find { it.id == id }

    fun findByName(labels: List<WatchlistLabel>, name: String): WatchlistLabel? =
        labels.find { it.name.equals(name, ignoreCase = true) }

    fun sorted(labels: List<WatchlistLabel>): List<WatchlistLabel> =
        labels.sortedBy { it.name.lowercase() }

    fun resolveLabels(labelIds: List<String>, labels: List<WatchlistLabel>): List<WatchlistLabel> =
        labelIds.mapNotNull { id -> findById(labels, id) }

    fun ensureLabel(
        labels: MutableList<WatchlistLabel>,
        name: String,
        nowEpochMs: Long = System.currentTimeMillis()
    ): WatchlistLabel {
        findByName(labels, name)?.let { return it }
        val normalized = normalizeName(name) ?: error("Label name is blank")
        val label = WatchlistLabel(
            id = newWatchlistLabelId(),
            name = normalized,
            createdAtEpochMs = nowEpochMs
        )
        labels.add(label)
        return label
    }

    fun combinedRegistry(
        watchlistLabels: List<WatchlistLabel>,
        pendingLabels: List<WatchlistLabel> = emptyList()
    ): List<WatchlistLabel> {
        val byName = linkedMapOf<String, WatchlistLabel>()
        watchlistLabels.forEach { label ->
            byName.putIfAbsent(label.name.lowercase(), label)
        }
        pendingLabels.forEach { label ->
            byName.putIfAbsent(label.name.lowercase(), label)
        }
        return sorted(byName.values.toList())
    }

    fun availableLabels(all: List<WatchlistLabel>, assignedIds: List<String>): List<WatchlistLabel> =
        all.filterNot { assignedIds.contains(it.id) }

    fun filterSuggestions(
        candidates: List<WatchlistLabel>,
        query: String,
        limit: Int = 12
    ): List<WatchlistLabel> {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return candidates.take(limit)
        val prefixMatches = candidates.filter { it.name.startsWith(trimmed, ignoreCase = true) }
        if (prefixMatches.isNotEmpty()) return prefixMatches.take(limit)
        return candidates.filter { it.name.contains(trimmed, ignoreCase = true) }.take(limit)
    }

    fun entryHasLabel(entry: WatchlistEntry, labelId: String): Boolean =
        entry.labelIds.contains(labelId)

    fun countForLabel(entries: List<WatchlistEntry>, labelId: String): Int =
        entries.count { entryHasLabel(it, labelId) }

    fun countUngrouped(entries: List<WatchlistEntry>): Int =
        entries.count { it.labelIds.isEmpty() }

    fun mergeLabelId(existing: List<String>, labelId: String): List<String> =
        if (existing.contains(labelId)) existing else existing + labelId

    fun removeLabelId(existing: List<String>, labelId: String): List<String> =
        existing.filterNot { it == labelId }

    fun mergePendingLabels(
        watchlistLabels: List<WatchlistLabel>,
        pendingLabels: List<WatchlistLabel>
    ): List<WatchlistLabel> {
        val merged = watchlistLabels.toMutableList()
        pendingLabels.forEach { pending ->
            if (findByName(merged, pending.name) == null && findById(merged, pending.id) == null) {
                merged.add(pending)
            }
        }
        return sorted(merged)
    }

    fun remapAssignedIds(
        assignedIds: List<String>,
        watchlistLabels: List<WatchlistLabel>,
        pendingLabels: List<WatchlistLabel>
    ): List<String> {
        val registry = combinedRegistry(watchlistLabels, pendingLabels)
        return assignedIds.mapNotNull { id ->
            findById(registry, id)?.id
                ?: pendingLabels.find { it.id == id }?.let { pending ->
                    findByName(registry, pending.name)?.id
                }
        }.distinct()
    }
}
