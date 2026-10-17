package dev.bilicraft.director.client;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Properties;

final class LocalControlSettings {
    private final Path path;
    int port = LocalControlServer.DEFAULT_PORT;
    boolean loadFailed;

    LocalControlSettings(Path path) {
        this.path = path;
    }

    void load() throws IOException {
        if (!Files.exists(path)) return;
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(path)) {
            properties.load(reader);
        }
        int savedPort = Integer.parseInt(properties.getProperty("port", Integer.toString(LocalControlServer.DEFAULT_PORT)));
        validate(savedPort);
        port = savedPort;
        loadFailed = false;
    }

    void save(int port) throws IOException {
        validate(port);
        Properties properties = new Properties();
        properties.setProperty("port", Integer.toString(port));
        Files.createDirectories(path.getParent());
        Path temporary = Files.createTempFile(path.getParent(), "bilicraft-director-web-", ".tmp");
        try {
            try (Writer writer = Files.newBufferedWriter(temporary)) {
                properties.store(writer, "BiliCraft director local control preferred port. Applied on next client start.");
            }
            try {
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
        this.port = port;
        loadFailed = false;
    }

    private static void validate(int port) {
        if (port < 1 || port > 65_535) throw new IllegalArgumentException("Local control port must be 1..65535");
    }
}
