package com.arflix.tv.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FavoriteChannelResolverTest {

    private fun id(n: Int) = "list_1:epg:$n"

    /** Lineup after the 2026-10-10 renumbering (every number >= ~1400 moved down by 2). */
    private val lineupAfterShift = FavoriteChannelResolver.Lineup(
        listOf(
            id(1419) to "ESPN 4K UHD",
            id(1421) to "ESPN (1080p)",
            id(1423) to "ESPN Deportes",
            id(1471) to "NFL Network",
            id(1487) to "Spectrum SportsNet LA Dodgers",
            id(1489) to "SportsGrid",
            id(1524) to "FOX News",
            id(1526) to "FOX Weather",
            id(1541) to "Newsmax",
            id(1543) to "NewsNation",
            id(4679) to "NFL RedZone",
        ),
    )

    /** What was stored (and on the sync server) before the fix: the pre-shift numbers. */
    private val staleIds = listOf(id(1543), id(1423), id(1526), id(1489))
    private val staleNames = mapOf(
        id(1543) to "Newsmax",
        id(1423) to "ESPN (1080p)",
        id(1526) to "FOX News",
        id(1489) to "Spectrum SportsNet LA Dodgers",
    )

    @Test
    fun `todays shift resolves every favourite to its channel's new number`() {
        val r = FavoriteChannelResolver.resolve(staleIds, staleNames, lineupAfterShift, dropUnresolved = true)

        assertEquals(listOf(id(1541), id(1421), id(1524), id(1487)), r.ids)
        assertEquals(r.ids, r.visible)
        assertEquals(
            mapOf(
                id(1541) to "Newsmax",
                id(1421) to "ESPN (1080p)",
                id(1524) to "FOX News",
                id(1487) to "Spectrum SportsNet LA Dodgers",
            ),
            r.names,
        )
        assertEquals(4, r.reanchored)
        assertEquals(0, r.dropped)
    }

    @Test
    fun `same result against a cached lineup, and nothing is ever dropped from cache`() {
        val r = FavoriteChannelResolver.resolve(staleIds, staleNames, lineupAfterShift, dropUnresolved = false)
        assertEquals(listOf(id(1541), id(1421), id(1524), id(1487)), r.ids)

        val gone = FavoriteChannelResolver.resolve(
            listOf(id(9999)), mapOf(id(9999) to "Some Removed Channel"), lineupAfterShift, dropUnresolved = false,
        )
        assertEquals(listOf(id(9999)), gone.ids) // kept for a fresh lineup to decide
        assertTrue(gone.visible.isEmpty()) // but never shown
    }

    @Test
    fun `display never shows the channel that inherited the old number`() {
        val r = FavoriteChannelResolver.resolve(staleIds, staleNames, lineupAfterShift, dropUnresolved = false)
        val wrong = setOf(id(1543), id(1423), id(1526), id(1489)) // NewsNation, Deportes, Weather, SportsGrid
        assertTrue(r.visible.none { it in wrong })
        r.visible.forEach { v -> assertEquals(r.names[v], lineupAfterShift.nameById[v]) }
    }

    @Test
    fun `cloud restore with stale ids and a stale-id favourite colliding with a real one`() {
        // Server payload restored verbatim: Newsmax's stale id 1543 is NewsNation's real id,
        // and NewsNation is ALSO a favourite (at its correct id). Both must survive, correctly.
        val restoredIds = listOf(id(1489), id(1423), id(1543), id(1526), id(1471))
        val restoredNames = mapOf(
            id(1489) to "Spectrum SportsNet LA Dodgers",
            id(1423) to "ESPN (1080p)",
            id(1543) to "Newsmax",
            id(1526) to "FOX News",
            id(1471) to "NFL Network",
        )
        val r = FavoriteChannelResolver.resolve(restoredIds, restoredNames, lineupAfterShift, dropUnresolved = true)
        assertEquals(listOf(id(1487), id(1421), id(1541), id(1524), id(1471)), r.ids)
        r.ids.forEach { assertEquals(r.names[it], lineupAfterShift.nameById[it]) }
    }

    @Test
    fun `correct favourite set is left exactly as is`() {
        val ids = listOf(id(1524), id(1541), id(1543), id(1471), id(4679), id(1487), id(1421))
        val names = mapOf(
            id(1524) to "FOX News", id(1541) to "Newsmax", id(1543) to "NewsNation",
            id(1471) to "NFL Network", id(4679) to "NFL RedZone",
            id(1487) to "Spectrum SportsNet LA Dodgers", id(1421) to "ESPN (1080p)",
        )
        val r = FavoriteChannelResolver.resolve(ids, names, lineupAfterShift, dropUnresolved = true)
        assertFalse(r.changed)
        assertEquals(ids, r.ids)
        assertEquals(ids, r.visible)
        assertEquals(names, r.names)
    }

    @Test
    fun `ESPN HD never re-anchors onto ESPN 4K UHD`() {
        val lineup = FavoriteChannelResolver.Lineup(
            listOf(id(10) to "ESPN 4K UHD", id(11) to "ESPN HD", id(12) to "Other"),
        )
        // ESPN HD's stored id now holds "Other"; exact name wins even though 4K UHD comes first.
        val r = FavoriteChannelResolver.resolve(listOf(id(12)), mapOf(id(12) to "ESPN HD"), lineup, true)
        assertEquals(listOf(id(11)), r.ids)

        // ESPN HD gone entirely: loose key "espn" is ambiguous only if two candidates; here only
        // 4K UHD remains -> but an id holding a *different* exact name must not be trusted either.
        val ambiguous = FavoriteChannelResolver.Lineup(
            listOf(id(10) to "ESPN 4K UHD", id(11) to "ESPN FHD", id(12) to "Other"),
        )
        val r2 = FavoriteChannelResolver.resolve(listOf(id(12)), mapOf(id(12) to "ESPN HD"), ambiguous, false)
        assertTrue(r2.visible.isEmpty()) // two loose candidates -> show nothing rather than guess
    }

    @Test
    fun `quality label change on the same id keeps the favourite`() {
        val lineup = FavoriteChannelResolver.Lineup(listOf(id(5) to "NFL Network FHD", id(6) to "NFL Network 4K"))
        val r = FavoriteChannelResolver.resolve(listOf(id(5)), mapOf(id(5) to "NFL Network SD"), lineup, true)
        assertEquals(listOf(id(5)), r.ids)
        assertEquals(listOf(id(5)), r.visible)
    }

    @Test
    fun `ids from outside the lineup (library channels) pass through untouched`() {
        val r = FavoriteChannelResolver.resolve(
            listOf("shows:abc", id(1543)),
            mapOf("shows:abc" to "My Shows", id(1543) to "Newsmax"),
            lineupAfterShift,
            dropUnresolved = true,
        )
        assertEquals(listOf("shows:abc", id(1541)), r.ids)
        assertEquals(listOf("shows:abc", id(1541)), r.visible)
    }

    @Test
    fun `last watched channel follows its name too`() {
        assertEquals(id(1541), FavoriteChannelResolver.resolveSingle(id(1543), "Newsmax", lineupAfterShift))
        assertEquals(id(1543), FavoriteChannelResolver.resolveSingle(id(1543), "NewsNation", lineupAfterShift))
        assertNull(FavoriteChannelResolver.resolveSingle(id(1543), "Gone Channel", lineupAfterShift))
        // No remembered name (older session): nothing to check against.
        assertEquals(id(1543), FavoriteChannelResolver.resolveSingle(id(1543), "", lineupAfterShift))
    }

    @Test
    fun `empty lineup changes nothing`() {
        val r = FavoriteChannelResolver.resolve(
            staleIds, staleNames, FavoriteChannelResolver.Lineup(emptyList()), dropUnresolved = true,
        )
        assertEquals(staleIds, r.ids)
        assertFalse(r.changed)
    }
}
