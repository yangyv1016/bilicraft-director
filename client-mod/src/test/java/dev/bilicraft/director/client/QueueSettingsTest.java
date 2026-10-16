package dev.bilicraft.director.client;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class QueueSettingsTest {
    @TempDir
    Path directory;

    @Test
    void missingFileUsesDocumentedDefaultsWithoutCreatingAFile() throws IOException {
        Path path = directory.resolve("queue.properties");
        QueueSettings settings = new QueueSettings(path);
        settings.load();
        assertTrue(settings.autoFill);
        assertEquals(4, settings.bufferSize);
        assertEquals(120, settings.sceneDurationSeconds);
        assertFalse(Files.exists(path));
    }

    @Test
    void savedSettingsSurviveAnotherInstanceAndDurationIsSeconds() throws IOException {
        Path path = directory.resolve("queue.properties");
        new QueueSettings(path).save(false, 12, 600);
        QueueSettings loaded = new QueueSettings(path);
        loaded.load();
        assertFalse(loaded.autoFill);
        assertEquals(12, loaded.bufferSize);
        assertEquals(600, loaded.sceneDurationSeconds);
        assertTrue(Files.readString(path).contains("scene-duration-seconds=600"));
        try (var files = Files.list(directory)) {
            assertEquals(1, files.count());
        }
    }

    @Test
    void malformedBooleanOrOutOfRangeNumbersDoNotPartiallyApply() throws IOException {
        Path path = directory.resolve("queue.properties");
        QueueSettings settings = new QueueSettings(path);
        for (String bad : new String[]{"auto-fill=yes", "auto-fill=false\nbuffer-size=0",
                "auto-fill=false\nscene-duration-seconds=601", "buffer-size=1.5"}) {
            Files.writeString(path, bad);
            assertThrows(IllegalArgumentException.class, settings::load);
            assertTrue(settings.autoFill);
            assertEquals(4, settings.bufferSize);
            assertEquals(120, settings.sceneDurationSeconds);
        }
    }

    @Test
    void invalidSavePreservesTheExistingFileAndRuntimeValues() throws IOException {
        Path path = directory.resolve("queue.properties");
        QueueSettings settings = new QueueSettings(path);
        settings.save(false, 3, 45);
        String before = Files.readString(path);
        assertThrows(IllegalArgumentException.class, () -> settings.save(true, 13, 120));
        assertEquals(before, Files.readString(path));
        assertFalse(settings.autoFill);
        assertEquals(3, settings.bufferSize);
        assertEquals(45, settings.sceneDurationSeconds);
    }

    @Test
    void ioFailureDoesNotClaimThatChangedSettingsWereSaved() throws IOException {
        Path occupied = directory.resolve("not-a-directory");
        Files.writeString(occupied, "occupied");
        QueueSettings settings = new QueueSettings(occupied.resolve("queue.properties"));
        assertThrows(IOException.class, () -> settings.save(false, 3, 45));
        assertTrue(settings.autoFill);
        assertEquals(4, settings.bufferSize);
        assertEquals(120, settings.sceneDurationSeconds);
    }
}
