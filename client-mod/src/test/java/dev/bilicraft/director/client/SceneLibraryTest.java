package dev.bilicraft.director.client;

import dev.bilicraft.director.protocol.Codec;
import dev.bilicraft.director.protocol.Message;
import dev.bilicraft.director.protocol.Message.SceneKind;
import dev.bilicraft.director.protocol.Message.SceneView;
import dev.bilicraft.director.protocol.Protocol;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SceneLibraryTest {
    private static final String WORLD = "minecraft:hyperionvoid";

    @TempDir
    Path directory;

    @Test
    void all924ImportedScenesRemainInTheLiveLibraryIncludingDistantAndUnavailableCoordinates() {
        ArrayList<SceneView> imported = new ArrayList<>();
        for (int index = 0; index < 924; index++) {
            imported.add(new SceneView("dynmap:" + index, "景点" + index, SceneKind.VIEWPOINT,
                    WORLD, 10_000 + index * 1_000, index == 923 ? 2_000 : 80, -10_000, 12));
        }

        List<SceneView> library = SceneLibrary.merge(WORLD, List.of(), imported);

        assertEquals(imported, library, "Distance and execution availability must not remove library entries");
        assertEquals(924, library.size());
        assertThrows(UnsupportedOperationException.class, library::clear);
    }

    @Test
    void sameWorldIdsAreDeduplicatedWithServerPriorityAndOtherWorldsAreExcluded() {
        SceneView server = new SceneView("shared", "服务端", SceneKind.AREA, WORLD, 0, 80, 0, 24);
        SceneView importedDuplicate = new SceneView("shared", "外链", SceneKind.VIEWPOINT, WORLD, 50_000, 80, 0, 12);
        SceneView imported = new SceneView("imported", "首个标记", SceneKind.VIEWPOINT, WORLD, 80_000, 80, 0, 12);
        SceneView laterDuplicate = new SceneView("imported", "重复标记", SceneKind.VIEWPOINT, WORLD, 90_000, 80, 0, 12);
        SceneView otherWorld = new SceneView("other", "其他世界", SceneKind.VIEWPOINT,
                "minecraft:overworld", 0, 80, 0, 12);

        assertEquals(List.of(server, imported), SceneLibrary.merge(WORLD, List.of(server, otherWorld),
                List.of(importedDuplicate, imported, laterDuplicate, otherWorld)));
    }

    @Test
    void a924SceneLiveLibraryUsesOnly64CachedScenesWithoutTruncatingTheLibrary() throws IOException {
        ArrayList<SceneView> imported = new ArrayList<>();
        for (int index = 0; index < 924; index++) {
            imported.add(new SceneView("dynmap:" + index, "景点" + index, SceneKind.VIEWPOINT,
                    WORLD, index * 10_000, 80, 0, 12));
        }
        List<SceneView> library = SceneLibrary.merge(WORLD, List.of(), imported);

        SceneLibrary.CacheSelection selection = SceneLibrary.selectCache(WORLD, library, List.of());
        LocalSceneCache cache = new LocalSceneCache(directory);
        cache.save("server.example:25577", selection.scenes());

        assertTrue(selection.limited());
        assertEquals(Protocol.MAX_SCENES, selection.scenes().size());
        assertEquals(library.subList(0, Protocol.MAX_SCENES), cache.load("server.example:25577"));
        assertEquals(imported, library);
        try (var paths = Files.list(directory)) {
            Message.Snapshot snapshot = assertInstanceOf(Message.Snapshot.class,
                    Codec.decode(Files.readAllBytes(paths.findFirst().orElseThrow())));
            assertTrue(snapshot.players().isEmpty());
            assertEquals(selection.scenes(), snapshot.scenes());
        }
    }

    @Test
    void cacheAlsoRespectsThe30KilobyteLimitBeforeReaching64Scenes() throws IOException {
        String world = "w".repeat(240);
        ArrayList<SceneView> imported = new ArrayList<>();
        for (int index = 0; index < 64; index++) {
            imported.add(new SceneView(String.format("%064d", index), "景".repeat(85), SceneKind.AREA,
                    world, index * 1_000, 80, 0, 48));
        }
        List<SceneView> library = SceneLibrary.merge(world, List.of(), imported);

        SceneLibrary.CacheSelection selection = SceneLibrary.selectCache(world, library, List.of());

        assertTrue(selection.limited());
        assertTrue(selection.scenes().size() < Protocol.MAX_SCENES);
        assertTrue(Codec.encode(new Message.Snapshot(List.of(), selection.scenes())).length <= Protocol.MAX_PACKET_BYTES);
        ArrayList<SceneView> oneTooMany = new ArrayList<>(selection.scenes());
        oneTooMany.add(library.get(selection.scenes().size()));
        assertThrows(IOException.class, () -> Codec.encode(new Message.Snapshot(List.of(), oneTooMany)));
        assertEquals(64, library.size());
    }

    @Test
    void currentWorldTakesCachePriorityAndThePreviousWorldCannotLeakIntoTheLiveLibrary() throws IOException {
        SceneView current = new SceneView("same-id", "当前", SceneKind.VIEWPOINT, WORLD, 10_000, 80, 0, 12);
        SceneView staleCurrent = new SceneView("stale", "已移除", SceneKind.VIEWPOINT, WORLD, 0, 80, 0, 12);
        SceneView other = new SceneView("same-id", "其他世界", SceneKind.VIEWPOINT,
                "minecraft:overworld", 20_000, 80, 0, 12);
        List<SceneView> previous = List.of(other, staleCurrent);
        List<SceneView> library = SceneLibrary.merge(WORLD, List.of(), List.of(current, other));

        SceneLibrary.CacheSelection selection = SceneLibrary.selectCache(WORLD, library, previous);

        assertEquals(List.of(current), library);
        assertEquals(List.of(current, other), selection.scenes());
        assertFalse(selection.limited());
        assertDoesNotThrow(() -> Codec.encode(new Message.Snapshot(List.of(), selection.scenes())));
    }

    @Test
    void clearingTheCurrentWorldKeepsOnlyOtherWorldsInTheFallbackCache() throws IOException {
        SceneView current = new SceneView("current", "当前", SceneKind.VIEWPOINT, WORLD, 0, 80, 0, 12);
        SceneView other = new SceneView("other", "其他", SceneKind.VIEWPOINT,
                "minecraft:overworld", 0, 80, 0, 12);

        SceneLibrary.CacheSelection selection = SceneLibrary.selectCache(WORLD, List.of(), List.of(current, other));

        assertEquals(List.of(other), selection.scenes());
        assertFalse(selection.limited());
        assertEquals(List.of(), SceneLibrary.merge(WORLD, List.of(), List.of(other)));
    }
}
