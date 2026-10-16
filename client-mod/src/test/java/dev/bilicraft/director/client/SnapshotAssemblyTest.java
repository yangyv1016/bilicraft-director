package dev.bilicraft.director.client;

import dev.bilicraft.director.protocol.Message;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class SnapshotAssemblyTest {
    private static Message.PlayerSummary player(long id, String world) {
        return new Message.PlayerSummary(new UUID(0, id), "player" + id, world, Message.PlayerAvailability.AVAILABLE);
    }

    @Test
    void outOfOrderPagesPublishTheWholeRosterAndSeparatedScenesOnlyOnceComplete() throws IOException {
        SnapshotAssembly assembly = new SnapshotAssembly(7, "world", 3);
        Message.SceneView scene = new Message.SceneView("scene", "scene", Message.SceneKind.VIEWPOINT, "world", 1, 80, 2, 6);
        assertNull(assembly.accept(new Message.SnapshotPage(7, 2, 3, List.of(), List.of(scene))));
        assertNull(assembly.accept(new Message.SnapshotPage(7, 0, 3, List.of(player(1, "world")), List.of())));
        var result = assembly.accept(new Message.SnapshotPage(7, 1, 3, List.of(player(2, "another")), List.of()));
        assertEquals(List.of(player(1, "world"), player(2, "another")), result.players());
        assertEquals(List.of(scene), result.scenes());
        assertTrue(assembly.belongsTo("world", 3));
        assertFalse(assembly.belongsTo("another", 3));
        assertFalse(assembly.belongsTo("world", 4));
    }

    @Test
    void oldRequestAndRepeatedPageCannotOverwriteOrCompleteANewRoster() throws IOException {
        SnapshotAssembly assembly = new SnapshotAssembly(8, "world", 4);
        assertNull(assembly.accept(new Message.SnapshotPage(7, 0, 1, List.of(player(9, "world")), List.of())));
        assertNull(assembly.accept(new Message.SnapshotPage(8, 0, 2, List.of(player(1, "world")), List.of())));
        assertNull(assembly.accept(new Message.SnapshotPage(8, 0, 2, List.of(player(99, "world")), List.of())));
        var result = assembly.accept(new Message.SnapshotPage(8, 1, 2, List.of(player(2, "world")), List.of()));
        assertEquals(List.of(player(1, "world"), player(2, "world")), result.players());
    }

    @Test
    void malformedPageSeriesNeverPublishesAPartialRoster() throws IOException {
        SnapshotAssembly changed = new SnapshotAssembly(1, "world", 1);
        changed.accept(new Message.SnapshotPage(1, 0, 2, List.of(), List.of()));
        assertThrows(IOException.class, () -> changed.accept(new Message.SnapshotPage(1, 1, 3, List.of(), List.of())));
        SnapshotAssembly duplicate = new SnapshotAssembly(2, "world", 1);
        duplicate.accept(new Message.SnapshotPage(2, 0, 2, List.of(player(1, "world")), List.of()));
        assertThrows(IOException.class, () -> duplicate.accept(new Message.SnapshotPage(2, 1, 2,
                List.of(player(1, "world")), List.of())));
    }

    @Test
    void emptyOnlineRosterIsACompleteSnapshot() throws IOException {
        var result = new SnapshotAssembly(1, "world", 1)
                .accept(new Message.SnapshotPage(1, 0, 1, List.of(), List.of()));
        assertTrue(result.players().isEmpty());
        assertTrue(result.scenes().isEmpty());
    }
}
