package dev.bilicraft.ai.client;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.net.URI;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.EnumMap;
import java.util.Map;
import java.util.Properties;

final class AiSettings {
    enum Provider {
        DEEPSEEK("https://api.deepseek.com", "bilicraftai.ai.deepseek"),
        OPENAI("https://api.openai.com/v1", "bilicraftai.ai.openai");

        final String defaultBaseUrl;
        final String translationKey;

        Provider(String defaultBaseUrl, String translationKey) {
            this.defaultBaseUrl = defaultBaseUrl;
            this.translationKey = translationKey;
        }
    }

    // A regular class intentionally avoids a record's generated toString exposing the key.
    static final class Profile {
        String baseUrl;
        String model;
        String apiKey;

        Profile(String baseUrl, String model, String apiKey) {
            this.baseUrl = baseUrl;
            this.model = model;
            this.apiKey = apiKey;
        }

        boolean complete() {
            return !baseUrl.isBlank() && !model.isBlank() && !apiKey.isBlank();
        }
    }

    private final Path path;
    final Map<Provider, Profile> profiles = new EnumMap<>(Provider.class);
    Provider selected = Provider.DEEPSEEK;
    boolean saved;
    boolean loadFailed;
    boolean enabled;
    long revision;

    AiSettings(Path path) {
        this.path = path;
        for (Provider provider : Provider.values()) {
            profiles.put(provider, new Profile(provider.defaultBaseUrl, "", ""));
        }
    }

    void load() {
        if (!Files.exists(path)) {
            return;
        }
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(path)) {
            properties.load(reader);
            Provider loadedProvider = Provider.valueOf(properties.getProperty("provider", Provider.DEEPSEEK.name()));
            Map<Provider, Profile> loaded = new EnumMap<>(Provider.class);
            for (Provider provider : Provider.values()) {
                String prefix = provider.name().toLowerCase(java.util.Locale.ROOT);
                Profile profile = new Profile(properties.getProperty(prefix + ".base-url", provider.defaultBaseUrl),
                        properties.getProperty(prefix + ".model", ""), properties.getProperty(prefix + ".api-key", ""));
                validate(profile);
                loaded.put(provider, profile);
            }
            String strategy = properties.getProperty("enabled", "false");
            if (!strategy.equals("true") && !strategy.equals("false")) {
                throw new IllegalArgumentException("Invalid AI enabled setting");
            }
            boolean loadedEnabled = Boolean.parseBoolean(strategy);
            if (loadedEnabled && !loaded.get(loadedProvider).complete()) {
                throw new IllegalArgumentException("An enabled strategy requires a complete profile");
            }
            selected = loadedProvider;
            profiles.putAll(loaded);
            enabled = loadedEnabled;
            revision++;
            saved = true;
            loadFailed = false;
        } catch (IOException | IllegalArgumentException exception) {
            // Malformed local files may contain secrets, including inside exception messages.
            loadFailed = true;
        }
    }

    void save(Provider provider, Map<Provider, Profile> drafts) throws IOException {
        save(provider, drafts, enabled);
    }

    void save(Provider provider, Map<Provider, Profile> drafts, boolean strategyEnabled) throws IOException {
        Properties properties = new Properties();
        properties.setProperty("provider", provider.name());
        properties.setProperty("enabled", Boolean.toString(strategyEnabled));
        for (Provider item : Provider.values()) {
            Profile profile = drafts.get(item);
            validate(profile);
            String prefix = item.name().toLowerCase(java.util.Locale.ROOT);
            properties.setProperty(prefix + ".base-url", profile.baseUrl);
            properties.setProperty(prefix + ".model", profile.model);
            properties.setProperty(prefix + ".api-key", profile.apiKey);
        }
        if (strategyEnabled && !drafts.get(provider).complete()) {
            throw new IllegalArgumentException("An enabled strategy requires a model, address and key");
        }
        Files.createDirectories(path.getParent());
        // Both the final filename and temporary filename match the repository's secret ignore rule.
        Path temporary = Files.createTempFile(path.getParent(), "bilicraft-ai-", ".local.properties");
        try {
            try (Writer writer = Files.newBufferedWriter(temporary)) {
                properties.store(writer, "Local AI configuration. Contains credentials. Do not share.");
            }
            try {
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
        selected = provider;
        enabled = strategyEnabled;
        revision++;
        for (Provider item : Provider.values()) {
            Profile profile = drafts.get(item);
            profiles.put(item, new Profile(profile.baseUrl, profile.model, profile.apiKey));
        }
        saved = true;
        loadFailed = false;
    }

    static void validate(Profile profile) {
        if (profile.baseUrl.length() > 1024 || profile.model.length() > 256 || profile.apiKey.length() > 4096) {
            throw new IllegalArgumentException("AI setting exceeds field limit");
        }
        if (profile.baseUrl.codePoints().anyMatch(Character::isISOControl)
                || profile.model.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("AI settings cannot contain control characters");
        }
        if (profile.apiKey.chars().anyMatch(character -> character < 0x21 || character > 0x7E)) {
            throw new IllegalArgumentException("API key must contain only visible ASCII characters without spaces");
        }
        if (!profile.baseUrl.isBlank()) {
            URI uri = URI.create(profile.baseUrl);
            if (!("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null || uri.getRawUserInfo() != null
                    || uri.getRawQuery() != null || uri.getRawFragment() != null) {
                throw new IllegalArgumentException("Invalid API base address");
            }
        }
    }
}
