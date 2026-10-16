package dev.bilicraft.director.client;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Properties;

final class QueueSettings {
    private final Path path;
    boolean autoFill = true;
    int bufferSize = 4;
    int sceneDurationSeconds = 120;
    boolean loadFailed;

    QueueSettings(Path path) {
        this.path = path;
    }

    void load() throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(path)) {
            properties.load(reader);
        }
        String fill = properties.getProperty("auto-fill", "true");
        if (!fill.equals("true") && !fill.equals("false")) {
            throw new IllegalArgumentException("auto-fill must be true or false");
        }
        boolean loadedAutoFill = Boolean.parseBoolean(fill);
        int loadedBuffer = Integer.parseInt(properties.getProperty("buffer-size", "4"));
        int loadedDuration = Integer.parseInt(properties.getProperty("scene-duration-seconds", "120"));
        validate(loadedBuffer, loadedDuration);
        autoFill = loadedAutoFill;
        bufferSize = loadedBuffer;
        sceneDurationSeconds = loadedDuration;
        loadFailed = false;
    }

    void save(boolean fill, int buffer, int duration) throws IOException {
        validate(buffer, duration);
        Properties properties = new Properties();
        properties.setProperty("auto-fill", Boolean.toString(fill));
        properties.setProperty("buffer-size", Integer.toString(buffer));
        properties.setProperty("scene-duration-seconds", Integer.toString(duration));
        Files.createDirectories(path.getParent());
        Path temporary = Files.createTempFile(path.getParent(), "bilicraft-director-queue-", ".tmp");
        try {
            try (Writer writer = Files.newBufferedWriter(temporary)) {
                properties.store(writer, "BiliCraft director queue. Scene duration is measured in seconds.");
            }
            try {
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
        autoFill = fill;
        bufferSize = buffer;
        sceneDurationSeconds = duration;
        loadFailed = false;
    }

    private static void validate(int buffer, int duration) {
        if (buffer < 1 || buffer > 12 || duration < 30 || duration > 600) {
            throw new IllegalArgumentException("Queue settings outside supported range");
        }
    }
}
