package dev.bubbleclaw

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationStoreTest {

    @Test
    fun titleComesFromFirstUserMessageAndCollapsesWhitespace() {
        val messages = listOf(
            ChatMessage(role = "assistant", text = "bonjour"),
            ChatMessage(role = "user", text = "  Lancer   YouTube\n s'il te plaît  ")
        )
        assertEquals("Lancer YouTube s'il te plaît", ConversationStore.titleFor(messages))
    }

    @Test
    fun titleFallsBackWhenNoUserMessageExists() {
        val messages = listOf(ChatMessage(role = "assistant", text = "bonjour"))
        assertEquals(ConversationStore.defaultTitle(), ConversationStore.titleFor(messages))
    }

    @Test
    fun sessionsAreStablePerConversationAndDistinctAcrossConversations() {
        val first = ConversationStore.sessionFor("android:bubbleclaw", "c1700000000001")
        val same = ConversationStore.sessionFor("android:bubbleclaw", "c1700000000001")
        val other = ConversationStore.sessionFor("android:bubbleclaw", "c1700000000002")
        assertEquals(first, same)
        assertNotEquals(first, other)
        assertTrue(first.startsWith("android:bubbleclaw:"))
    }
}
