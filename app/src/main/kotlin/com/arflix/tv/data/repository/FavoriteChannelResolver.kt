package com.arflix.tv.data.repository

/**
 * Resolves favourite channels against the current lineup by NAME.
 *
 * Why this exists: Dispatcharr's M3U publishes the channel *number* as the tvg-id, and channel
 * ids are built from the tvg-id ("list_1:epg:1541"). When channels are removed lower in the
 * lineup every later number shifts, so a stored id silently starts pointing at whatever channel
 * inherited that number (2026-10-10: every number >= ~1400 moved down by 2, so the stored
 * Newsmax id 1543 became NewsNation, ESPN (1080p) 1423 became ESPN Deportes, FOX News 1526 became
 * FOX Weather, Dodgers 1489 became SportsGrid).
 *
 * The remembered channel NAME is the source of truth; the id is only a cache of "where that
 * name lives in the lineup right now". Pure and Android-free so it can be unit tested.
 */
object FavoriteChannelResolver {

    /** Exact comparison key: case/whitespace-insensitive, nothing else stripped. */
    fun exactKey(name: String): String = name.trim().lowercase()

    /**
     * Loose key: drops decoration ("US: ", "HD", "FHD", "4K"...). Useful when a stream's quality
     * label changes, but dangerous on its own: "ESPN HD" and "ESPN 4K UHD" both collapse to
     * "espn" while being two distinct live channels. Only ever used as a fallback after an exact
     * match failed, and only when it is unambiguous.
     */
    fun looseKey(name: String): String =
        name.lowercase()
            .replace(Regex("^(us[:|]|usa\\s*\\||uk[:|])\\s*"), "")
            .replace(Regex("\\b(fhd|uhd|hd|sd|4k|raw|h265|hevc)\\b"), "")
            .replace(Regex("[^a-z0-9]+"), "")
            .trim()

    /** A lineup snapshot: id -> name, plus name indexes. */
    class Lineup(entries: List<Pair<String, String>>) {
        val nameById: Map<String, String>
        val idsByExact: Map<String, List<String>>
        val idsByLoose: Map<String, List<String>>
        val size: Int get() = nameById.size
        /** Playlist prefixes ("list_1") present in this lineup. */
        val prefixes: Set<String>

        init {
            val byId = LinkedHashMap<String, String>(entries.size)
            val exact = LinkedHashMap<String, MutableList<String>>()
            val loose = LinkedHashMap<String, MutableList<String>>()
            entries.forEach { (id, name) ->
                if (id.isBlank() || byId.containsKey(id)) return@forEach
                byId[id] = name
                exactKey(name).takeIf { it.isNotEmpty() }?.let { exact.getOrPut(it) { mutableListOf() }.add(id) }
                looseKey(name).takeIf { it.isNotEmpty() }?.let { loose.getOrPut(it) { mutableListOf() }.add(id) }
            }
            nameById = byId
            prefixes = byId.keys.mapTo(HashSet()) { it.substringBefore(':') }
            idsByExact = exact
            idsByLoose = loose
        }

        fun isEmpty(): Boolean = nameById.isEmpty()

        /**
         * False for ids that don't come from this lineup at all (library/music channels, other
         * sources). Those are not renumbering victims and are passed through untouched.
         */
        fun owns(id: String): Boolean = id.contains(':') && id.substringBefore(':') in prefixes
    }

    data class Resolution(
        /** Ids to persist, in the original favourite order. */
        val ids: List<String>,
        /** id -> remembered name for [ids]. */
        val names: Map<String, String>,
        /**
         * Ids that are safe to display right now: each one's live channel matches its remembered
         * name (or it has no remembered name and exists). Never contains an id whose live channel
         * has a different name than the one the user favourited.
         */
        val visible: List<String>,
        val reanchored: Int,
        val dropped: Int,
    ) {
        val changed: Boolean get() = reanchored > 0 || dropped > 0
    }

    /** True when the live channel at [id] is the channel called [rememberedName]. */
    private fun liveMatches(lineup: Lineup, id: String, rememberedName: String): Boolean {
        val live = lineup.nameById[id] ?: return false
        return exactKey(live) == exactKey(rememberedName)
    }

    /**
     * Finds the id where [rememberedName] lives now, or null. [originalId] is the stored id and
     * [claimed] are ids already taken by other favourites.
     *
     * Exact name first. If no exact match exists, a loose match is accepted only when it is
     * unambiguous (one candidate), or when the channel at the original id is itself one of the
     * candidates (a pure quality-label change on the same channel). Ambiguous => null: showing
     * nothing beats showing ESPN 4K UHD for an ESPN HD favourite.
     */
    fun resolveName(
        lineup: Lineup,
        rememberedName: String,
        originalId: String? = null,
        claimed: Set<String> = emptySet(),
    ): String? {
        val exact = lineup.idsByExact[exactKey(rememberedName)].orEmpty().filter { it !in claimed }
        if (exact.isNotEmpty()) {
            return if (originalId != null && originalId in exact) originalId else exact.first()
        }
        val key = looseKey(rememberedName)
        if (key.isEmpty()) return null
        val loose = lineup.idsByLoose[key].orEmpty().filter { it !in claimed }
        return when {
            loose.isEmpty() -> null
            originalId != null && originalId in loose -> originalId
            loose.size == 1 -> loose.first()
            else -> null
        }
    }

    /**
     * Resolves stored favourites ([ids] + their remembered [names]) against [lineup].
     *
     * - An id whose live channel has the remembered name is kept.
     * - Otherwise the favourite is re-anchored to wherever its name lives now.
     * - A favourite that can't be resolved is dropped only when [dropUnresolved] (i.e. the lineup
     *   is a fresh network fetch); otherwise it is kept in storage but never made visible.
     * - A favourite with no remembered name (legacy) is kept as-is and visible if its id exists;
     *   there is nothing to validate it against.
     */
    fun resolve(
        ids: List<String>,
        names: Map<String, String>,
        lineup: Lineup,
        dropUnresolved: Boolean,
    ): Resolution {
        val distinctIds = ids.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (lineup.isEmpty()) {
            return Resolution(
                ids = distinctIds,
                names = names.filterKeys { it in distinctIds },
                visible = distinctIds,
                reanchored = 0,
                dropped = 0,
            )
        }

        // Pass 1: ids that are still trustworthy claim their slot first, so a favourite being
        // re-anchored can never steal an id that another favourite legitimately holds.
        val claimed = LinkedHashSet<String>()
        distinctIds.forEach { id ->
            val name = names[id]
            if (name != null && liveMatches(lineup, id, name)) claimed += id
        }

        val outIds = ArrayList<String>(distinctIds.size)
        val outNames = LinkedHashMap<String, String>()
        val visible = ArrayList<String>(distinctIds.size)
        var reanchored = 0
        var dropped = 0

        distinctIds.forEach { id ->
            val name = names[id]
            when {
                !lineup.owns(id) -> {
                    if (id !in outIds) {
                        outIds += id; visible += id
                        name?.let { outNames[id] = it }
                    }
                }
                name == null -> {
                    // Legacy entry without a name. Keep it; display it only if the id exists.
                    if (id in outIds) return@forEach
                    if (lineup.nameById.containsKey(id)) {
                        outIds += id; visible += id
                    } else if (dropUnresolved) {
                        dropped++
                    } else {
                        outIds += id
                    }
                }
                liveMatches(lineup, id, name) -> {
                    outIds += id; outNames[id] = name; visible += id
                }
                else -> {
                    val recovered = resolveName(lineup, name, originalId = id, claimed = claimed)
                    if (recovered != null && recovered !in outIds) {
                        claimed += recovered
                        outIds += recovered
                        outNames[recovered] = name
                        visible += recovered
                        if (recovered != id) reanchored++
                    } else if (dropUnresolved) {
                        dropped++
                    } else if (id !in outIds && id !in claimed) {
                        // Unresolvable against this (possibly stale) lineup: keep the record so
                        // a fresh lineup can recover it, but never display it.
                        outIds += id
                        outNames[id] = name
                    } else {
                        // Its stored id is held by a different favourite; nothing to keep.
                        dropped++
                    }
                }
            }
        }
        return Resolution(outIds, outNames, visible, reanchored, dropped)
    }

    /**
     * Resolves a single remembered channel (e.g. the last-watched channel). Returns the id to
     * use, [id] itself when it still matches or there is no name, or null when the channel at
     * [id] is a different channel and the name can't be found.
     */
    fun resolveSingle(id: String, rememberedName: String?, lineup: Lineup): String? {
        if (id.isBlank() || lineup.isEmpty() || rememberedName.isNullOrBlank() || !lineup.owns(id)) return id
        if (liveMatches(lineup, id, rememberedName)) return id
        return resolveName(lineup, rememberedName, originalId = id)
    }
}
