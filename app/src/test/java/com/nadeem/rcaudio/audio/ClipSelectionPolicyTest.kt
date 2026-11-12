package com.nadeem.rcaudio.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipSelectionPolicyTest {
    @Test
    fun returnsNullWhenNoClipIsMapped() {
        assertNull(ClipSelectionPolicy.choose(emptyList(), emptyList()))
    }

    @Test
    fun avoidsTheImmediatelyPreviousClipWhenThereIsAnAlternative() {
        repeat(20) {
            assertEquals("clip-b", ClipSelectionPolicy.choose(listOf("clip-a", "clip-b"), listOf("clip-a")))
        }
    }

    @Test
    fun avoidsTheRecentThreeWhenEnoughClipsExist() {
        val recent = listOf("a", "b", "c")
        val candidates = recent + "d"
        repeat(20) {
            assertEquals("d", ClipSelectionPolicy.choose(candidates, recent))
        }
    }

    @Test
    fun selectsOnlyFromMappedCandidates() {
        val candidates = listOf("one", "two", "three")
        repeat(100) {
            assertTrue(ClipSelectionPolicy.choose(candidates, emptyList()) in candidates)
        }
    }

    @Test
    fun allowsAnExistingClipEvenWhenPoolIsFull() {
        assertTrue(
            ClipPoolPolicy.canAdd(
                (1..AudioPoolManager.MAX_CLIPS_PER_EVENT).map { it.toString() }.toSet(),
                "1",
                AudioPoolManager.MAX_CLIPS_PER_EVENT,
            ),
        )
    }

    @Test
    fun rejectsANewClipWhenPoolIsFull() {
        assertEquals(
            false,
            ClipPoolPolicy.canAdd(
                (1..AudioPoolManager.MAX_CLIPS_PER_EVENT).map { it.toString() }.toSet(),
                "41",
                AudioPoolManager.MAX_CLIPS_PER_EVENT,
            ),
        )
    }

    @Test
    fun allowsTheFortiethClipAndCapsEachCategoryAtForty() {
        val thirtyNineClips = (1..39).map { it.toString() }.toSet()
        val fortyClips = (1..40).map { it.toString() }.toSet()

        assertEquals(40, AudioPoolManager.MAX_CLIPS_PER_EVENT)
        assertTrue(ClipPoolPolicy.canAdd(thirtyNineClips, "40", AudioPoolManager.MAX_CLIPS_PER_EVENT))
        assertEquals(
            false,
            ClipPoolPolicy.canAdd(fortyClips, "41", AudioPoolManager.MAX_CLIPS_PER_EVENT),
        )
    }
}
