package dev.gaurish.outlinelite.transfer

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the v3.2.1 browse, browse_listing, and fetch inner payloads. The Go
 * desktop client builds and parses the same shapes, so the literals here are
 * the cross-platform contract.
 */
class BrowseCodecTest {

    private val requestId = "req-0011223344556677"
    private val requesterId = "desktop-abc123"
    private val requesterName = "Gaurish Laptop"
    private val targetId = "android-f3dd4a01"
    private val expiresTs = 1_767_225_600_000L
    private val grant = "cd232c541e09733b88b73687f7b4806e7cf8ad22995adf2b5fe7542bc77506d6"
    private val ts = 1_767_225_300_000L
    private val now = expiresTs - 60_000L

    // ------------------------------------------------------------- browse

    private fun validBrowse(): JSONObject = JSONObject()
        .put("request_id", requestId)
        .put("requester_id", requesterId)
        .put("requester_name", requesterName)
        .put("target_id", targetId)
        .put("path", "Pictures/Screenshots")
        .put("ts", ts)

    @Test
    fun browseParsesEveryField() {
        val parsed = RequestCodec.parseBrowse(validBrowse())
        assertNotNull(parsed)
        assertEquals(requestId, parsed!!.requestId)
        assertEquals(requesterId, parsed.requesterId)
        assertEquals(requesterName, parsed.requesterName)
        assertEquals(targetId, parsed.targetId)
        assertEquals("Pictures/Screenshots", parsed.path)
        assertEquals(ts, parsed.ts)
        assertNull(parsed.originId)
        assertEquals(requesterId, parsed.trustId)
    }

    @Test
    fun browseRootPathIsEmptyAndValid() {
        val parsed = RequestCodec.parseBrowse(validBrowse().put("path", ""))
        assertNotNull(parsed)
        assertEquals("", parsed!!.path)
    }

    @Test
    fun browseRequiresAPathField() {
        val missing = JSONObject(validBrowse().toString()).apply { remove("path") }
        assertNull(RequestCodec.parseBrowse(missing))
        assertNull(RequestCodec.parseBrowse(validBrowse().put("path", JSONObject.NULL)))
        assertNull(RequestCodec.parseBrowse(validBrowse().put("path", 42)))
    }

    @Test
    fun browsePathShapeIsPinned() {
        val badPaths = listOf(
            "/Pictures",
            "Pictures/",
            "Pictures//Screenshots",
            "..",
            "../Pictures",
            "Pictures/../Movies",
            "Pictures/./x",
            "Pictures/\u0000x",
            "Pictures/\u001Fx",
            "Pictures/\u007Fx",
            "x".repeat(RequestCodec.MAX_PATH_CHARS + 1),
        )
        for (bad in badPaths) {
            assertNull("accepted path: $bad", RequestCodec.parseBrowse(validBrowse().put("path", bad)))
        }
        // Exactly the cap is still fine.
        val max = "P".repeat(RequestCodec.MAX_PATH_CHARS)
        assertNotNull(RequestCodec.parseBrowse(validBrowse().put("path", max)))
    }

    @Test
    fun browseOriginIdDrivesTheTrustId() {
        val desktop = JSONObject(validBrowse().toString())
            .put("requester_id", "reqdev-4242-a1b2")
            .put("origin_id", "laptop-1")
        val parsed = RequestCodec.parseBrowse(desktop)
        assertNotNull(parsed)
        assertEquals("reqdev-4242-a1b2", parsed!!.requesterId)
        assertEquals("laptop-1", parsed.trustId)
        assertNull(RequestCodec.parseBrowse(validBrowse().put("origin_id", "two words")))
    }

    @Test
    fun buildBrowseRoundTripsAndOmitsAbsentOrigin() {
        val built = RequestCodec.buildBrowse(
            requestId = requestId,
            requesterId = requesterId,
            requesterName = requesterName,
            targetId = targetId,
            path = "Movies",
            ts = ts,
        )
        assertFalse(built.has("origin_id"))
        val parsed = RequestCodec.parseBrowse(built)
        assertEquals("Movies", parsed!!.path)
        assertEquals(ts, parsed.ts)

        val withOrigin = RequestCodec.buildBrowse(
            requestId = requestId,
            requesterId = "reqdev-4242-a1b2",
            originId = "laptop-1",
            requesterName = requesterName,
            targetId = targetId,
            path = "",
            ts = ts,
        )
        assertEquals("laptop-1", RequestCodec.parseBrowse(withOrigin)!!.trustId)
    }

    @Test
    fun browseRejectsMissingAndMalformedIdentityFields() {
        for (field in listOf("request_id", "requester_id", "requester_name", "target_id", "ts")) {
            val json = JSONObject(validBrowse().toString()).apply { remove(field) }
            assertNull("accepted without $field", RequestCodec.parseBrowse(json))
        }
        assertNull(RequestCodec.parseBrowse(validBrowse().put("request_id", "req-nope")))
        assertNull(RequestCodec.parseBrowse(validBrowse().put("target_id", "two words")))
        assertNull(RequestCodec.parseBrowse(validBrowse().put("ts", 0)))
    }

    // ------------------------------------------------------ browse listing

    private fun entry(name: String, size: Long = 10L, modified: Long = ts, dir: Boolean = false) =
        BrowseEntry(name, size, modified, dir)

    @Test
    fun listingRoundTripsEntriesInOrder() {
        val entries = listOf(
            entry("Screenshots", size = 0L, modified = 0L, dir = true),
            entry("Screenshot_03.png", size = 2_048L),
            entry("notes.txt", size = 12L),
        )
        val built = RequestCodec.buildBrowseListing(
            requestId = requestId,
            path = "Pictures",
            entries = entries,
            ts = ts,
        )
        val parsed = RequestCodec.parseBrowseListing(built)
        assertNotNull(parsed)
        assertEquals(requestId, parsed!!.requestId)
        assertEquals("Pictures", parsed.path)
        assertEquals(entries, parsed.entries)
        assertFalse(parsed.truncated)
        assertNull(parsed.error)
        assertEquals(ts, parsed.ts)
        // The optional flag is omitted when false.
        assertFalse(built.has("truncated"))
    }

    @Test
    fun listingTruncatedFlagRoundTrips() {
        val built = RequestCodec.buildBrowseListing(
            requestId = requestId,
            path = "",
            entries = listOf(entry("Pictures", dir = true)),
            truncated = true,
            ts = ts,
        )
        assertTrue(RequestCodec.parseBrowseListing(built)!!.truncated)
    }

    @Test
    fun listingAcceptsAnEmptyDirectory() {
        val built = RequestCodec.buildBrowseListing(
            requestId = requestId,
            path = "Music",
            entries = emptyList(),
            ts = ts,
        )
        val parsed = RequestCodec.parseBrowseListing(built)
        assertNotNull(parsed)
        assertTrue(parsed!!.entries.isEmpty())
    }

    @Test
    fun everyListingErrorRoundTrips() {
        for (error in BrowseError.entries) {
            val built = RequestCodec.buildBrowseListing(
                requestId = requestId,
                path = "Pictures",
                error = error,
                ts = ts,
            )
            assertFalse(built.has("entries"))
            val parsed = RequestCodec.parseBrowseListing(built)
            assertNotNull("rejected error: ${error.wire}", parsed)
            assertEquals(error, parsed!!.error)
            assertTrue(parsed.entries.isEmpty())
        }
    }

    @Test
    fun listingErrorAndEntriesAreMutuallyExclusive() {
        val both = RequestCodec.buildBrowseListing(
            requestId = requestId,
            path = "Pictures",
            entries = listOf(entry("a.jpg")),
            ts = ts,
        ).put("error", "denied")
        assertNull(RequestCodec.parseBrowseListing(both))

        // A truncated flag alongside an error is harmless and ignored.
        val withTruncated = RequestCodec.buildBrowseListing(
            requestId = requestId,
            path = "Pictures",
            error = BrowseError.DENIED,
            ts = ts,
        ).put("truncated", false)
        val parsed = RequestCodec.parseBrowseListing(withTruncated)
        assertEquals(BrowseError.DENIED, parsed!!.error)
        assertFalse(parsed.truncated)
    }

    @Test
    fun listingRejectsUnknownErrorsAndMissingEntries() {
        val unknown = RequestCodec.buildBrowseListing(
            requestId = requestId,
            path = "Pictures",
            error = BrowseError.DENIED,
            ts = ts,
        ).put("error", "because")
        assertNull(RequestCodec.parseBrowseListing(unknown))

        val noEntries = JSONObject()
            .put("request_id", requestId)
            .put("path", "Pictures")
            .put("ts", ts)
        assertNull(RequestCodec.parseBrowseListing(noEntries))
    }

    @Test
    fun listingEntryCapIsOneThousand() {
        val atCap = JSONArray()
        repeat(RequestCodec.MAX_LISTING_ENTRIES) { index ->
            atCap.put(
                JSONObject()
                    .put("name", "file-$index.txt")
                    .put("size", 1)
                    .put("modified", 1)
                    .put("dir", false),
            )
        }
        val accepted = JSONObject()
            .put("request_id", requestId)
            .put("path", "Documents")
            .put("entries", atCap)
            .put("ts", ts)
        val atCapParsed = RequestCodec.parseBrowseListing(accepted)!!
        assertEquals(RequestCodec.MAX_LISTING_ENTRIES, atCapParsed.entries.size)
        assertFalse(atCapParsed.truncated)

        atCap.put(
            JSONObject().put("name", "one-too-many.txt").put("size", 1)
                .put("modified", 1).put("dir", false),
        )
        val capped = RequestCodec.parseBrowseListing(
            JSONObject(accepted.toString()).put("entries", atCap),
        )!!
        // The cap applies to accepted entries: the extra row is dropped and
        // the UI is told the list was cut.
        assertEquals(RequestCodec.MAX_LISTING_ENTRIES, capped.entries.size)
        assertTrue(capped.truncated)
    }

    @Test
    fun listingSkipsMalformedRowsAndKeepsTheRest() {
        val entries = JSONArray()
            .put(
                JSONObject().put("name", "keep-1.txt").put("size", 1)
                    .put("modified", 1).put("dir", false),
            )
            // A 300-char directory name on the target's disk.
            .put(
                JSONObject().put("name", "x".repeat(RequestCodec.MAX_ENTRY_NAME_CHARS + 1))
                    .put("size", 1).put("modified", 1).put("dir", true),
            )
            .put(
                JSONObject().put("name", "bad\u0000name").put("size", 1)
                    .put("modified", 1).put("dir", false),
            )
            // Missing a required field.
            .put(JSONObject().put("name", "no-dir").put("size", 1).put("modified", 1))
            .put(
                JSONObject().put("name", "keep-2.txt").put("size", 2)
                    .put("modified", 2).put("dir", false),
            )
        val json = JSONObject()
            .put("request_id", requestId)
            .put("path", "Documents")
            .put("entries", entries)
            .put("ts", ts)

        val parsed = RequestCodec.parseBrowseListing(json)
        assertNotNull(parsed)
        assertEquals(listOf("keep-1.txt", "keep-2.txt"), parsed!!.entries.map { it.name })
        assertFalse(parsed.truncated)
    }

    @Test
    fun listingDropsDuplicateNameAndDirPairsKeepingFirst() {
        fun row(name: String, size: Long, dir: Boolean = false) = JSONObject()
            .put("name", name)
            .put("size", size)
            .put("modified", size)
            .put("dir", dir)

        val json = JSONObject()
            .put("request_id", requestId)
            .put("path", "Documents")
            .put(
                "entries",
                JSONArray()
                    .put(row("same.txt", 1L))
                    .put(row("same.txt", 2L))
                    // Same name as a directory is a different list key.
                    .put(row("same.txt", 3L, dir = true))
                    .put(row("other.txt", 4L)),
            )
            .put("ts", ts)

        val parsed = RequestCodec.parseBrowseListing(json)!!
        assertEquals(
            listOf("same.txt", "same.txt", "other.txt"),
            parsed.entries.map { it.name },
        )
        assertEquals(listOf(false, true, false), parsed.entries.map { it.dir })
        // First occurrence wins.
        assertEquals(1L, parsed.entries.first().size)
    }

    @Test
    fun listingEntryNamesAreSingleSafeSegments() {
        val badNames = listOf(
            "",
            ".",
            "..",
            "a/b",
            "/leading",
            "trailing/",
            "tab\there",
            "nul\u0000here",
            "x".repeat(RequestCodec.MAX_ENTRY_NAME_CHARS + 1),
        )
        for (bad in badNames) {
            val json = JSONObject()
                .put("request_id", requestId)
                .put("path", "Pictures")
                .put(
                    "entries",
                    JSONArray().put(
                        JSONObject().put("name", bad).put("size", 1)
                            .put("modified", 1).put("dir", false),
                    ),
                )
                .put("ts", ts)
            // One unlistable name is skipped, not fatal to the listing.
            val parsed = RequestCodec.parseBrowseListing(json)
            assertNotNull("rejected listing for name: $bad", parsed)
            assertTrue("kept name: $bad", parsed!!.entries.isEmpty())
        }
    }

    @Test
    fun listingEntryFieldsAreStrictlyTyped() {
        fun listingWith(entry: JSONObject): JSONObject = JSONObject()
            .put("request_id", requestId)
            .put("path", "Pictures")
            .put("entries", JSONArray().put(entry))
            .put("ts", ts)

        val valid = JSONObject().put("name", "a.jpg").put("size", 1)
            .put("modified", 1).put("dir", false)
        assertEquals(1, RequestCodec.parseBrowseListing(listingWith(valid))!!.entries.size)

        // Missing or wrongly typed fields skip that row, not the listing.
        for (field in listOf("name", "size", "modified", "dir")) {
            val missing = JSONObject(valid.toString()).apply { remove(field) }
            val parsed = RequestCodec.parseBrowseListing(listingWith(missing))
            assertNotNull("rejected listing without $field", parsed)
            assertTrue("kept row without $field", parsed!!.entries.isEmpty())
        }
        for (mutated in listOf(
            JSONObject(valid.toString()).put("size", -1),
            JSONObject(valid.toString()).put("size", 1.5),
            JSONObject(valid.toString()).put("dir", "false"),
            JSONObject(valid.toString()).put("modified", -1),
        )) {
            assertTrue(RequestCodec.parseBrowseListing(listingWith(mutated))!!.entries.isEmpty())
        }
        // Zero size and zero mtime are legal.
        assertEquals(
            1,
            RequestCodec.parseBrowseListing(
                listingWith(
                    JSONObject(valid.toString()).put("size", 0).put("modified", 0),
                ),
            )!!.entries.size,
        )
    }

    @Test
    fun listingPathAndTimestampAreValidated() {
        val base = RequestCodec.buildBrowseListing(
            requestId = requestId,
            path = "Pictures",
            entries = emptyList(),
            ts = ts,
        )
        assertNull(RequestCodec.parseBrowseListing(JSONObject(base.toString()).put("path", "/Pictures")))
        assertNull(RequestCodec.parseBrowseListing(JSONObject(base.toString()).put("path", "../x")))
        assertNull(RequestCodec.parseBrowseListing(JSONObject(base.toString()).put("ts", 0)))
        assertNull(
            RequestCodec.parseBrowseListing(
                JSONObject(base.toString()).put("request_id", "req-nope"),
            ),
        )
        // Optional flags are strictly typed.
        assertNull(
            RequestCodec.parseBrowseListing(
                JSONObject(base.toString()).put("truncated", "true"),
            ),
        )
        assertNull(
            RequestCodec.parseBrowseListing(
                JSONObject(base.toString()).put("error", 42),
            ),
        )
    }

    // -------------------------------------------------------------- fetch

    private fun validFetch(paths: List<String> = listOf("Pictures/a.jpg")): JSONObject = JSONObject()
        .put("request_id", requestId)
        .put("requester_id", requesterId)
        .put("requester_name", requesterName)
        .put("target_id", targetId)
        .put("paths", JSONArray().apply { paths.forEach { put(it) } })
        .put("expires_ts", expiresTs)
        .put("grant", grant)
        .put("ts", ts)

    @Test
    fun fetchParsesEveryField() {
        val paths = listOf("Pictures/Screenshots/a.png", "Movies/b.mp4")
        val parsed = RequestCodec.parseFetch(validFetch(paths), now)
        assertNotNull(parsed)
        assertEquals(requestId, parsed!!.requestId)
        assertEquals(requesterId, parsed.requesterId)
        assertEquals(requesterName, parsed.requesterName)
        assertEquals(targetId, parsed.targetId)
        assertEquals(paths, parsed.paths)
        assertEquals(expiresTs, parsed.expiresTs)
        assertEquals(grant, parsed.grant)
        assertEquals(ts, parsed.ts)
        assertEquals(requesterId, parsed.trustId)
    }

    @Test
    fun fetchPathCountIsOneToThirtyTwo() {
        val one = RequestCodec.parseFetch(validFetch(listOf("Pictures/a.jpg")), now)
        assertNotNull(one)
        assertEquals(1, one!!.paths.size)

        val thirtyTwo = (0 until RequestCodec.MAX_FETCH_PATHS).map { "Pictures/$it.jpg" }
        assertNotNull(RequestCodec.parseFetch(validFetch(thirtyTwo), now))

        assertNull(RequestCodec.parseFetch(validFetch(emptyList()), now))
        val thirtyThree = (0 until RequestCodec.MAX_FETCH_PATHS + 1).map { "Pictures/$it.jpg" }
        assertNull(RequestCodec.parseFetch(validFetch(thirtyThree), now))
    }

    @Test
    fun fetchPathsMustBeNonEmptyRelativePaths() {
        val badPaths = listOf(
            "",
            "/Pictures/a.jpg",
            "Pictures/",
            "Pictures/../a.jpg",
            "Pictures//a.jpg",
            "Pictures/\u0000a.jpg",
            "x".repeat(RequestCodec.MAX_PATH_CHARS + 1),
        )
        for (bad in badPaths) {
            assertNull("accepted path: $bad", RequestCodec.parseFetch(validFetch(listOf(bad)), now))
        }
        val nonString = JSONObject(validFetch().toString())
            .put("paths", JSONArray().put(42))
        assertNull(RequestCodec.parseFetch(nonString, now))
        val missing = JSONObject(validFetch().toString()).apply { remove("paths") }
        assertNull(RequestCodec.parseFetch(missing, now))
    }

    @Test
    fun fetchGrantAndExpiryAreClamped() {
        assertNull(RequestCodec.parseFetch(validFetch().put("grant", "abc"), now))
        assertNull(RequestCodec.parseFetch(validFetch().put("grant", grant.uppercase()), now))
        assertNull(
            RequestCodec.parseFetch(
                validFetch().put("expires_ts", now + RequestCodec.MAX_REQUEST_LIFETIME_MS + 1),
                now,
            ),
        )
        assertNull(RequestCodec.parseFetch(validFetch().put("expires_ts", now), now))
        assertNull(RequestCodec.parseFetch(validFetch().put("expires_ts", 0), now))
    }

    @Test
    fun fetchRejectsMissingIdentityFields() {
        for (field in listOf(
            "request_id", "requester_id", "requester_name", "target_id",
            "expires_ts", "grant", "ts",
        )) {
            val json = JSONObject(validFetch().toString()).apply { remove(field) }
            assertNull("accepted without $field", RequestCodec.parseFetch(json, now))
        }
    }

    @Test
    fun fetchOriginIdDrivesTheTrustId() {
        val desktop = JSONObject(validFetch().toString())
            .put("requester_id", "reqdev-4242-a1b2")
            .put("origin_id", "laptop-1")
        val parsed = RequestCodec.parseFetch(desktop, now)
        assertEquals("laptop-1", parsed!!.trustId)
        assertNull(RequestCodec.parseFetch(validFetch().put("origin_id", "two words"), now))
    }

    @Test
    fun buildFetchRoundTrips() {
        val paths = listOf("Pictures/a.jpg", "Music/b.flac")
        val built = RequestCodec.buildFetch(
            requestId = requestId,
            requesterId = requesterId,
            requesterName = requesterName,
            targetId = targetId,
            paths = paths,
            expiresTs = expiresTs,
            grant = grant,
            ts = ts,
        )
        assertFalse(built.has("origin_id"))
        val parsed = RequestCodec.parseFetch(built, now)
        assertEquals(paths, parsed!!.paths)
        assertEquals(grant, parsed.grant)

        val withOrigin = RequestCodec.buildFetch(
            requestId = requestId,
            requesterId = "reqdev-4242-a1b2",
            originId = "laptop-1",
            requesterName = requesterName,
            targetId = targetId,
            paths = paths,
            expiresTs = expiresTs,
            grant = grant,
            ts = ts,
        )
        assertEquals("laptop-1", RequestCodec.parseFetch(withOrigin, now)!!.trustId)
    }

    // ------------------------------------------------------ path helpers

    @Test
    fun relativePathRulesArePinned() {
        for (good in listOf("Pictures", "Pictures/Screenshots", "a/b/c.txt", "a b/c.txt")) {
            assertTrue("rejected: $good", RequestCodec.isRelativePath(good))
        }
        for (bad in listOf(
            "", "/x", "x/", "x//y", ".", "..", "x/../y", "x/./y",
            "x\u0000y", "x\u001Fy", "x\u007Fy", "x\u0085y",
            "x".repeat(RequestCodec.MAX_PATH_CHARS + 1),
        )) {
            assertFalse("accepted: $bad", RequestCodec.isRelativePath(bad))
        }
        assertTrue(RequestCodec.isBrowsePath(""))
        assertFalse(RequestCodec.isBrowsePath("/"))
    }

    @Test
    fun entryNameRulesArePinned() {
        assertTrue(RequestCodec.isEntryName("Screenshot_03.png"))
        assertTrue(RequestCodec.isEntryName("a b.txt"))
        for (bad in listOf("", ".", "..", "a/b", "a\u0000b", "x".repeat(256))) {
            assertFalse("accepted: $bad", RequestCodec.isEntryName(bad))
        }
    }

    // ----------------------------------------------------------- declines

    @Test
    fun newDeclineReasonsArePinned() {
        assertEquals("not_found", DeclineReason.NOT_FOUND.wire)
        assertEquals("too_large", DeclineReason.TOO_LARGE.wire)
        // The old reasons keep their wire values.
        assertEquals("declined", DeclineReason.DECLINED.wire)
        assertEquals("busy", DeclineReason.BUSY.wire)
        assertEquals("expired", DeclineReason.EXPIRED.wire)
        assertEquals("unsupported", DeclineReason.UNSUPPORTED.wire)
        assertEquals("picker_unavailable", DeclineReason.PICKER_UNAVAILABLE.wire)
        assertEquals("not_allowed", DeclineReason.NOT_ALLOWED.wire)

        for (reason in listOf(DeclineReason.NOT_FOUND, DeclineReason.TOO_LARGE)) {
            val parsed = RequestCodec.parseDecline(
                RequestCodec.buildDecline(requestId, reason, ts),
            )
            assertEquals(reason, parsed!!.reason)
        }
    }
}
