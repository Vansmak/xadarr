package com.arflix.tv.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FavoritesSyncPolicyTest {
    private fun id(n: Int) = "list_1:epg:$n"

    private val serverSeven = FavoritesSyncPolicy.Favs(
        ids = listOf(id(1524), id(1541), id(1543), id(1471), id(4679), id(1487), id(1421)),
        names = mapOf(
            id(1524) to "FOX News", id(1541) to "Newsmax", id(1543) to "NewsNation",
            id(1471) to "NFL Network", id(4679) to "NFL RedZone",
            id(1487) to "Spectrum SportsNet LA Dodgers", id(1421) to "ESPN (1080p)",
        ),
        updatedAt = 2_000L,
    )

    /** The SHIELD's stale copy (pre-renumbering ids, 4 favourites). */
    private val staleFour = FavoritesSyncPolicy.Favs(
        ids = listOf(id(1489), id(1423), id(1543), id(1526)),
        names = mapOf(
            id(1543) to "Newsmax", id(1423) to "ESPN (1080p)",
            id(1489) to "Spectrum SportsNet LA Dodgers", id(1526) to "FOX News",
        ),
        updatedAt = 1_000L,
    )

    @Test
    fun `stale device push after a newer server write leaves server favourites unchanged`() {
        // Device last took favourites from the server at t=900, never edited one since; the
        // server was corrected at t=2000. Whatever triggers its push, it must send the 7 back.
        val sent = FavoritesSyncPolicy.forPush(staleFour, localEditedAt = 0L, localSyncedAt = 900L, server = serverSeven)
        assertEquals(serverSeven, sent)
    }

    @Test
    fun `older local edit loses to a newer server write`() {
        // User toggled at t=1500 on this device (unpushed), but the server got a newer edit at 2000.
        val sent = FavoritesSyncPolicy.forPush(staleFour, localEditedAt = 1_500L, localSyncedAt = 900L, server = serverSeven)
        assertEquals(serverSeven, sent)
        assertTrue(FavoritesSyncPolicy.shouldApplyIncoming(2_000L, localEditedAt = 1_500L, localSyncedAt = 900L))
    }

    @Test
    fun `device with a newer local edit wins`() {
        val local = serverSeven.copy(ids = serverSeven.ids.dropLast(1), names = serverSeven.names - id(1421))
        val sent = FavoritesSyncPolicy.forPush(local, localEditedAt = 3_000L, localSyncedAt = 2_100L, server = serverSeven)
        assertEquals(local.ids, sent.ids)
        assertEquals(3_000L, sent.updatedAt)
        // And a restore of the older server copy must not undo it before it is pushed.
        assertFalse(FavoritesSyncPolicy.shouldApplyIncoming(2_000L, localEditedAt = 3_000L, localSyncedAt = 2_100L))
    }

    @Test
    fun `restore applies when there is no pending local edit`() {
        assertTrue(FavoritesSyncPolicy.shouldApplyIncoming(1_000L, localEditedAt = 0L, localSyncedAt = 0L))
        // Edit already superseded by a later restore: not pending any more.
        assertTrue(FavoritesSyncPolicy.shouldApplyIncoming(500L, localEditedAt = 3_000L, localSyncedAt = 4_000L))
    }

    @Test
    fun `fresh install with nothing edited sends the server's set`() {
        val empty = FavoritesSyncPolicy.Favs(emptyList(), emptyMap(), 0L)
        assertEquals(serverSeven, FavoritesSyncPolicy.forPush(empty, 0L, 0L, serverSeven))
    }

    @Test
    fun `no server copy yet - local is sent`() {
        val sent = FavoritesSyncPolicy.forPush(serverSeven, 2_500L, 0L, server = null)
        assertEquals(serverSeven.ids, sent.ids)
        assertEquals(2_500L, sent.updatedAt)
    }
}
