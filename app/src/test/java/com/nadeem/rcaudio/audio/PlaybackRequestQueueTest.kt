package com.nadeem.rcaudio.audio

import com.nadeem.rcaudio.events.GameEventType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaybackRequestQueueTest {
    private val first = PlaybackRequest("first", GameEventType.FOUR, "content://clips/four")
    private val second = PlaybackRequest("second", GameEventType.SIX, "content://clips/six")
    private val replacement = PlaybackRequest("replacement", GameEventType.DOUBLE, "content://clips/double")

    @Test
    fun preservesQueueOrderForNormalEvents() {
        val queue = PlaybackRequestQueue()
        queue.enqueue(first)
        queue.enqueue(second)

        assertEquals(first, queue.poll())
        assertEquals(second, queue.poll())
        assertNull(queue.poll())
    }

    @Test
    fun replacementClearsPendingEventsBeforeEnqueueingNewEvent() {
        val queue = PlaybackRequestQueue()
        queue.enqueue(first)
        queue.enqueue(second)
        queue.enqueue(replacement, replaceQueued = true)

        assertEquals(replacement, queue.poll())
        assertNull(queue.poll())
    }

    @Test
    fun stopCanClearAllPendingEvents() {
        val queue = PlaybackRequestQueue()
        queue.enqueue(first)
        queue.enqueue(second)
        queue.clear()

        assertNull(queue.poll())
    }
}
