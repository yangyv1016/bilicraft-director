package dev.bilicraft.director.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LocalChatHistoryTest {
    @Test
    void retainsOnlyTheLatestHundredMessagesAndClearsAcrossConnections() {
        LocalChatHistory history = new LocalChatHistory();
        for (int index = 0; index < 105; index++) history.append("message " + index, index);
        var snapshot = history.messages();
        assertEquals(100, snapshot.size());
        assertEquals("message 5", snapshot.getFirst().text());
        assertEquals(104, snapshot.getLast().receivedAt());
        history.clear();
        assertTrue(history.messages().isEmpty());
        history.append("new connection", 200);
        assertTrue(history.messages().getFirst().id() > snapshot.getLast().id());
        assertEquals(100, snapshot.size(), "Snapshots must not change with the live history");
    }

    @Test
    void boundsIncomingTextWithoutSplittingAnEmojiAndKeepsMarkupAsPlainText() {
        LocalChatHistory history = new LocalChatHistory();
        history.append("§a", 0);
        history.append("§a<player> <script>alert(1)</script>", 1);
        assertEquals("<player> <script>alert(1)</script>", history.messages().getFirst().text());
        history.append("a".repeat(2047) + "😀tail", 2);
        assertEquals("a".repeat(2047), history.messages().getLast().text());
    }
}
