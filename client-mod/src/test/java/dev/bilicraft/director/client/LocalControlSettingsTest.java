package dev.bilicraft.director.client;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class LocalControlSettingsTest {
    @TempDir Path directory;

    @Test
    void missingConfigurationUsesStablePreferredPortWithoutWritingAFile() throws Exception {
        Path path = directory.resolve("web.properties");
        LocalControlSettings settings = new LocalControlSettings(path);
        settings.load();
        assertEquals(16_360, settings.port);
        assertFalse(Files.exists(path));
    }

    @Test
    void savedPortSurvivesReloadIncludingBothBoundaries() throws Exception {
        Path path = directory.resolve("nested/web.properties");
        for (int port : new int[]{1, 16_361, 65_535}) {
            LocalControlSettings settings = new LocalControlSettings(path);
            settings.save(port);
            LocalControlSettings reloaded = new LocalControlSettings(path);
            reloaded.load();
            assertEquals(port, reloaded.port);
        }
    }

    @Test
    void invalidFileDoesNotApplyRandomOrOutOfRangePort() throws Exception {
        Path path = directory.resolve("web.properties");
        for (String port : new String[]{"0", "-1", "65536", "random"}) {
            Files.writeString(path, "port=" + port + "\n");
            LocalControlSettings settings = new LocalControlSettings(path);
            assertThrows(IllegalArgumentException.class, settings::load);
            assertEquals(LocalControlServer.DEFAULT_PORT, settings.port);
        }
    }

    @Test
    void rejectedSavePreservesTheExistingFileAndPort() throws Exception {
        Path path = directory.resolve("web.properties");
        LocalControlSettings settings = new LocalControlSettings(path);
        settings.save(16_361);
        String before = Files.readString(path);
        for (int port : new int[]{0, -1, 65_536}) {
            assertThrows(IllegalArgumentException.class, () -> settings.save(port));
            assertEquals(before, Files.readString(path));
            assertEquals(16_361, settings.port);
        }
    }
}
