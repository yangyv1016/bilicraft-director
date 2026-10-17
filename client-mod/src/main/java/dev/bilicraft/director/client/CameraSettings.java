package dev.bilicraft.director.client;

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
    CameraMotion.Mode mode = CameraMotion.Mode.ORBIT;
    double radius = 136;
    double speed = 2.5;
    double heightY = 122;
    double playerHeightOffset = 4;
    double motionAmplitude = 8;
    double turnRateDegreesPerSecond = 30;
    double maxPitchDegrees = 45;
    boolean autoDistance = true;
    double minGroundClearance = 16;

    CameraSettings() {
        this(FabricLoader.getInstance().getConfigDir().resolve("bilicraft-director.properties"));
    }

    CameraSettings(Path path) {
        this.path = path;
    }

    static void validate(CameraMotion.Mode mode, double radius, double speed, double heightY,
                         double playerHeightOffset, double amplitude, double turnRate, double maxPitchDegrees,
                         double minGroundClearance) {
        if (mode == null || !Double.isFinite(radius) || radius < 6 || radius > 256
                || !Double.isFinite(speed) || speed < 0.5 || speed > 5
                || !Double.isFinite(heightY) || heightY < -64 || heightY > 256
                || !Double.isFinite(playerHeightOffset) || playerHeightOffset < 1 || playerHeightOffset > 12
                || !Double.isFinite(amplitude) || amplitude < 2 || amplitude > 24
                || !Double.isFinite(turnRate) || turnRate < 6 || turnRate > 80
                || !Double.isFinite(maxPitchDegrees) || maxPitchDegrees < 5 || maxPitchDegrees > 80
                || !Double.isFinite(minGroundClearance) || minGroundClearance < 0 || minGroundClearance > 128) {
            throw new IllegalArgumentException("Camera settings outside supported range");
        }
    }

    void load(Logger logger) {
        Properties properties = new Properties();
        try {
            if (path.getFileName().toString().equals("bilicraft-director.properties") && Files.notExists(path)) {
                Path legacy = path.resolveSibling("bilicraft-ai.properties");
                if (!Files.notExists(legacy)) {
                    Files.move(legacy, path);
                }
            }
            if (Files.notExists(path)) {
                return;
            }
            try (Reader reader = Files.newBufferedReader(path)) {
                properties.load(reader);
            }
            double savedRadius = Double.parseDouble(properties.getProperty("camera.radius", "136"));
            double savedSpeed = Double.parseDouble(properties.getProperty("camera.speed", "2.5"));
            CameraMotion.Mode savedMode = CameraMotion.Mode.valueOf(properties.getProperty("camera.mode", "ORBIT"));
            double savedHeightY = Double.parseDouble(properties.getProperty("camera.height-y", "122"));
            double savedPlayerHeight = Double.parseDouble(properties.getProperty("camera.player-height-offset",
                    properties.getProperty("camera.height-offset", "4")));
            double savedAmplitude = Double.parseDouble(properties.getProperty("camera.motion-amplitude", "8"));
            double savedTurnRate = Double.parseDouble(properties.getProperty("camera.turn-rate", "30"));
            double savedMaxPitch = Double.parseDouble(properties.getProperty("camera.max-pitch", "45"));
            boolean savedAutoDistance = switch (properties.getProperty("camera.auto-distance", "true")) {
                case "true" -> true;
                case "false" -> false;
                default -> throw new IllegalArgumentException("Invalid camera auto-distance setting");
            };
            double savedGroundClearance = Double.parseDouble(properties.getProperty("camera.min-ground-clearance", "16"));
            validate(savedMode, savedRadius, savedSpeed, savedHeightY, savedPlayerHeight, savedAmplitude,
                    savedTurnRate, savedMaxPitch, savedGroundClearance);
            mode = savedMode;
            radius = savedRadius;
            speed = savedSpeed;
            heightY = savedHeightY;
            playerHeightOffset = savedPlayerHeight;
            motionAmplitude = savedAmplitude;
            turnRateDegreesPerSecond = savedTurnRate;
            maxPitchDegrees = savedMaxPitch;
            autoDistance = savedAutoDistance;
            minGroundClearance = savedGroundClearance;
        } catch (IOException | IllegalArgumentException exception) {
            logger.warn("Cannot read camera configuration; using defaults", exception);
        }
    }

    boolean save(Logger logger) {
        validate(mode, radius, speed, heightY, playerHeightOffset, motionAmplitude,
                turnRateDegreesPerSecond, maxPitchDegrees, minGroundClearance);
        Properties properties = new Properties();
        properties.setProperty("camera.mode", mode.name());
        properties.setProperty("camera.radius", Double.toString(radius));
        properties.setProperty("camera.speed", Double.toString(speed));
        properties.setProperty("camera.height-y", Double.toString(heightY));
        properties.setProperty("camera.player-height-offset", Double.toString(playerHeightOffset));
        properties.setProperty("camera.motion-amplitude", Double.toString(motionAmplitude));
        properties.setProperty("camera.turn-rate", Double.toString(turnRateDegreesPerSecond));
        properties.setProperty("camera.max-pitch", Double.toString(maxPitchDegrees));
        properties.setProperty("camera.auto-distance", Boolean.toString(autoDistance));
        properties.setProperty("camera.min-ground-clearance", Double.toString(minGroundClearance));
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
