package dev.bilicraft.director.protocol;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SnapshotPagesTest {
    private static final Message.PlayerSummary PLAYER = new Message.PlayerSummary(new UUID(1, 2), "Alice", "world",
            Message.PlayerAvailability.AVAILABLE);
    private static final Message.SceneView SCENE = new Message.SceneView("city", "城区", Message.SceneKind.AREA,
            "world", 0, 80, 0, 12);

    @Test
    void pagesPreserveAllPlayersAndScenesBeyondTheLegacyLimits() throws IOException {
        List<Message.PlayerSummary> players = IntStream.range(0, 180).mapToObj(index ->
                new Message.PlayerSummary(new UUID(0, index), "P" + index, "world", Message.PlayerAvailability.AVAILABLE)).toList();
        List<Message.SceneView> scenes = IntStream.range(0, 70).mapToObj(index ->
                new Message.SceneView("s" + index, "S", Message.SceneKind.AREA, "world", index, 80, 0, 12)).toList();
        List<Message.SnapshotPage> pages = SnapshotPages.create(42, players, scenes);
        assertEquals(9, pages.size());
        assertEquals(players, pages.stream().flatMap(page -> page.players().stream()).toList());
        assertEquals(scenes, pages.stream().flatMap(page -> page.scenes().stream()).toList());
        for (int index = 0; index < pages.size(); index++) {
            Message.SnapshotPage page = pages.get(index);
            assertEquals(index, page.pageIndex());
            assertEquals(9, page.pageCount());
            assertTrue(page.players().isEmpty() || page.scenes().isEmpty());
            assertEquals(page, Codec.decode(Codec.encode(page)));
        }
    }

    @Test
    void emptySnapshotStillCompletesWithOnePageAndListsAreImmutable() throws IOException {
        List<Message.SnapshotPage> pages = SnapshotPages.create(1, List.of(), List.of());
        assertEquals(List.of(new Message.SnapshotPage(1, 0, 1, List.of(), List.of())), pages);
        assertEquals(pages.getFirst(), Codec.decode(Codec.encode(pages.getFirst())));
        ArrayList<Message.PlayerSummary> players = new ArrayList<>(List.of(PLAYER));
        ArrayList<Message.SceneView> scenes = new ArrayList<>(List.of(SCENE));
        Message.SnapshotPage page = new Message.SnapshotPage(1, 0, 1, players, scenes);
        players.clear();
        scenes.clear();
        assertEquals(List.of(PLAYER), page.players());
        assertEquals(List.of(SCENE), page.scenes());
        assertThrows(UnsupportedOperationException.class, () -> page.players().clear());
        assertThrows(UnsupportedOperationException.class, () -> page.scenes().clear());
        assertThrows(UnsupportedOperationException.class, pages::clear);
    }

    @Test
    void maximumPlayerAndTotalPageCountsAreLosslessAndExcessIsRejected() {
        List<Message.PlayerSummary> players = IntStream.range(0, Protocol.MAX_SNAPSHOT_PLAYERS).mapToObj(index ->
                new Message.PlayerSummary(new UUID(0, index), "P", "world", Message.PlayerAvailability.AVAILABLE)).toList();
        List<Message.SceneView> scenes = IntStream.range(0, 4_096).mapToObj(index ->
                new Message.SceneView("s" + index, "S", Message.SceneKind.AREA, "world", 0, 80, 0, 12)).toList();
        assertEquals(256, SnapshotPages.create(1, players, scenes).size());
        ArrayList<Message.PlayerSummary> tooManyPlayers = new ArrayList<>(players);
        tooManyPlayers.add(new Message.PlayerSummary(new UUID(1, 0), "P", "world", Message.PlayerAvailability.IDLE));
        assertThrows(IllegalArgumentException.class, () -> SnapshotPages.create(1, tooManyPlayers, List.of()));
        ArrayList<Message.SceneView> tooManyPages = new ArrayList<>(scenes);
        tooManyPages.add(new Message.SceneView("extra", "S", Message.SceneKind.AREA, "world", 0, 80, 0, 12));
        assertThrows(IllegalArgumentException.class, () -> SnapshotPages.create(1, players, tooManyPages));
        assertThrows(IllegalArgumentException.class, () -> SnapshotPages.create(0, List.of(), List.of()));
        assertThrows(IllegalArgumentException.class, () -> SnapshotPages.create(1, List.of(PLAYER, PLAYER), List.of()));
        assertThrows(IllegalArgumentException.class, () -> SnapshotPages.create(1, List.of(), List.of(SCENE, SCENE)));
    }

    @Test
    void fullScenePageAtEveryStringByteLimitFitsThePacketBudget() throws IOException {
        List<Message.SceneView> scenes = IntStream.range(0, 32).mapToObj(index -> {
            String suffix = Integer.toString(index);
            return new Message.SceneView("s".repeat(64 - suffix.length()) + suffix, "n".repeat(256),
                    Message.SceneKind.AREA, "w".repeat(256), 0, 80, 0, 48);
        }).toList();
        Message.SnapshotPage page = SnapshotPages.create(1, List.of(), scenes).getFirst();
        byte[] bytes = Codec.encode(page);
        assertEquals(19_703, bytes.length);
        assertTrue(bytes.length < Protocol.MAX_PACKET_BYTES);
        assertEquals(page, Codec.decode(bytes));
    }

    @Test
    void everyAvailabilityAndPositiveRequestIdRoundTrips() throws IOException {
        for (Message.PlayerAvailability availability : Message.PlayerAvailability.values()) {
            Message.SnapshotPage page = new Message.SnapshotPage(7, 0, 1,
                    List.of(new Message.PlayerSummary(PLAYER.id(), "玩家", "world", availability)), List.of());
            assertEquals(page, Codec.decode(Codec.encode(page)));
        }
        assertEquals(new Message.SnapshotRequest(Long.MAX_VALUE), Codec.decode(Codec.encode(new Message.SnapshotRequest(Long.MAX_VALUE))));
        for (long invalid : List.of(0L, -1L)) {
            assertThrows(IOException.class, () -> Codec.encode(new Message.SnapshotRequest(invalid)));
            byte[] bytes = Codec.encode(new Message.SnapshotRequest(1));
            ByteBuffer.wrap(bytes).putLong(7, invalid);
            assertThrows(IOException.class, () -> Codec.decode(bytes));
        }
    }

    @Test
    void rejectsMalformedPageMetadataAndCountsBeforeEntries() throws IOException {
        for (Message.SnapshotPage invalid : List.of(
                new Message.SnapshotPage(0, 0, 1, List.of(), List.of()),
                new Message.SnapshotPage(1, -1, 1, List.of(), List.of()),
                new Message.SnapshotPage(1, 1, 1, List.of(), List.of()),
                new Message.SnapshotPage(1, 0, 0, List.of(), List.of()),
                new Message.SnapshotPage(1, 0, 257, List.of(), List.of()),
                new Message.SnapshotPage(1, 0, 1, java.util.Collections.nCopies(33, PLAYER), List.of()),
                new Message.SnapshotPage(1, 0, 1, List.of(), java.util.Collections.nCopies(33, SCENE)))) {
            assertThrows(IOException.class, () -> Codec.encode(invalid));
        }
        for (int[] corruption : List.of(new int[]{15, 1}, new int[]{17, 0}, new int[]{17, 257},
                new int[]{19, 33}, new int[]{21, 33})) {
            byte[] bytes = Codec.encode(new Message.SnapshotPage(1, 0, 1, List.of(), List.of()));
            ByteBuffer.wrap(bytes).putShort(corruption[0], (short) corruption[1]);
            assertThrows(IOException.class, () -> Codec.decode(bytes));
        }
    }

    @Test
    void rejectsDuplicateIdentitiesInvalidAvailabilityAndIdentityText() throws IOException {
        assertThrows(IOException.class, () -> Codec.encode(new Message.SnapshotPage(1, 0, 1, List.of(PLAYER, PLAYER), List.of())));
        assertThrows(IOException.class, () -> Codec.encode(new Message.SnapshotPage(1, 0, 1, List.of(), List.of(SCENE, SCENE))));
        for (Message.PlayerSummary player : List.of(
                new Message.PlayerSummary(null, "Alice", "world", Message.PlayerAvailability.AVAILABLE),
                new Message.PlayerSummary(PLAYER.id(), "", "world", Message.PlayerAvailability.AVAILABLE),
                new Message.PlayerSummary(PLAYER.id(), "Alice", "world\0", Message.PlayerAvailability.AVAILABLE),
                new Message.PlayerSummary(PLAYER.id(), "Alice", "world", null))) {
            assertThrows(IOException.class, () -> Codec.encode(new Message.SnapshotPage(1, 0, 1, List.of(player), List.of())));
        }
        byte[] badEnum = Codec.encode(new Message.SnapshotPage(1, 0, 1, List.of(PLAYER), List.of()));
        badEnum[badEnum.length - 3] = (byte) 255;
        assertThrows(IOException.class, () -> Codec.decode(badEnum));
        Message.PlayerSummary other = new Message.PlayerSummary(new UUID(3, 4), PLAYER.name(), PLAYER.world(), PLAYER.availability());
        byte[] duplicate = Codec.encode(new Message.SnapshotPage(1, 0, 1, List.of(PLAYER, other), List.of()));
        int playerLength = 16 + 2 + PLAYER.name().length() + 2 + PLAYER.world().length() + 1;
        System.arraycopy(duplicate, 21, duplicate, 21 + playerLength, 16);
        assertThrows(IOException.class, () -> Codec.decode(duplicate));
    }

    @Test
    void rejectsAllTruncationsAndTrailingDataOfNewPagePackets() throws IOException {
        byte[] bytes = Codec.encode(new Message.SnapshotPage(1, 0, 1, List.of(PLAYER), List.of(SCENE)));
        for (int length = 0; length < bytes.length; length++) {
            byte[] prefix = Arrays.copyOf(bytes, length);
            assertThrows(IOException.class, () -> Codec.decode(prefix));
        }
        assertThrows(IOException.class, () -> Codec.decode(Arrays.copyOf(bytes, bytes.length + 1)));
    }
}
