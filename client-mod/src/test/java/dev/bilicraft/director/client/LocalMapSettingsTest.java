package dev.bilicraft.director.client;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LocalMapSettingsTest {
    @TempDir
    Path directory;

    @Test
    void missingMappingDoesNotCreateConfigurationOrInventAWorld() throws IOException {
        Path missing = directory.resolve("missing");
        LocalMapSettings.Profile profile = new LocalMapSettings(missing).load("server.example:25565", "minecraft:hyperionvoid");
        assertFalse(profile.configured());
        assertEquals("", profile.baseUrl());
        assertEquals("", profile.dynmapWorld());
        assertFalse(Files.exists(missing));
    }

    @Test
    void exactServerPortAndWorldAreIsolatedAndSurviveReload() throws IOException {
        LocalMapSettings settings = new LocalMapSettings(directory);
        settings.save("server.example:25565", "minecraft:hyperionvoid", " https://map.example/hyperion ", " HyperionVoid ");
        settings.save("server.example:25566", "minecraft:hyperionvoid", "https://other.example/", "Other");
        settings.save("server.example:25565", "minecraft:artist", "https://map.example/art/", "artist");
        LocalMapSettings reloaded = new LocalMapSettings(directory);
        assertEquals(new LocalMapSettings.Profile("https://map.example/hyperion/", "HyperionVoid"),
                reloaded.load("server.example:25565", "minecraft:hyperionvoid"));
        assertEquals("Other", reloaded.load("server.example:25566", "minecraft:hyperionvoid").dynmapWorld());
        assertEquals("artist", reloaded.load("server.example:25565", "minecraft:artist").dynmapWorld());
        assertFalse(reloaded.load("SERVER.example:25565", "minecraft:hyperionvoid").configured());
        assertFalse(reloaded.load("server.example:25565", "minecraft:other").configured());
    }

    @Test
    void clearingOnlyRemovesTheExactMapping() throws IOException {
        LocalMapSettings settings = new LocalMapSettings(directory);
        settings.save("one", "world", "https://map.example/", "one");
        settings.save("one", "other", "https://map.example/", "other");
        assertFalse(settings.save("one", "world", " ", "").configured());
        assertFalse(settings.load("one", "world").configured());
        assertTrue(settings.load("one", "other").configured());
        assertFalse(settings.save("unknown", "world", "", "").configured());
    }

    @Test
    void invalidAddressOrPartialClearCannotOverwriteGoodMapping() throws IOException {
        LocalMapSettings settings = new LocalMapSettings(directory);
        LocalMapSettings.Profile original = settings.save("one", "world", "https://map.example/", "Valid");
        for (String bad : List.of("", "file:///tmp/", "https://user:password@map.example/", "https://map.example/?key=x",
                "https://map.example/#part", "https://map.example/a b", "https://map.example/" + "x".repeat(1_024))) {
            assertThrows(IllegalArgumentException.class, () -> settings.save("one", "world", bad, "Valid"));
            assertEquals(original, settings.load("one", "world"));
        }
        for (String badWorld : List.of("", "a\nb", "x".repeat(257))) {
            assertThrows(IllegalArgumentException.class, () -> settings.save("one", "world", "https://map.example/", badWorld));
        }
        assertEquals(original, settings.load("one", "world"));
    }

    @Test
    void corruptedFileIsReportedAndPreservedUntilExplicitReplacement() throws IOException {
        LocalMapSettings settings = new LocalMapSettings(directory);
        settings.save("one", "world", "https://map.example/", "Valid");
        Path path;
        try (var paths = Files.list(directory)) { path = paths.findFirst().orElseThrow(); }
        for (String invalid : List.of("base-url=https://map.example/", "base-url=https://map.example/\ndynmap-world=Valid\nunknown=yes",
                "base-url=file:///tmp/\ndynmap-world=Valid")) {
            Files.writeString(path, invalid);
            assertThrows(IllegalArgumentException.class, () -> settings.load("one", "world"));
            assertEquals(invalid, Files.readString(path));
        }
        settings.save("one", "world", "https://map.example/", "Replaced");
        assertEquals("Replaced", settings.load("one", "world").dynmapWorld());
    }

    @Test
    void identitiesCannotCollideThroughSeparatorOrPathTraversal() throws IOException {
        LocalMapSettings settings = new LocalMapSettings(directory);
        assertThrows(IllegalArgumentException.class, () -> settings.save("a\0b", "world", "https://map.example/", "Valid"));
        assertThrows(IllegalArgumentException.class, () -> settings.load("one", ""));
        settings.save("../../outside", "../world", "https://map.example/", "Valid");
        try (var paths = Files.list(directory)) {
            var files = paths.toList();
            assertEquals(1, files.size());
            assertTrue(files.getFirst().getFileName().toString().matches("[0-9a-f]{64}\\.properties"));
        }
    }

    @Test
    void filesystemFailureDoesNotReplacePreviousMapping() throws IOException {
        LocalMapSettings settings = new LocalMapSettings(directory);
        settings.save("one", "world", "https://map.example/", "Valid");
        Path path;
        try (var paths = Files.list(directory)) { path = paths.findFirst().orElseThrow(); }
        Files.delete(path);
        Files.createDirectory(path);
        Files.writeString(path.resolve("occupied"), "keep");
        assertThrows(IOException.class, () -> settings.save("one", "world", "https://map.example/", "Changed"));
        assertEquals("keep", Files.readString(path.resolve("occupied")));
        try (var paths = Files.list(directory)) { assertEquals(List.of(path), paths.toList()); }
    }
}
