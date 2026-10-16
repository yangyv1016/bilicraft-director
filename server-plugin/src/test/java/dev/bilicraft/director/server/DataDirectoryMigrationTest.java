package dev.bilicraft.director.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class DataDirectoryMigrationTest {
    @TempDir Path directory;

    @Test
    void migrationPreservesBlacklistCooldownAndAllConfigurationFiles() throws IOException {
        Path legacy = Files.createDirectory(directory.resolve("BiliCraftAI"));
        Path target = directory.resolve("BiliCraftDirector");
        UUID player = UUID.randomUUID();
        PrivacyStore privacy = new PrivacyStore(legacy.resolve("privacy.properties"));
        privacy.setBlacklist(player, true);
        privacy.setCooldown(player, 2_000_000_000_000L);
        for (String filename : new String[]{"config.yml", "worlds.yml", "map-sources.yml"}) {
            Files.writeString(legacy.resolve(filename), filename);
        }

        DirectorPlugin.migrateDataDirectory(target);

        assertFalse(Files.exists(legacy));
        PrivacyStore migrated = new PrivacyStore(target.resolve("privacy.properties"));
        assertTrue(migrated.blacklisted(player));
        assertEquals(2_000_000_000_000L, migrated.cooldownUntil(player));
        for (String filename : new String[]{"config.yml", "worlds.yml", "map-sources.yml"}) {
            assertEquals(filename, Files.readString(target.resolve(filename)));
        }
        DirectorPlugin.migrateDataDirectory(target);
        assertTrue(new PrivacyStore(target.resolve("privacy.properties")).blacklisted(player));
    }

    @Test
    void precreatedEmptyDirectoryDoesNotDiscardLegacyPrivacy() throws IOException {
        Path legacy = Files.createDirectory(directory.resolve("BiliCraftAI"));
        Files.writeString(legacy.resolve("privacy.properties"), "version=1\n");
        Path target = Files.createDirectory(directory.resolve("BiliCraftDirector"));

        DirectorPlugin.migrateDataDirectory(target);

        assertEquals("version=1\n", Files.readString(target.resolve("privacy.properties")));
        assertFalse(Files.exists(legacy));
    }

    @Test
    void conflictingDirectoriesAreLeftIntactInsteadOfChoosingOnePrivacyFile() throws IOException {
        Path legacy = Files.createDirectory(directory.resolve("BiliCraftAI"));
        Path target = Files.createDirectory(directory.resolve("BiliCraftDirector"));
        Files.writeString(legacy.resolve("privacy.properties"), "legacy privacy");
        Files.writeString(target.resolve("config.yml"), "new configuration");

        assertThrows(IOException.class, () -> DirectorPlugin.migrateDataDirectory(target));

        assertEquals("legacy privacy", Files.readString(legacy.resolve("privacy.properties")));
        assertEquals("new configuration", Files.readString(target.resolve("config.yml")));
        assertFalse(Files.exists(target.resolve("privacy.properties")));
    }

    @Test
    void corruptLegacyPrivacyStillFailsClosedAfterMigration() throws IOException {
        Path legacy = Files.createDirectory(directory.resolve("BiliCraftAI"));
        Path target = directory.resolve("BiliCraftDirector");
        Files.writeString(legacy.resolve("privacy.properties"), "broken privacy");

        DirectorPlugin.migrateDataDirectory(target);

        assertThrows(IOException.class, () -> new PrivacyStore(target.resolve("privacy.properties")));
        assertEquals("broken privacy", Files.readString(target.resolve("privacy.properties")));
    }

    @Test
    void freshInstallationDoesNotCreateOrAlterData() throws IOException {
        Path target = directory.resolve("BiliCraftDirector");
        DirectorPlugin.migrateDataDirectory(target);
        assertFalse(Files.exists(target));
    }
}
