package dev.bilicraft.director.server;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;

/** UUID preferences and absolute expiry times survive reconnects and server restarts. */
final class PrivacyStore {
    private final Path file;
    private final Set<UUID> blacklist = new HashSet<>();
    private final Map<UUID, Long> cooldownUntil = new HashMap<>();

    PrivacyStore(Path file) throws IOException {
        this.file = file;
        if (Files.notExists(file)) {
            return;
        }
        Properties values = new Properties();
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            values.load(reader);
        } catch (IllegalArgumentException exception) {
            throw new IOException("Malformed privacy data: " + file, exception);
        }
        if (!"1".equals(values.getProperty("version"))) {
            throw new IOException("Unknown or missing privacy data version: " + file);
        }
        for (String key : values.stringPropertyNames()) {
            if (key.equals("version")) {
                continue;
            }
            String value = values.getProperty(key);
            try {
                if (key.startsWith("blacklist.") && value.equals("true")) {
                    blacklist.add(UUID.fromString(key.substring("blacklist.".length())));
                } else if (key.startsWith("cooldown.")) {
                    UUID id = UUID.fromString(key.substring("cooldown.".length()));
                    long expiry = Long.parseLong(value);
                    if (expiry < 0) {
                        throw new IllegalArgumentException("Negative expiry");
                    }
                    cooldownUntil.put(id, expiry);
                } else {
                    throw new IllegalArgumentException("Unrecognized preference");
                }
            } catch (IllegalArgumentException exception) {
                throw new IOException("Invalid privacy entry: " + key, exception);
            }
        }
    }

    boolean blacklisted(UUID target) {
        return blacklist.contains(target);
    }

    long cooldownUntil(UUID target) {
        return cooldownUntil.getOrDefault(target, 0L);
    }

    boolean available(UUID target, long now) {
        return !blacklisted(target) && cooldownUntil(target) <= now;
    }

    void setBlacklist(UUID target, boolean blocked) throws IOException {
        if (blocked) {
            blacklist.add(target);
        } else {
            blacklist.remove(target);
        }
        save();
    }

    void setCooldown(UUID target, long until) throws IOException {
        cooldownUntil.put(target, until);
        save();
    }

    private void save() throws IOException {
        Properties values = new Properties();
        values.setProperty("version", "1");
        blacklist.forEach(id -> values.setProperty("blacklist." + id, "true"));
        cooldownUntil.forEach((id, until) -> values.setProperty("cooldown." + id, Long.toString(until)));
        Files.createDirectories(file.getParent());
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
            values.store(writer, "BiliCraft Director privacy data; expiry values are epoch milliseconds");
        }
        try {
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
