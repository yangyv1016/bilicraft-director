package dev.bilicraft.ai.client;

import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Properties;

final class CameraSettings {
    private final Path path;
    CameraMotion.Mode mode = CameraMotion.Mode.AUTO;
    double radius = 12;
    double speed = 2.5;
    double heightOffset = 4;
    double motionAmplitude = 8;
    double turnRateDegreesPerSecond = 30;

    CameraSettings() {
        this(FabricLoader.getInstance().getConfigDir().resolve("bilicraft-ai.properties"));
    }

    CameraSettings(Path path) {
        this.path = path;
    }

    static void validate(CameraMotion.Mode mode, double radius, double speed, double height,
                         double amplitude, double turnRate) {
        if (mode == null || !Double.isFinite(radius) || radius < 6 || radius > 24
                || !Double.isFinite(speed) || speed < 0.5 || speed > 5
                || !Double.isFinite(height) || height < 1 || height > 12
                || !Double.isFinite(amplitude) || amplitude < 2 || amplitude > 24
                || !Double.isFinite(turnRate) || turnRate < 6 || turnRate > 80) {
            throw new IllegalArgumentException("Camera settings outside supported range");
        }
    }

    void load(Logger logger) {
        if (!Files.exists(path)) {
            return;
        }
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(path)) {
            properties.load(reader);
            double savedRadius = Double.parseDouble(properties.getProperty("camera.radius", "12"));
            double savedSpeed = Double.parseDouble(properties.getProperty("camera.speed", "2.5"));
            CameraMotion.Mode savedMode = CameraMotion.Mode.valueOf(properties.getProperty("camera.mode", "AUTO"));
            double savedHeight = Double.parseDouble(properties.getProperty("camera.height-offset", "4"));
            double savedAmplitude = Double.parseDouble(properties.getProperty("camera.motion-amplitude", "8"));
            double savedTurnRate = Double.parseDouble(properties.getProperty("camera.turn-rate", "30"));
            validate(savedMode, savedRadius, savedSpeed, savedHeight, savedAmplitude, savedTurnRate);
            mode = savedMode;
            radius = savedRadius;
            speed = savedSpeed;
            heightOffset = savedHeight;
            motionAmplitude = savedAmplitude;
            turnRateDegreesPerSecond = savedTurnRate;
        } catch (IOException | IllegalArgumentException exception) {
            logger.warn("Cannot read camera configuration; using defaults", exception);
        }
    }

    boolean save(Logger logger) {
        validate(mode, radius, speed, heightOffset, motionAmplitude, turnRateDegreesPerSecond);
        Properties properties = new Properties();
        properties.setProperty("camera.mode", mode.name());
        properties.setProperty("camera.radius", Double.toString(radius));
        properties.setProperty("camera.speed", Double.toString(speed));
        properties.setProperty("camera.height-offset", Double.toString(heightOffset));
        properties.setProperty("camera.motion-amplitude", Double.toString(motionAmplitude));
        properties.setProperty("camera.turn-rate", Double.toString(turnRateDegreesPerSecond));
        try {
            Files.createDirectories(path.getParent());
            Path temporary = Files.createTempFile(path.getParent(), "bilicraft-camera-", ".tmp");
            try {
                try (Writer writer = Files.newBufferedWriter(temporary)) {
                    properties.store(writer, "BiliCraft director camera settings");
                }
                try {
                    Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException exception) {
                    Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temporary);
            }
            return true;
        } catch (IOException exception) {
            logger.warn("Cannot save camera configuration", exception);
            return false;
        }
    }
}
