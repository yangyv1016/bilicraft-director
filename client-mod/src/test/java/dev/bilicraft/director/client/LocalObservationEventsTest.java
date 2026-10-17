package dev.bilicraft.director.client;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class LocalObservationEventsTest {
    private final UUID playerId = new UUID(5, 9);

    @Test
    void givesEachHistoryItsOwnStableIdentifierAndStartsEmpty() {
        LocalObservationEvents history = new LocalObservationEvents();
        String historyId = history.historyId();
        assertEquals(historyId, UUID.fromString(historyId).toString());
        assertTrue(history.entries().isEmpty());
        history.append("REQUESTED", "CLIENT", playerId, "Player", "server", "world", "请求观察", 100);
        assertEquals(historyId, history.historyId());
        assertNotEquals(historyId, new LocalObservationEvents().historyId());
    }

    @Test
    void preservesEventIdentityContextAndOriginalTimestamp() {
        LocalObservationEvents history = new LocalObservationEvents();
        LocalObservationEvents.Entry entry = history.append("ENDED", "SERVER", playerId,
                "Player", "play.example.test:25565", "minecraft:overworld", "目标拒绝观察", 1_234);
        assertTrue(entry.id() > 0);
        assertEquals(1_234, entry.timestamp());
        assertEquals("ENDED", entry.stage());
        assertEquals("SERVER", entry.source());
        assertEquals(playerId, entry.playerId());
        assertEquals("Player", entry.playerName());
        assertEquals("play.example.test:25565", entry.server());
        assertEquals("minecraft:overworld", entry.world());
        assertEquals("目标拒绝观察", entry.reason());
        assertEquals(entry, history.entries().getFirst());
    }

    @Test
    void retainsTheLatestTwoHundredEventsInArrivalOrderWithoutReusingIds() {
        LocalObservationEvents history = new LocalObservationEvents();
        for (int index = 0; index < 205; index++) {
            history.append("ENDED", "SERVER", playerId, "Player", "server", "world",
                    "event " + index, 1_000 - index);
        }
        var entries = history.entries();
        assertEquals(200, entries.size());
        assertEquals("event 5", entries.getFirst().reason());
        assertEquals(995, entries.getFirst().timestamp());
        assertEquals("event 204", entries.getLast().reason());
        assertEquals(796, entries.getLast().timestamp());
        for (int index = 1; index < entries.size(); index++) {
            assertEquals(entries.get(index - 1).id() + 1, entries.get(index).id());
        }
        LocalObservationEvents.Entry next = history.append("REQUESTED", "CLIENT", playerId,
                "Player", "server", "world", "new attempt", 2_000);
        assertEquals(entries.getLast().id() + 1, next.id());
        assertEquals(200, history.entries().size());
    }

    @Test
    void returnsImmutableSnapshotsThatDoNotChangeWhenTheLiveBufferAdvances() {
        LocalObservationEvents history = new LocalObservationEvents();
        LocalObservationEvents.Entry first = history.append("NOTICE", "SERVER", playerId,
                "Player", "server", "world", "已通知玩家", 10);
        var snapshot = history.entries();
        assertThrows(UnsupportedOperationException.class, () -> snapshot.add(first));
        assertThrows(UnsupportedOperationException.class, () -> snapshot.removeFirst());
        for (int index = 0; index < 200; index++) {
            history.append("ENDED", "SERVER", playerId, "Player", "server", "world", "event " + index, index);
        }
        assertEquals(1, snapshot.size());
        assertEquals(first, snapshot.getFirst());
        assertFalse(history.entries().contains(first));
    }

    @Test
    void removesMinecraftFormattingAndReplacesControlCharactersAcrossTextFields() {
        LocalObservationEvents history = new LocalObservationEvents();
        LocalObservationEvents.Entry entry = history.append("§aENDED\r", "§lSERVER\n", playerId,
                "§bPlayer\t", "server\u0000", "world\u0085", "§c拒绝\r\n观察\t\u001b§r", 10);
        assertEquals("ENDED ", entry.stage());
        assertEquals("SERVER ", entry.source());
        assertEquals("Player ", entry.playerName());
        assertEquals("server ", entry.server());
        assertEquals("world ", entry.world());
        assertEquals("拒绝  观察  ", entry.reason());
    }

    @Test
    void boundsReasonsAfterCleaningWithoutSplittingAnEmojiAndKeepsMarkupAsText() {
        LocalObservationEvents history = new LocalObservationEvents();
        LocalObservationEvents.Entry exact = history.append("ENDED", "SERVER", playerId,
                "Player", "server", "world", "§a" + "a".repeat(510) + "😀tail", 10);
        assertEquals("a".repeat(510) + "😀", exact.reason());
        assertEquals(512, exact.reason().length());

        LocalObservationEvents.Entry split = history.append("ENDED", "SERVER", playerId,
                "Player", "server", "world", "a".repeat(511) + "😀tail", 11);
        assertEquals("a".repeat(511), split.reason());
        assertFalse(Character.isHighSurrogate(split.reason().charAt(split.reason().length() - 1)));

        LocalObservationEvents.Entry markup = history.append("ENDED", "SERVER", playerId,
                "<player>", "server", "world", "<script>alert(1)</script> 中文 😀", 12);
        assertEquals("<player>", markup.playerName());
        assertEquals("<script>alert(1)</script> 中文 😀", markup.reason());
    }
}
