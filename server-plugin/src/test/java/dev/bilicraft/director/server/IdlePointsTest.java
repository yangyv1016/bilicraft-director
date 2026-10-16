package dev.bilicraft.director.server;

import dev.bilicraft.director.protocol.Message;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class IdlePointsTest {
    @TempDir Path directory;
    private static final UUID WORLD = new UUID(1, 2);
    private static final UUID OTHER = new UUID(3, 4);
    private static final Message.IdlePose POINT = new Message.IdlePose(10.25, 122.75, -30.125, 450, -23.5f);

    @Test
    void poseSurvivesRestartAndWorldRenameAndUpdatesOnlySelectedUuid() throws IOException {
        Path file = directory.resolve("idle-points.yml");
        IdlePoints store = new IdlePoints(file);
        assertNull(store.get(WORLD));
        store.set(WORLD, POINT);
        store.set(OTHER, new Message.IdlePose(3, 80, 4, -90, 45));
        assertEquals(POINT, new IdlePoints(file).get(WORLD));
        assertEquals(90, store.get(WORLD).yaw());
        store.set(WORLD, new Message.IdlePose(20, 90, 30, 0, 0));
        assertEquals(20, new IdlePoints(file).get(WORLD).x());
        store.set(WORLD, null);
        store.set(WORLD, null);
        assertNull(new IdlePoints(file).get(WORLD));
        assertNotNull(new IdlePoints(file).get(OTHER));
    }

    @Test
    void malformedFileIsReportedAndPreserved() throws IOException {
        Path file = directory.resolve("idle-points.yml");
        String prefix = "version: 1\nworlds:\n  '" + WORLD + "':\n    x: 1\n    y: 80\n    z: 2\n    yaw: 0\n";
        for (String content : new String[]{"version: 2\nworlds: {}\n", "version: 1\nworlds: []\n", prefix,
                prefix + "    pitch: 91\n", prefix + "    pitch: 0\n    extra: true\n"}) {
            Files.writeString(file, content);
            assertThrows(IOException.class, () -> new IdlePoints(file));
            assertEquals(content, Files.readString(file));
        }
    }

    @Test
    void saveFailureRollsBackInMemoryAndPreservesPreviousTarget() throws IOException {
        Path file = directory.resolve("idle-points.yml");
        IdlePoints store = new IdlePoints(file);
        store.set(WORLD, POINT);
        Files.delete(file);
        Files.createDirectory(file);
        Files.writeString(file.resolve("keep.txt"), "keep");
        assertThrows(IOException.class, () -> store.set(WORLD, null));
        assertEquals(POINT, store.get(WORLD));
        assertThrows(IOException.class, () -> store.set(OTHER, POINT));
        assertNull(store.get(OTHER));
        assertEquals("keep", Files.readString(file.resolve("keep.txt")));
        try (var paths = Files.list(directory)) {
            assertTrue(paths.noneMatch(path -> path.toString().endsWith(".tmp")));
        }
    }
}
