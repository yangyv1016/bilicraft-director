package dev.bilicraft.director.client;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class CameraSettingsTest {
    @TempDir Path directory;

    @Test
    void legacyCameraConfigurationMigratesWithSavedMotionParameters() throws Exception {
        Path legacy = directory.resolve("bilicraft-ai.properties");
        String configuration = "camera.mode=CRANE\ncamera.radius=18\ncamera.speed=4\n";
        Files.writeString(legacy, configuration);
        Path target = directory.resolve("bilicraft-director.properties");
        CameraSettings settings = new CameraSettings(target);

        settings.load(LoggerFactory.getLogger(getClass()));

        assertEquals(CameraMotion.Mode.CRANE, settings.mode);
        assertEquals(18, settings.radius);
        assertEquals(4, settings.speed);
        assertEquals(configuration, Files.readString(target));
        assertFalse(Files.exists(legacy));
    }

    @Test
    void existingDirectorCameraConfigurationIsNotReplacedByLegacySettings() throws Exception {
        Path legacy = directory.resolve("bilicraft-ai.properties");
        Path target = directory.resolve("bilicraft-director.properties");
        Files.writeString(legacy, "camera.radius=18\n");
        Files.writeString(target, "camera.radius=20\n");
        CameraSettings settings = new CameraSettings(target);

        settings.load(LoggerFactory.getLogger(getClass()));

        assertEquals(20, settings.radius);
        assertEquals("camera.radius=18\n", Files.readString(legacy));
        assertEquals("camera.radius=20\n", Files.readString(target));
    }

    @Test
    void oldConfigurationKeepsRadiusAndSpeedAndAddsDefaultMotionParameters() throws Exception {
        Path path = directory.resolve("camera.properties");
        Files.writeString(path, "camera.radius=16\ncamera.speed=3.5\n");
        CameraSettings settings = new CameraSettings(path);
        settings.load(LoggerFactory.getLogger(getClass()));
        assertEquals(16, settings.radius);
        assertEquals(3.5, settings.speed);
        assertEquals(CameraMotion.Mode.AUTO, settings.mode);
        assertEquals(80, settings.heightY);
        assertEquals(4, settings.playerHeightOffset);
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
        saved.heightY = 120;
        saved.playerHeightOffset = 7;
        saved.motionAmplitude = 10;
        saved.turnRateDegreesPerSecond = 24;
        assertTrue(saved.save(LoggerFactory.getLogger(getClass())));
        CameraSettings loaded = new CameraSettings(path);
        loaded.load(LoggerFactory.getLogger(getClass()));
        assertEquals(saved.mode, loaded.mode);
        assertEquals(saved.radius, loaded.radius);
        assertEquals(saved.speed, loaded.speed);
        assertEquals(saved.heightY, loaded.heightY);
        assertEquals(saved.playerHeightOffset, loaded.playerHeightOffset);
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
    void extendedCameraDistanceAndAbsoluteYBoundsSurviveSaveAndReload() {
        Path path = directory.resolve("camera.properties");
        CameraSettings settings = new CameraSettings(path);
        settings.radius = 256;
        settings.playerHeightOffset = 12;
        for (double y : new double[]{-64, 256}) {
            settings.heightY = y;
            assertTrue(settings.save(LoggerFactory.getLogger(getClass())));
            CameraSettings loaded = new CameraSettings(path);
            loaded.load(LoggerFactory.getLogger(getClass()));

            assertEquals(256, loaded.radius);
            assertEquals(y, loaded.heightY);
            assertEquals(12, loaded.playerHeightOffset);
            assertEquals(2.5, loaded.speed);
            assertEquals(8, loaded.motionAmplitude);
        }
    }

    @Test
    void legacyRelativeHeightMigratesOnlyToPlayerOffsetAndNeverToAbsoluteY() throws Exception {
        Path path = directory.resolve("camera.properties");
        Files.writeString(path, "camera.radius=18\ncamera.height-offset=9\n");
        CameraSettings settings = new CameraSettings(path);
        settings.load(LoggerFactory.getLogger(getClass()));

        assertEquals(18, settings.radius);
        assertEquals(80, settings.heightY);
        assertEquals(9, settings.playerHeightOffset);
        assertTrue(settings.save(LoggerFactory.getLogger(getClass())));
        String saved = Files.readString(path);
        assertTrue(saved.contains("camera.height-y=80.0"));
        assertTrue(saved.contains("camera.player-height-offset=9.0"));
        assertFalse(saved.contains("camera.height-offset="));
    }

    @Test
    void newHeightFieldsTakePrecedenceOverLegacyRelativeHeight() throws Exception {
        Path path = directory.resolve("camera.properties");
        Files.writeString(path, "camera.height-y=120\ncamera.player-height-offset=7\ncamera.height-offset=9\n");
        CameraSettings settings = new CameraSettings(path);
        settings.load(LoggerFactory.getLogger(getClass()));

        assertEquals(120, settings.heightY);
        assertEquals(7, settings.playerHeightOffset);
    }

    @Test
    void validationRejectsNonFiniteValuesAndOutOfRangeParameters() {
        assertThrows(IllegalArgumentException.class, () -> CameraSettings.validate(CameraMotion.Mode.AUTO, 257, 2.5, 80, 4, 8, 30));
        assertThrows(IllegalArgumentException.class, () -> CameraSettings.validate(CameraMotion.Mode.AUTO, 12, 2.5, 257, 4, 8, 30));
        assertThrows(IllegalArgumentException.class, () -> CameraSettings.validate(CameraMotion.Mode.AUTO, 12, 2.5, -65, 4, 8, 30));
        assertThrows(IllegalArgumentException.class, () -> CameraSettings.validate(CameraMotion.Mode.AUTO, Double.NaN, 2.5, 80, 4, 8, 30));
        assertThrows(IllegalArgumentException.class, () -> CameraSettings.validate(CameraMotion.Mode.AUTO, 12, 2.5, Double.POSITIVE_INFINITY, 4, 8, 30));
        assertThrows(IllegalArgumentException.class, () -> CameraSettings.validate(CameraMotion.Mode.AUTO, 12, 2.5, 80, 0, 8, 30));
        assertThrows(IllegalArgumentException.class, () -> CameraSettings.validate(CameraMotion.Mode.AUTO, 12, 2.5, 80, 13, 8, 30));
        assertThrows(IllegalArgumentException.class, () -> CameraSettings.validate(CameraMotion.Mode.AUTO, 12, 2.5, 80, 4, 25, 30));
        assertThrows(IllegalArgumentException.class, () -> CameraSettings.validate(CameraMotion.Mode.AUTO, 12, 2.5, 80, 4, 8, Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> CameraSettings.validate(null, 12, 2.5, 80, 4, 8, 30));
    }
}
