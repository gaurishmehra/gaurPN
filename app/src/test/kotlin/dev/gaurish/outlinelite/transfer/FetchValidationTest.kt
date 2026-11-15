package dev.gaurish.outlinelite.transfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FetchValidationTest {

    private fun paths(count: Int): List<String> = (0 until count).map { "Pictures/$it.jpg" }

    @Test
    fun thirtyTwoPathsAreAccepted() {
        assertNull(FetchValidation.validate(paths(32), List(32) { 1L }))
    }

    @Test
    fun thirtyThreePathsAreRejected() {
        assertEquals(
            FetchRejection.INVALID_COUNT,
            FetchValidation.validate(paths(33), List(33) { 1L }),
        )
    }

    @Test
    fun emptyBatchIsRejected() {
        assertEquals(FetchRejection.INVALID_COUNT, FetchValidation.validate(emptyList(), emptyList()))
    }

    @Test
    fun missingPathIsNotFound() {
        val sizes = listOf<Long?>(1L, null, 3L)
        assertEquals(
            FetchRejection.NOT_FOUND,
            FetchValidation.validate(paths(3), sizes),
        )
    }

    @Test
    fun oversizedBatchIsRejected() {
        assertEquals(
            FetchRejection.TOO_LARGE,
            FetchValidation.validate(
                paths(2),
                listOf(FetchValidation.MAX_TOTAL_BYTES, 1L),
            ),
        )
        assertEquals(
            FetchRejection.TOO_LARGE,
            FetchValidation.validate(
                paths(2),
                listOf(FetchValidation.MAX_TOTAL_BYTES - 1L, 2L),
            ),
        )
    }

    @Test
    fun exactlyFourGibibytesIsAllowed() {
        assertNull(
            FetchValidation.validate(
                paths(2),
                listOf(FetchValidation.MAX_TOTAL_BYTES - 1L, 1L),
            ),
        )
    }

    @Test
    fun hugeValuesCannotOverflowIntoAnAccept() {
        assertEquals(
            FetchRejection.TOO_LARGE,
            FetchValidation.validate(paths(2), listOf(Long.MAX_VALUE, Long.MAX_VALUE)),
        )
    }

    @Test
    fun sizeListMustMatchThePathList() {
        assertEquals(
            FetchRejection.INVALID_COUNT,
            FetchValidation.validate(paths(2), listOf(1L)),
        )
    }

    @Test
    fun negativeSizeIsTreatedAsMissing() {
        assertEquals(
            FetchRejection.NOT_FOUND,
            FetchValidation.validate(paths(1), listOf(-1L)),
        )
    }

    @Test
    fun overlongSegmentIsRejected() {
        // A segment longer than any listable entry name can never name a real
        // file; the desktop enforces the same 255-unit cap.
        assertEquals(
            FetchRejection.INVALID_PATH,
            FetchValidation.validate(
                listOf("Pictures/" + "x".repeat(FetchValidation.MAX_SEGMENT_CHARS + 1)),
                listOf(1L),
            ),
        )
        assertNull(
            FetchValidation.validate(
                listOf("Pictures/" + "x".repeat(FetchValidation.MAX_SEGMENT_CHARS)),
                listOf(1L),
            ),
        )
        // The cap is per segment, not per path.
        assertNull(
            FetchValidation.validate(
                listOf(
                    "Pictures/" + "a".repeat(FetchValidation.MAX_SEGMENT_CHARS) +
                        "/" + "b".repeat(FetchValidation.MAX_SEGMENT_CHARS),
                ),
                listOf(1L),
            ),
        )
        assertEquals(255, FetchValidation.MAX_SEGMENT_CHARS)
    }

    @Test
    fun capIsPinned() {
        assertEquals(32, FetchValidation.MAX_PATHS)
        assertEquals(4L * 1024 * 1024 * 1024, FetchValidation.MAX_TOTAL_BYTES)
    }
}
