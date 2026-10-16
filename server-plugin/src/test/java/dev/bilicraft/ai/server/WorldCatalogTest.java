package dev.bilicraft.ai.server;

import dev.bilicraft.ai.protocol.Message.SceneKind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldCatalogTest {
    private static final UUID FIRST_WORLD = UUID.fromString("f3baec1d-b51a-4b5a-8328-870ef4badc91");
    private static final UUID SECOND_WORLD = UUID.fromString("dac4ebff-05a5-32d9-8b0b-ecefa1409983");
    private static final SceneConfiguration.ConfiguredScene SCENE = new SceneConfiguration.ConfiguredScene(
            "station", "车站", SceneKind.VIEWPOINT, 12.5, 80, -24.5, 12);

    @TempDir
    Path directory;

    @Test
    void missingFileIsUninitializedAndDoesNotCreateOrImportWorlds() throws IOException {
        Path file = directory.resolve("worlds.yml");
        WorldCatalog catalog = new WorldCatalog(file);
        assertFalse(catalog.initialized());
        assertTrue(catalog.profiles().isEmpty());
        assertNull(catalog.get(FIRST_WORLD));
        assertFalse(Files.exists(file));
    }

    @Test
    void firstWriteReloadsTheUuidSceneAndExclusionWithoutMutableLists() throws IOException {
        Path file = directory.resolve("nested/worlds.yml");
        WorldCatalog.Profile profile = new WorldCatalog.Profile(FIRST_WORLD, "Paralon", List.of(SCENE),
                List.of(new SceneConfiguration.Bounds(100, 120, 30, 50)));
        WorldCatalog catalog = new WorldCatalog(file);
        catalog.put(profile);
        assertTrue(catalog.initialized());

        WorldCatalog reloaded = new WorldCatalog(file);
        assertTrue(reloaded.initialized());
        assertEquals(profile, reloaded.get(FIRST_WORLD));
        assertEquals(List.of(profile), List.copyOf(reloaded.profiles()));
        assertThrows(UnsupportedOperationException.class, () -> reloaded.profiles().clear());
        assertThrows(UnsupportedOperationException.class, () -> reloaded.get(FIRST_WORLD).scenes().clear());
        assertThrows(UnsupportedOperationException.class, () -> reloaded.get(FIRST_WORLD).exclusions().clear());
    }

    @Test
    void removingTheLastWorldLeavesAnInitializedEmptyAllowlistAcrossRestart() throws IOException {
        Path file = directory.resolve("worlds.yml");
        WorldCatalog catalog = new WorldCatalog(file);
        catalog.put(new WorldCatalog.Profile(FIRST_WORLD, "Paralon", List.of(SCENE), List.of()));
        catalog.remove(FIRST_WORLD);

        WorldCatalog reloaded = new WorldCatalog(file);
        assertTrue(reloaded.initialized());
        assertTrue(reloaded.profiles().isEmpty());
        assertNull(reloaded.get(FIRST_WORLD));
        assertTrue(Files.exists(file));
    }

    @Test
    void explicitlySavingAnEmptyCatalogAlsoDisablesLegacyInitialization() throws IOException {
        Path file = directory.resolve("worlds.yml");
        WorldCatalog catalog = new WorldCatalog(file);
        catalog.save();
        assertTrue(catalog.initialized());
        WorldCatalog reloaded = new WorldCatalog(file);
        assertTrue(reloaded.initialized());
        assertTrue(reloaded.profiles().isEmpty());
    }

    @Test
    void renamingAWorldKeepsItsUuidAndOtherWorldsMayReuseItsSceneId() throws IOException {
        Path file = directory.resolve("worlds.yml");
        WorldCatalog catalog = new WorldCatalog(file);
        catalog.put(new WorldCatalog.Profile(FIRST_WORLD, "Paralon", List.of(SCENE), List.of()));
        catalog.put(new WorldCatalog.Profile(SECOND_WORLD, "artist", List.of(SCENE), List.of()));
        catalog.put(new WorldCatalog.Profile(FIRST_WORLD, "ParalonRenamed", List.of(SCENE), List.of()));

        WorldCatalog reloaded = new WorldCatalog(file);
        assertEquals(2, reloaded.profiles().size());
        assertEquals(FIRST_WORLD, reloaded.get(FIRST_WORLD).id());
        assertEquals("ParalonRenamed", reloaded.get(FIRST_WORLD).lastKnownName());
        assertEquals("artist", reloaded.get(SECOND_WORLD).lastKnownName());
        assertEquals("station", reloaded.get(FIRST_WORLD).scenes().getFirst().id());
        assertEquals("station", reloaded.get(SECOND_WORLD).scenes().getFirst().id());
    }

    @Test
    void rejectsMalformedYamlAndInvalidCatalogShapesWithoutRewritingTheFile() throws IOException {
        Path file = directory.resolve("worlds.yml");
        for (String invalid : List.of(
                "version: 1\nworlds: [\n",
                "worlds: {}\n",
                "version: 2\nworlds: {}\n",
                "version: 1\nworlds: []\n",
                "version: 1\nworlds: {}\nextra: true\n",
                "version: 1\nworlds:\n  invalid-uuid: {}\n")) {
            Files.writeString(file, invalid);
            assertThrows(IOException.class, () -> new WorldCatalog(file));
            assertEquals(invalid, Files.readString(file));
        }
    }

    @Test
    void rejectsDuplicateWorldNamesAndDuplicateUuidSpellings() throws IOException {
        Path file = directory.resolve("worlds.yml");
        String twoEntries = """
                version: 1
                worlds:
                  %s:
                    name: Paralon
                    scenes: []
                    exclude-regions: []
                  %s:
                    name: %s
                    scenes: []
                    exclude-regions: []
                """;
        Files.writeString(file, twoEntries.formatted(FIRST_WORLD, SECOND_WORLD, "Paralon"));
        assertThrows(IOException.class, () -> new WorldCatalog(file));
        Files.writeString(file, twoEntries.formatted(FIRST_WORLD,
                FIRST_WORLD.toString().toUpperCase(java.util.Locale.ROOT), "artist"));
        assertThrows(IOException.class, () -> new WorldCatalog(file));
    }

    @Test
    void rejectsInvalidSceneConfigurationWithinAWorld() throws IOException {
        Path file = directory.resolve("worlds.yml");
        String scene = """
                - id: station
                  name: Station
                  kind: viewpoint
                  x: 0
                  y: 80
                  z: 0
                  radius: 12
                """;
        String document = """
                version: 1
                worlds:
                  %s:
                    name: Paralon
                    scenes:
                %s    exclude-regions: []
                """;
        Files.writeString(file, document.formatted(FIRST_WORLD, scene.indent(6)));
        assertEquals(1, new WorldCatalog(file).get(FIRST_WORLD).scenes().size());
        for (String invalid : List.of(scene.replace("viewpoint", "player"),
                scene.replace("radius: 12", "radius: 5"), scene.replace("x: 0", "x: invalid"), scene + scene)) {
            Files.writeString(file, document.formatted(FIRST_WORLD, invalid.indent(6)));
            assertThrows(IOException.class, () -> new WorldCatalog(file));
        }
    }

    @Test
    void duplicateNamePutCannotOverwriteTheExistingCatalog() throws IOException {
        Path file = directory.resolve("worlds.yml");
        WorldCatalog catalog = new WorldCatalog(file);
        catalog.put(new WorldCatalog.Profile(FIRST_WORLD, "Paralon", List.of(SCENE), List.of()));
        byte[] original = Files.readAllBytes(file);
        assertThrows(IOException.class, () -> catalog.put(new WorldCatalog.Profile(
                SECOND_WORLD, "Paralon", List.of(), List.of())));
        assertArrayEquals(original, Files.readAllBytes(file));
        assertNull(catalog.get(SECOND_WORLD));
        assertEquals(1, new WorldCatalog(file).profiles().size());
    }

    @Test
    void failedMoveKeepsTheLastGoodFileAndCleansItsTemporaryFile() throws IOException {
        Path file = directory.resolve("worlds.yml");
        WorldCatalog catalog = new WorldCatalog(file);
        WorldCatalog.Profile originalProfile = new WorldCatalog.Profile(
                FIRST_WORLD, "Paralon", List.of(SCENE), List.of());
        catalog.put(originalProfile);
        byte[] original = Files.readAllBytes(file);
        Path lastGood = directory.resolve("last-good.yml");
        Files.move(file, lastGood);
        Files.createDirectory(file);
        Files.writeString(file.resolve("obstruction"), "keep");

        assertThrows(IOException.class, () -> catalog.put(new WorldCatalog.Profile(
                SECOND_WORLD, "artist", List.of(), List.of())));
        assertNull(catalog.get(SECOND_WORLD));
        assertThrows(IOException.class, () -> catalog.put(new WorldCatalog.Profile(
                FIRST_WORLD, "renamed", List.of(), List.of())));
        assertEquals(originalProfile, catalog.get(FIRST_WORLD));
        assertArrayEquals(original, Files.readAllBytes(lastGood));
        assertEquals("keep", Files.readString(file.resolve("obstruction")));
        try (var paths = Files.list(directory)) {
            assertFalse(paths.anyMatch(path -> path.getFileName().toString().endsWith(".tmp")));
        }
    }

    @Test
    void failedRemoveRestoresTheOriginalProfileInMemoryAndLeavesTheLastGoodFile() throws IOException {
        Path file = directory.resolve("worlds.yml");
        WorldCatalog.Profile profile = new WorldCatalog.Profile(
                FIRST_WORLD, "Paralon", List.of(SCENE), List.of());
        WorldCatalog catalog = new WorldCatalog(file);
        catalog.put(profile);
        byte[] original = Files.readAllBytes(file);
        Path lastGood = directory.resolve("last-good.yml");
        Files.move(file, lastGood);
        Files.createDirectory(file);
        Files.writeString(file.resolve("obstruction"), "keep");

        assertThrows(IOException.class, () -> catalog.remove(FIRST_WORLD));
        assertEquals(profile, catalog.get(FIRST_WORLD));
        assertEquals(List.of(profile), List.copyOf(catalog.profiles()));
        assertArrayEquals(original, Files.readAllBytes(lastGood));
        assertEquals("keep", Files.readString(file.resolve("obstruction")));
        try (var paths = Files.list(directory)) {
            assertFalse(paths.anyMatch(path -> path.getFileName().toString().endsWith(".tmp")));
        }
    }
}
