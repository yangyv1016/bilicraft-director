package dev.bilicraft.ai.client;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;

import static org.junit.jupiter.api.Assertions.*;

class AiSettingsTest {
    @TempDir Path directory;

    @Test
    void strategyIsDisabledUntilExplicitlySavedAndBothProviderSecretsSurviveSwitching() throws Exception {
        Path path = directory.resolve("test.local.properties");
        AiSettings settings = new AiSettings(path);
        assertFalse(settings.enabled);
        var drafts = new EnumMap<>(settings.profiles);
        drafts.put(AiSettings.Provider.DEEPSEEK, new AiSettings.Profile("https://api.example.test/v1", "ds-test", "synthetic-ds"));
        drafts.put(AiSettings.Provider.OPENAI, new AiSettings.Profile("https://other.example.test/v1", "gpt-test", "synthetic-gpt"));
        settings.save(AiSettings.Provider.DEEPSEEK, drafts, true);
        assertEquals(1, settings.revision);
        AiSettings loaded = new AiSettings(path);
        loaded.load();
        assertTrue(loaded.enabled);
        assertTrue(loaded.saved);
        assertFalse(loaded.loadFailed);
        loaded.save(AiSettings.Provider.OPENAI, loaded.profiles, false);
        AiSettings again = new AiSettings(path);
        again.load();
        assertEquals(AiSettings.Provider.OPENAI, again.selected);
        assertFalse(again.enabled);
        assertEquals("synthetic-ds", again.profiles.get(AiSettings.Provider.DEEPSEEK).apiKey);
        assertEquals("synthetic-gpt", again.profiles.get(AiSettings.Provider.OPENAI).apiKey);
        try (var files = Files.list(directory)) {
            assertEquals(1, files.count(), "atomic temporary credentials must be removed");
        }
    }

    @Test
    void incompleteProfilesCannotBeEnabledAndFailureDoesNotOverwriteExistingFileOrRevision() throws Exception {
        Path path = directory.resolve("test.local.properties");
        AiSettings settings = new AiSettings(path);
        settings.save(AiSettings.Provider.DEEPSEEK, settings.profiles, false);
        String original = Files.readString(path);
        assertThrows(IllegalArgumentException.class, () -> settings.save(AiSettings.Provider.DEEPSEEK, settings.profiles, true));
        assertFalse(settings.enabled);
        assertEquals(1, settings.revision);
        assertEquals(original, Files.readString(path));
    }

    @Test
    void modelsTestMayBeConfiguredWithoutAChatModelWhileOldFilesDefaultToDisabled() throws Exception {
        Path path = directory.resolve("test.local.properties");
        Files.writeString(path, "provider=DEEPSEEK\ndeepseek.base-url=https://example.test\ndeepseek.api-key=synthetic\n");
        AiSettings settings = new AiSettings(path);
        settings.load();
        assertTrue(settings.saved);
        assertFalse(settings.enabled);
        assertFalse(settings.profiles.get(settings.selected).complete());
    }

    @Test
    void corruptEnabledSettingAndControlCharactersCannotPartiallyApplyASecretFile() throws Exception {
        Path path = directory.resolve("test.local.properties");
        Files.writeString(path, "provider=OPENAI\nenabled=not-boolean\nopenai.api-key=synthetic\n");
        AiSettings settings = new AiSettings(path);
        settings.load();
        assertTrue(settings.loadFailed);
        assertFalse(settings.saved);
        assertEquals(AiSettings.Provider.DEEPSEEK, settings.selected);
        assertEquals("", settings.profiles.get(AiSettings.Provider.OPENAI).apiKey);
        assertThrows(IllegalArgumentException.class, () -> AiSettings.validate(
                new AiSettings.Profile("https://example.test", "m", "synthetic\r\nInjected: value")));
    }

    @Test
    void nonAsciiOrWhitespaceKeysCannotReplaceSavedMemoryOrFile() throws Exception {
        Path path = directory.resolve("test.local.properties");
        AiSettings settings = new AiSettings(path);
        var drafts = new EnumMap<>(settings.profiles);
        drafts.put(AiSettings.Provider.DEEPSEEK,
                new AiSettings.Profile("https://example.test/v1", "model-test", "synthetic-old-header-token"));
        settings.save(AiSettings.Provider.DEEPSEEK, drafts, true);
        byte[] original = Files.readAllBytes(path);

        for (String invalid : new String[]{"synthetic key", " synthetic", "synthetic ", "synthetic\tkey", "synthetic-密钥", "synthetic\u00A0key", "synthetic\uD800"}) {
            var attempted = new EnumMap<>(settings.profiles);
            attempted.put(AiSettings.Provider.OPENAI, new AiSettings.Profile("https://other.example.test", "other-model", invalid));
            assertThrows(IllegalArgumentException.class, () -> settings.save(AiSettings.Provider.OPENAI, attempted, false));
            assertArrayEquals(original, Files.readAllBytes(path));
            assertEquals(AiSettings.Provider.DEEPSEEK, settings.selected);
            assertEquals("synthetic-old-header-token", settings.profiles.get(AiSettings.Provider.DEEPSEEK).apiKey);
            assertEquals("", settings.profiles.get(AiSettings.Provider.OPENAI).apiKey);
            assertEquals(1, settings.revision);
            assertTrue(settings.enabled);
            assertTrue(settings.saved);
            assertFalse(settings.loadFailed);
        }
        try (var files = Files.list(directory)) {
            assertEquals(1, files.count(), "rejected input must not write even a temporary credential file");
        }
    }

    @Test
    void loadingInvalidHeaderKeyFailsWithoutApplyingOrRewritingTheFile() throws Exception {
        Path path = directory.resolve("test.local.properties");
        for (String invalid : new String[]{"synthetic key", "synthetic-密钥"}) {
            String contents = "provider=OPENAI\nopenai.api-key=" + invalid + "\n";
            Files.writeString(path, contents);
            AiSettings settings = new AiSettings(path);
            settings.load();
            assertTrue(settings.loadFailed);
            assertFalse(settings.saved);
            assertFalse(settings.enabled);
            assertEquals(0, settings.revision);
            assertEquals(AiSettings.Provider.DEEPSEEK, settings.selected);
            assertEquals("", settings.profiles.get(AiSettings.Provider.OPENAI).apiKey);
            assertEquals(contents, Files.readString(path));
        }
        AiSettings.validate(new AiSettings.Profile("https://example.test", "model", ""));
        AiSettings.validate(new AiSettings.Profile("https://example.test", "model", "!~synthetic-token"));
    }
}
