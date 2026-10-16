package dev.bilicraft.director.client;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Properties;

/** Explicit local external-map mappings, isolated by the exact server address and world key. */
final class LocalMapSettings {
    private final Path directory;

    LocalMapSettings(Path directory) {
        this.directory = directory;
    }

    Profile load(String serverAddress, String worldKey) throws IOException {
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(pathFor(serverAddress, worldKey))) {
            properties.load(reader);
        } catch (NoSuchFileException exception) {
            return new Profile("", "");
        }
        if (!properties.stringPropertyNames().equals(java.util.Set.of("base-url", "dynmap-world"))) {
            throw new IllegalArgumentException("Invalid local map configuration fields");
        }
        return new Profile(properties.getProperty("base-url"), properties.getProperty("dynmap-world"));
    }

    Profile save(String serverAddress, String worldKey, String baseUrl, String dynmapWorld) throws IOException {
        Profile profile = new Profile(baseUrl, dynmapWorld);
        Path path = pathFor(serverAddress, worldKey);
        if (!profile.configured()) {
            Files.deleteIfExists(path);
            return profile;
        }
        Properties properties = new Properties();
        properties.setProperty("base-url", profile.baseUrl());
        properties.setProperty("dynmap-world", profile.dynmapWorld());
        Files.createDirectories(directory);
        Path temporary = Files.createTempFile(directory, "map-", ".tmp");
        try {
            try (Writer writer = Files.newBufferedWriter(temporary)) {
                properties.store(writer, "BiliCraft director local external-map mapping.");
            }
            try {
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
        return profile;
    }

    private Path pathFor(String serverAddress, String worldKey) {
        if (serverAddress.isBlank() || worldKey.isBlank() || serverAddress.indexOf('\0') >= 0 || worldKey.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Missing local map identity");
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((serverAddress + '\0' + worldKey).getBytes(StandardCharsets.UTF_8));
            return directory.resolve(HexFormat.of().formatHex(digest) + ".properties");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Java runtime is missing mandatory SHA-256 support", exception);
        }
    }

    record Profile(String baseUrl, String dynmapWorld) {
        Profile {
            baseUrl = baseUrl.trim();
            dynmapWorld = dynmapWorld.trim();
            if (baseUrl.isEmpty() != dynmapWorld.isEmpty() || baseUrl.length() > 1_024 || dynmapWorld.length() > 256) {
                throw new IllegalArgumentException("Map address and world must both be set or cleared");
            }
            if (!baseUrl.isEmpty()) {
                DynmapMapClient.scenePrefix(baseUrl, dynmapWorld, "local:validation");
                if (!baseUrl.endsWith("/")) baseUrl += "/";
                if (baseUrl.length() > 1_024) throw new IllegalArgumentException("Map address exceeds limit");
            }
        }

        boolean configured() {
            return !baseUrl.isEmpty();
        }
    }
}
