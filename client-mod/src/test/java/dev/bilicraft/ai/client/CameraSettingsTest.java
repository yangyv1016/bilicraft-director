package dev.bilicraft.ai.client;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class CameraSettingsTest {
    @TempDir Path directory;

    @Test
    void oldConfigurationKeepsRadiusAndSpeedAndAddsDefaultMotionParameters() throws Exception {
        Path path = directory.resolve("camera.properties");
        Files.writeString(path, "camera.radius=16\ncamera.speed=3.5\n");
        CameraSettings settings = new CameraSettings(path);
        settings.load(LoggerFactory.getLogger(getClass()));
        assertEquals(16, settings.radius);
        assertEquals(3.5, settings.speed);
        assertEquals(CameraMotion.Mode.AUTO, settings.mode);
        assertEquals(4, settings.heightOffset);
        assertEquals(8, settings.motionAmplitude);
        assertEquals(30, settings.turnRateDegreesPerSecond);
    }

    @Test
    void allMotionParametersSurviveAtomicSaveAndReload() {
        Path path = directory.resolve("nested/camera.properties");
        CameraSettings saved = new CameraSettings(path);
        saved.mode = CameraMotion.Mode.CRANE;
        saved.radius = 18;
        saved.speed = 4;
        saved.heightOffset = 7;
        saved.motionAmplitude = 10;
        saved.turnRateDegreesPerSecond = 24;
        assertTrue(saved.save(LoggerFactory.getLogger(getClass())));
        CameraSettings loaded = new CameraSettings(path);
        loaded.load(LoggerFactory.getLogger(getClass()));
        assertEquals(saved.mode, loaded.mode);
        assertEquals(saved.radius, loaded.radius);
        assertEquals(saved.speed, loaded.speed);
        assertEquals(saved.heightOffset, loaded.heightOffset);
        assertEquals(saved.motionAmplitude, loaded.motionAmplitude);
        assertEquals(saved.turnRateDegreesPerSecond, loaded.turnRateDegreesPerSecond);
    }

    @Test
    void invalidSavedFieldDoesNotPartiallyApplyOtherFields() throws Exception {
        Path path = directory.resolve("camera.properties");
        Files.writeString(path, "camera.radius=20\ncamera.speed=4\ncamera.turn-rate=NaN\n");
        CameraSettings settings = new CameraSettings(path);
        settings.load(LoggerFactory.getLogger(getClass()));
        assertEquals(12, settings.radius);
        assertEquals(2.5, settings.speed);
    }

    @Test
    void validationRejectsNonFiniteValuesAndOutOfRangeParameters() {
        assertThrows(IllegalArgumentException.class, () -> CameraSettings.validate(CameraMotion.Mode.AUTO, 12, 2.5, 13, 8, 30));
        assertThrows(IllegalArgumentException.class, () -> CameraSettings.validate(CameraMotion.Mode.AUTO, 12, 2.5, 4, 25, 30));
        assertThrows(IllegalArgumentException.class, () -> CameraSettings.validate(CameraMotion.Mode.AUTO, 12, 2.5, 4, 8, Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> CameraSettings.validate(null, 12, 2.5, 4, 8, 30));
    }
}
