package dev.bilicraft.director.client;

import dev.bilicraft.director.protocol.Codec;
import dev.bilicraft.director.protocol.Message;
import dev.bilicraft.director.protocol.Message.SceneKind;
import dev.bilicraft.director.protocol.Message.SceneView;
import dev.bilicraft.director.protocol.Protocol;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalSceneCacheTest {
    private static final String SERVER = "server.example:25565";
    // Independently computed from the UTF-8 address, not from a cache implementation helper.
    private static final String CACHE_NAME = "abd773514c7924f1fe91e8293102a068eb4b7bc96aa9b4ef13dd776c9c97b079.bin";
    private static final SceneView VIEWPOINT = new SceneView("station", "车站", SceneKind.VIEWPOINT,
            "world", 12.5, 80, -24.5, 12);
    private static final SceneView AREA = new SceneView("station", "城区", SceneKind.AREA,
            "builds", -30, 90, 60, 48);

    @TempDir
    Path directory;

    @Test
    void legacyProductSignatureLoadsScenesAndNextSaveUsesDirectorSignature() throws IOException {
        byte[] legacy = Codec.encode(new Message.Snapshot(List.of(), List.of(VIEWPOINT)));
        ByteBuffer.wrap(legacy).putInt(0x42434149);
        Path file = directory.resolve(CACHE_NAME);
        Files.write(file, legacy);
        LocalSceneCache cache = new LocalSceneCache(directory);

        assertEquals(List.of(VIEWPOINT), cache.load(SERVER));
        assertArrayEquals(legacy, Files.readAllBytes(file));
        cache.save(SERVER, List.of(AREA));
        assertEquals(Protocol.MAGIC, ByteBuffer.wrap(Files.readAllBytes(file)).getInt());
        assertEquals(List.of(AREA), cache.load(SERVER));
        assertThrows(IOException.class, () -> Codec.decode(legacy));
    }

    @Test
    void legacySignatureStillRejectsPlayerDataAndPreservesCorruptFile() throws IOException {
        Message.PlayerView player = new Message.PlayerView(UUID.randomUUID(), "Player", "world", 0, 100, 0, 0, 0, 1);
        byte[] legacy = Codec.encode(new Message.Snapshot(List.of(player), List.of(VIEWPOINT)));
        ByteBuffer.wrap(legacy).putInt(0x42434149);
        Path file = directory.resolve(CACHE_NAME);
        Files.write(file, legacy);

        assertThrows(IOException.class, () -> new LocalSceneCache(directory).load(SERVER));
        assertArrayEquals(legacy, Files.readAllBytes(file));
    }

    @Test
    void missingCacheReturnsEmptyWithoutCreatingFiles() throws IOException {
        Path missingDirectory = directory.resolve("missing");
        LocalSceneCache cache = new LocalSceneCache(missingDirectory);
        assertEquals(List.of(), cache.load(SERVER));
        assertFalse(Files.exists(missingDirectory));
    }

    @Test
    void scenesSurviveANewCacheInstanceWithWorldsAndKindsIntact() throws IOException {
        List<SceneView> scenes = List.of(VIEWPOINT, AREA);
        new LocalSceneCache(directory).save(SERVER, scenes);
        List<SceneView> reloaded = new LocalSceneCache(directory).load(SERVER);
        assertEquals(scenes, reloaded);
        assertThrows(UnsupportedOperationException.class, reloaded::clear);
    }

    @Test
    void eachExactServerAddressIsIsolatedIncludingThePort() throws IOException {
        LocalSceneCache cache = new LocalSceneCache(directory);
        cache.save(SERVER, List.of(VIEWPOINT));
        cache.save("server.example:25566", List.of(AREA));
        assertEquals(List.of(VIEWPOINT), cache.load(SERVER));
        assertEquals(List.of(AREA), cache.load("server.example:25566"));
        assertEquals(List.of(), cache.load("SERVER.example:25565"));
        assertEquals(List.of(), cache.load("other.example:25565"));
    }

    @Test
    void diskFormatUsesSha256NameAndContainsOnlyScenes() throws IOException {
        new LocalSceneCache(directory).save(SERVER, List.of(VIEWPOINT, AREA));
        Message.Snapshot snapshot = assertInstanceOf(Message.Snapshot.class,
                Codec.decode(Files.readAllBytes(directory.resolve(CACHE_NAME))));
        assertTrue(snapshot.players().isEmpty());
        assertEquals(List.of(VIEWPOINT, AREA), snapshot.scenes());
        try (var paths = Files.list(directory)) {
            assertEquals(List.of(directory.resolve(CACHE_NAME)), paths.toList());
        }
    }

    @Test
    void emptyAuthorizedSnapshotReplacesOldScenesWithoutLeavingTemporaryFiles() throws IOException {
        LocalSceneCache cache = new LocalSceneCache(directory);
        cache.save(SERVER, List.of(VIEWPOINT));
        cache.save(SERVER, List.of(AREA));
        assertEquals(List.of(AREA), cache.load(SERVER));
        cache.save(SERVER, List.of());
        assertEquals(List.of(), cache.load(SERVER));
        try (var paths = Files.list(directory)) {
            assertEquals(List.of(directory.resolve(CACHE_NAME)), paths.toList());
        }
    }

    @Test
    void corruptOversizedAndOldVersionFilesAreReportedWithoutRewritingThem() throws IOException {
        LocalSceneCache cache = new LocalSceneCache(directory);
        byte[] oldVersion = Codec.encode(new Message.Snapshot(List.of(), List.of(VIEWPOINT)));
        ByteBuffer.wrap(oldVersion).putShort(4, (short) 1);
        for (byte[] invalid : List.of(new byte[]{1, 2, 3}, new byte[Protocol.MAX_PACKET_BYTES + 1], oldVersion)) {
            Files.write(directory.resolve(CACHE_NAME), invalid);
            assertThrows(IOException.class, () -> cache.load(SERVER));
            assertArrayEquals(invalid, Files.readAllBytes(directory.resolve(CACHE_NAME)));
        }
    }

    @Test
    void refusesPlayerSnapshotsAndOtherMessageTypes() throws IOException {
        LocalSceneCache cache = new LocalSceneCache(directory);
        Message.PlayerView player = new Message.PlayerView(new UUID(1, 2), "Player", "world",
                0, 80, 0, 0, 0, 0);
        for (Message message : List.of(new Message.Heartbeat(),
                new Message.Snapshot(List.of(player), List.of(VIEWPOINT)))) {
            Files.write(directory.resolve(CACHE_NAME), Codec.encode(message));
            assertThrows(IOException.class, () -> cache.load(SERVER));
        }
    }

    @Test
    void invalidSceneDataCannotOverwriteAnExistingGoodCache() throws IOException {
        LocalSceneCache cache = new LocalSceneCache(directory);
        cache.save(SERVER, List.of(VIEWPOINT));
        byte[] original = Files.readAllBytes(directory.resolve(CACHE_NAME));
        SceneView invalidRadius = new SceneView("invalid", "Invalid", SceneKind.AREA, "world", 0, 80, 0, 5);
        assertThrows(IOException.class, () -> cache.save(SERVER, List.of(invalidRadius)));
        ArrayList<SceneView> tooManyScenes = new ArrayList<>();
        for (int index = 0; index <= Protocol.MAX_SCENES; index++) {
            tooManyScenes.add(new SceneView("s" + index, "S", SceneKind.AREA, "world", 0, 80, 0, 12));
        }
        assertThrows(IOException.class, () -> cache.save(SERVER, tooManyScenes));
        assertArrayEquals(original, Files.readAllBytes(directory.resolve(CACHE_NAME)));
        assertEquals(List.of(VIEWPOINT), cache.load(SERVER));
    }

    @Test
    void failedAtomicReplacementPreservesTheTargetAndCleansTheTemporaryFile() throws IOException {
        Path target = Files.createDirectory(directory.resolve(CACHE_NAME));
        Files.writeString(target.resolve("existing"), "keep");
        LocalSceneCache cache = new LocalSceneCache(directory);
        assertThrows(IOException.class, () -> cache.save(SERVER, List.of(VIEWPOINT)));
        assertEquals("keep", Files.readString(target.resolve("existing")));
        try (var paths = Files.list(directory)) {
            assertEquals(List.of(target), paths.toList());
        }
    }
}
