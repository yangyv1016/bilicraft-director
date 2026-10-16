package dev.bilicraft.ai.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class PrivacyStoreTest {
    @TempDir
    Path directory;

    @Test
    void permanentBlacklistAndAbsoluteCooldownSurviveRestart() throws IOException {
        Path file = directory.resolve("privacy.properties");
        UUID target = UUID.randomUUID();
        PrivacyStore first = new PrivacyStore(file);
        first.setBlacklist(target, true);
        first.setCooldown(target, 2_000_000_000_000L);

        PrivacyStore restarted = new PrivacyStore(file);
        assertTrue(restarted.blacklisted(target));
        assertFalse(restarted.available(target, 2_000_000_000_001L));
        restarted.setBlacklist(target, false);
        assertFalse(restarted.available(target, 1_999_999_999_999L));
        assertTrue(restarted.available(target, 2_000_000_000_000L));
        assertFalse(new PrivacyStore(file).blacklisted(target));
    }

    @Test
    void malformedPrivacyFileFailsInsteadOfClearingOptOuts() throws IOException {
        Path file = directory.resolve("privacy.properties");
        String corrupted = "version=1\nblacklist.not-a-uuid=true\n";
        Files.writeString(file, corrupted);
        assertThrows(IOException.class, () -> new PrivacyStore(file));
        assertEquals(corrupted, Files.readString(file));
    }

    @Test
    void missingVersionAndInvalidExpiryFailClosed() throws IOException {
        Path file = directory.resolve("privacy.properties");
        Files.writeString(file, "blacklist." + UUID.randomUUID() + "=true\n");
        assertThrows(IOException.class, () -> new PrivacyStore(file));
        Files.writeString(file, "version=1\ncooldown." + UUID.randomUUID() + "=-1\n");
        assertThrows(IOException.class, () -> new PrivacyStore(file));
    }

    @Test
    void failedWriteIsReportedAndInMemoryOptOutStaysBlocked() throws IOException {
        Path obstructed = directory.resolve("blocked");
        PrivacyStore store = new PrivacyStore(obstructed.resolve("privacy.properties"));
        Files.writeString(obstructed, "not a directory");
        UUID target = UUID.randomUUID();
        assertThrows(IOException.class, () -> store.setBlacklist(target, true));
        assertTrue(store.blacklisted(target));
    }
}
