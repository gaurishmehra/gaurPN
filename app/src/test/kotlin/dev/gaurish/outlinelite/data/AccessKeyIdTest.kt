package dev.gaurish.outlinelite.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessKeyIdTest {

    private fun key(password: String, host: String = "198.51.100.1") =
        AccessKey(
            name = "test",
            host = host,
            port = 8388,
            method = "chacha20-ietf-poly1305",
            password = password,
        )

    @Test
    fun samePasswordProducesSameId() {
        assertEquals(key("hunter2").computeStableId(), key("hunter2").computeStableId())
        assertEquals(key("hunter2").id, key("hunter2").computeStableId())
    }

    @Test
    fun differentPasswordsProduceDifferentIds() {
        assertNotEquals(key("hunter2").computeStableId(), key("hunter3").computeStableId())
    }

    @Test
    fun hashCodeCollidingPasswordsGetDistinctStableIds() {
        // "Aa" and "BB" are the classic Java String.hashCode collision pair:
        // both hash to 2112, so the legacy id formula could not tell them apart.
        val aa = key("Aa")
        val bb = key("BB")
        assertEquals(aa.legacyId(), bb.legacyId())
        assertNotEquals(aa.computeStableId(), bb.computeStableId())
    }

    @Test
    fun stableIdIsPrefixedAndTruncatedHex() {
        val id = key("hunter2").computeStableId()
        assertTrue("id must keep the host:port|method| prefix, got: $id", id.startsWith("198.51.100.1:8388|chacha20-ietf-poly1305|"))
        val hash = id.substringAfterLast('|')
        assertEquals("id must carry a 16-char hex digest", 16, hash.length)
        assertTrue("hex digest must be lowercase hex", hash.all { it in "0123456789abcdef" })
    }

    @Test
    fun migrationRemapsLegacyActiveIdToStableId() {
        val first = key("hunter2")
        val second = key("other", host = "10.0.0.5")
        val keys = listOf(first, second)
        val legacyActive = first.legacyId()

        val migration = migrateAccessKeyIds(keys, legacyActive)

        assertEquals(first.computeStableId(), migration.activeId)
        assertEquals(keys, migration.keys)
        assertTrue(migration.changed)
    }

    @Test
    fun migrationKeepsAlreadyStableActiveId() {
        val first = key("hunter2")
        val second = key("other", host = "10.0.0.5")
        val keys = listOf(first, second)

        val migration = migrateAccessKeyIds(keys, first.computeStableId())

        assertEquals(first.computeStableId(), migration.activeId)
        assertTrue(!migration.changed)
    }

    @Test
    fun migrationLeavesUnknownActiveIdAlone() {
        val first = key("hunter2")
        val migration = migrateAccessKeyIds(listOf(first), "ghost-key-id")

        assertEquals("ghost-key-id", migration.activeId)
        assertTrue(!migration.changed)
    }

    @Test
    fun migrationHandlesNullActiveId() {
        val first = key("hunter2")
        val migration = migrateAccessKeyIds(listOf(first), null)

        assertEquals(null, migration.activeId)
        assertTrue(!migration.changed)
    }

    @Test
    fun migrationPicksTheKeyTheLegacyIdPointedAt() {
        val first = key("hunter2")
        val second = key("Aa") // collides with "BB" under the legacy formula
        val third = key("BB")
        val keys = listOf(first, second, third)

        val migration = migrateAccessKeyIds(keys, second.legacyId())

        // The legacy selection was ambiguous ("Aa"/"BB" share an id); the
        // remap resolves to the first key whose legacy id matches.
        assertEquals(second.computeStableId(), migration.activeId)
    }
}
