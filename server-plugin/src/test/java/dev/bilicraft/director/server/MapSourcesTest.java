package dev.bilicraft.director.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class MapSourcesTest {
    private static final UUID WORLD = UUID.fromString("f3baec1d-b51a-4b5a-8328-870ef4badc91");
    @TempDir Path directory;

    @Test
    void persistExplicitUuidMappingNormalizeBaseAndKeepEmptyAfterRemoval() throws IOException {
        Path file = directory.resolve("map-sources.yml");
        MapSources sources = new MapSources(file);
        assertNull(sources.get(WORLD));
        sources.put(new MapSources.Source(WORLD, "world", "https://map.example/s2/hyperion", "world"));
        MapSources.Source saved = new MapSources(file).get(WORLD);
        assertEquals("https://map.example/s2/hyperion/", saved.baseUrl());
        assertEquals("world", saved.dynmapWorld());
        sources.remove(WORLD);
        assertNull(new MapSources(file).get(WORLD));
    }

    @Test
    void invalidEndpointAndCorruptStorageFailExplicitly() throws IOException {
        for (String url : new String[]{"file:///local", "https://user:password@map.example/", "https://map.example/?token=x", "https://map.example/#x"}) {
            assertThrows(IllegalArgumentException.class, () -> new MapSources.Source(WORLD, "world", url, "Paralon"));
        }
        Path file = directory.resolve("map-sources.yml");
        for (String content : new String[]{"version: 2\nworlds: {}\n", "version: 1\nworlds: []\n", "version: 1\nworlds:\n  unknown:\n    name: world\n"}) {
            Files.writeString(file, content);
            assertThrows(IOException.class, () -> new MapSources(file));
            assertEquals(content, Files.readString(file));
        }
    }

    @Test
    void moveFailureRollsBackMemoryAndLeavesExistingTarget() throws IOException {
        Path file = directory.resolve("map-sources.yml");
        // A real filesystem move failure without mocking the persistence implementation.
        MapSources blocked = new MapSources(file);
        blocked.put(new MapSources.Source(WORLD, "world", "http://127.0.0.1:25590/", "Paralon"));
        Files.delete(file);
        Files.createDirectory(file);
        Files.writeString(file.resolve("keep.txt"), "keep");
        assertThrows(IOException.class, () -> blocked.remove(WORLD));
        assertNotNull(blocked.get(WORLD));
        assertEquals("keep", Files.readString(file.resolve("keep.txt")));
        try (var files = Files.list(directory)) {
            assertTrue(files.noneMatch(path -> path.getFileName().toString().endsWith(".tmp")));
        }
    }
}
