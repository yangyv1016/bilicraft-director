package dev.bilicraft.director.client;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class CameraMotionTest {
    @TempDir Path directory;
    private final CameraMotion.Point center = new CameraMotion.Point(0, 80, 0);
    private final CameraMotion.Point camera = new CameraMotion.Point(12, 84, 0);

    private CameraMotion.Subject scene() {
        return new CameraMotion.Subject("scene", center, 0, false, false, false, 24);
    }

    private CameraSettings settings(CameraMotion.Mode mode) {
        CameraSettings settings = new CameraSettings(directory.resolve("camera.properties"));
        settings.mode = mode;
        settings.radius = 12;
        settings.heightY = camera.y();
        return settings;
    }

    @Test
    void comfortablePitchKeepsTheOriginalComposition() {
        CameraMotion.Shot original = new CameraMotion.Shot(new CameraMotion.Point(136, 122, 0), center);

        assertSame(original, CameraMotion.widenForPitch(original, original.position(), 0, 1.62, 45, 256));
    }

    @Test
    void steepPitchWidensAlongTheExistingSideStartingFiveDegreesBeforeItsLimit() {
        CameraSettings settings = settings(CameraMotion.Mode.STATIC);
        settings.heightY = 122;
        settings.maxPitchDegrees = 45;
        CameraMotion.Shot original = new CameraMotion.Shot(new CameraMotion.Point(7.2, 122, 9.6), center);

        CameraMotion.Shot widened = CameraMotion.widenForPitch(original, original.position(), 0,
                1.62, settings.maxPitchDegrees, 256);

        double radius = Math.hypot(widened.position().x(), widened.position().z());
        assertEquals(40, Math.toDegrees(Math.atan2(122 + 1.62 - center.y(), radius)), 1.0E-9);
        assertEquals(0.75, widened.position().x() / widened.position().z(), 1.0E-9);
        assertEquals(122, widened.position().y());
        assertSame(original.lookAt(), widened.lookAt());
        assertEquals(new CameraMotion.Point(7.2, 122, 9.6), original.position());
        assertEquals(12, settings.radius, "execution must not overwrite the user's saved distance");
        assertEquals(122, settings.heightY);
        assertEquals(45, settings.maxPitchDegrees);
    }

    @Test
    void lookingUpAndLookingDownUseTheSamePitchMagnitude() {
        CameraMotion.Point position = new CameraMotion.Point(12, 122, 0);
        CameraMotion.Shot down = new CameraMotion.Shot(position, new CameraMotion.Point(0, 80, 0));
        CameraMotion.Shot up = new CameraMotion.Shot(position, new CameraMotion.Point(0, 167.24, 0));

        CameraMotion.Shot widenedDown = CameraMotion.widenForPitch(down, position, 0, 1.62, 45, 256);
        CameraMotion.Shot widenedUp = CameraMotion.widenForPitch(up, position, 0, 1.62, 45, 256);

        assertTrue(widenedDown.position().x() > position.x());
        assertEquals(widenedDown.position().x(), widenedUp.position().x(), 1.0E-9);
        assertSame(up.lookAt(), widenedUp.lookAt());
    }

    @Test
    void temporaryLiftAndAHigherActualCameraBothContributeWithoutChangingThePlannedY() {
        CameraMotion.Shot original = new CameraMotion.Shot(camera, center);
        CameraMotion.Shot lifted = CameraMotion.widenForPitch(original, camera, 10, 1.62, 45, 256);
        CameraMotion.Shot alreadyHigher = CameraMotion.widenForPitch(original,
                new CameraMotion.Point(12, 100, 0), 10, 1.62, 45, 256);

        assertEquals((84 + 10 + 1.62 - 80) / Math.tan(Math.toRadians(40)), lifted.position().x(), 1.0E-9);
        assertEquals((100 + 1.62 - 80) / Math.tan(Math.toRadians(40)), alreadyHigher.position().x(), 1.0E-9);
        assertEquals(84, lifted.position().y());
        assertEquals(84, alreadyHigher.position().y());
    }

    @Test
    void automaticDistanceRespectsTheSeparateSceneAndPlayerEnvelopes() {
        CameraMotion.Shot original = new CameraMotion.Shot(new CameraMotion.Point(12, 500, 0), center);

        CameraMotion.Shot scene = CameraMotion.widenForPitch(original, original.position(), 0, 1.62, 45, 256);
        CameraMotion.Shot player = CameraMotion.widenForPitch(original, original.position(), 0, 1.62, 45, 24);

        assertEquals(256, scene.position().x());
        assertEquals(24, player.position().x());
        assertEquals(500, scene.position().y());
        assertEquals(500, player.position().y());
        CameraMotion.Shot alreadyAtLimit = new CameraMotion.Shot(scene.position(), center);
        assertSame(alreadyAtLimit, CameraMotion.widenForPitch(alreadyAtLimit, scene.position(), 0, 1.62, 45, 256));
    }

    @Test
    void lowestPitchLimitKeepsAFiniteFiveDegreeWideningThreshold() {
        CameraMotion.Shot original = new CameraMotion.Shot(camera, center);

        CameraMotion.Shot widened = CameraMotion.widenForPitch(original, camera, 0, 1.62, 5, 256);

        assertEquals((84 + 1.62 - 80) / Math.tan(Math.toRadians(5)), widened.position().x(), 1.0E-9);
        assertTrue(Double.isFinite(widened.position().x()));
    }

    @Test
    void zeroHorizontalShotUsesTheActualSideOrPositiveXWhenBothAreCentered() {
        CameraMotion.Shot original = new CameraMotion.Shot(new CameraMotion.Point(0, 122, 0), center);

        CameraMotion.Shot actualSide = CameraMotion.widenForPitch(original,
                new CameraMotion.Point(0, 122, -12), 0, 1.62, 45, 256);
        CameraMotion.Shot centered = CameraMotion.widenForPitch(original, original.position(), 0, 1.62, 45, 256);

        assertEquals(0, actualSide.position().x());
        assertTrue(actualSide.position().z() < 0);
        assertEquals(0, centered.position().z());
        assertTrue(centered.position().x() > 0);
        for (CameraMotion.Shot shot : new CameraMotion.Shot[]{actualSide, centered}) {
            assertTrue(Double.isFinite(shot.position().x()));
            assertTrue(Double.isFinite(shot.position().z()));
            assertEquals(122, shot.position().y());
        }
    }

    @Test
    void allModesProduceFiniteShotsAcrossSeveralCycles() {
        for (CameraMotion.Mode mode : CameraMotion.Mode.values()) {
            CameraSettings settings = settings(mode);
            CameraMotion motion = new CameraMotion();
            for (int tick = 0; tick < 5_000; tick++) {
                CameraMotion.Shot shot = motion.sample(scene(), camera, settings);
                for (double value : new double[]{shot.position().x(), shot.position().y(), shot.position().z(),
                        shot.lookAt().x(), shot.lookAt().y(), shot.lookAt().z()}) {
                    assertTrue(Double.isFinite(value), mode.name());
                }
            }
        }
    }

    @Test
    void truckDollyAndCraneMoveAlongDifferentAxes() {
        CameraMotion.Shot dolly = advance(CameraMotion.Mode.DOLLY);
        CameraMotion.Shot truck = advance(CameraMotion.Mode.TRUCK);
        CameraMotion.Shot crane = advance(CameraMotion.Mode.CRANE);
        assertTrue(dolly.position().x() < 12);
        assertEquals(0, dolly.position().z());
        assertEquals(12 * 0.8, truck.position().x());
        assertTrue(truck.position().z() > 0);
        assertEquals(camera.x(), crane.position().x());
        assertEquals(camera.z(), crane.position().z());
        assertTrue(crane.position().y() > camera.y());
    }

    private CameraMotion.Shot advance(CameraMotion.Mode mode) {
        CameraMotion motion = new CameraMotion();
        CameraSettings settings = settings(mode);
        CameraMotion.Shot shot = null;
        for (int tick = 0; tick < 100; tick++) shot = motion.sample(scene(), camera, settings);
        return shot;
    }

    @Test
    void panAndStaticKeepCameraAnchoredWhileOnlyPanSweepsTheLookPoint() {
        CameraMotion.Shot pan = advance(CameraMotion.Mode.PAN);
        CameraMotion.Shot still = advance(CameraMotion.Mode.STATIC);
        assertEquals(camera, pan.position());
        assertEquals(camera, still.position());
        assertNotEquals(center, pan.lookAt());
        assertEquals(center, still.lookAt());
    }

    @Test
    void orbitUsesConfiguredRadiusAndAbsoluteYInsteadOfMarkerFootprint() {
        CameraSettings settings = settings(CameraMotion.Mode.ORBIT);
        settings.heightY = 89;
        CameraMotion.Subject smallScene = new CameraMotion.Subject("small", center, 0, false, false, false, 6);
        CameraMotion.Shot shot = new CameraMotion().sample(smallScene, camera, settings);
        assertEquals(12, Math.hypot(shot.position().x(), shot.position().z()), 1e-9);
        assertEquals(89, shot.position().y());
    }

    @Test
    void autoPlayerContinuesOrbitFromTrailingSideAfterMovementStops() {
        CameraSettings settings = settings(CameraMotion.Mode.AUTO);
        CameraMotion motion = new CameraMotion();
        CameraMotion.Shot moving = motion.sample(new CameraMotion.Subject("player", center, 30, true, false, true, 12), camera, settings);
        CameraMotion.Shot stopped = motion.sample(new CameraMotion.Subject("player", center, 30, true, false, false, 12), camera, settings);
        assertTrue(Math.hypot(stopped.position().x() - moving.position().x(), stopped.position().z() - moving.position().z()) < 0.1);
    }

    @Test
    void explicitFollowOfSceneHoldsAConsistentSide() {
        CameraMotion motion = new CameraMotion();
        CameraSettings settings = settings(CameraMotion.Mode.FOLLOW);
        CameraMotion.Shot first = motion.sample(scene(), camera, settings);
        for (int tick = 0; tick < 100; tick++) assertEquals(first, motion.sample(scene(), camera, settings));
    }

    @Test
    void changingTargetOrModeResetsAnchoredPosition() {
        CameraSettings settings = settings(CameraMotion.Mode.STATIC);
        CameraMotion motion = new CameraMotion();
        motion.sample(scene(), camera, settings);
        CameraMotion.Point nextPosition = new CameraMotion.Point(8, 90, 3);
        assertEquals(camera, motion.sample(scene(), nextPosition, settings).position());
        motion.reset();
        CameraMotion.Point anchored = motion.sample(scene(), nextPosition, settings).position();
        assertNotEquals(camera, anchored);
        assertEquals(12, Math.hypot(anchored.x(), anchored.z()), 1e-9);
        assertEquals(84, anchored.y());
        settings.mode = CameraMotion.Mode.PAN;
        assertEquals(camera, motion.sample(scene(), camera, settings).position());
    }

    @Test
    void horizontalMovementStaysWithinConfiguredRadiusEvenWithMaximumAmplitude() {
        CameraMotion.Subject smallScene = new CameraMotion.Subject("small", center, 0, false, false, false, 6);
        for (CameraMotion.Mode mode : CameraMotion.Mode.values()) {
            CameraMotion motion = new CameraMotion();
            CameraSettings settings = settings(mode);
            settings.motionAmplitude = 24;
            for (int tick = 0; tick < 2_000; tick++) {
                CameraMotion.Point point = motion.sample(smallScene, camera, settings).position();
                assertTrue(Math.hypot(point.x(), point.z()) <= settings.radius + 1e-9, mode.name());
            }
        }
    }

    @Test
    void smallMarkerCanUseA256BlockCameraRadiusAtAbsoluteY256() {
        CameraSettings settings = settings(CameraMotion.Mode.ORBIT);
        settings.radius = 256;
        settings.heightY = 256;
        CameraMotion.Subject marker = new CameraMotion.Subject("marker", center, 0, false, false, false, 12);
        CameraMotion.Shot shot = new CameraMotion().sample(marker, camera, settings);
        assertEquals(256, Math.hypot(shot.position().x(), shot.position().z()), 1e-9);
        assertEquals(256, shot.position().y());
    }

    @Test
    void extendedSceneSettingsKeepPlayersWithinTheExistingFollowEnvelope() {
        CameraMotion.Subject player = new CameraMotion.Subject("player", center, 30, true, false, true, 256);
        for (CameraMotion.Mode mode : CameraMotion.Mode.values()) {
            CameraSettings settings = settings(mode);
            settings.radius = 256;
            settings.heightY = 256;
            settings.playerHeightOffset = 12;
            settings.motionAmplitude = 24;
            CameraMotion motion = new CameraMotion();
            for (int tick = 0; tick < 2_000; tick++) {
                CameraMotion.Point point = motion.sample(player, camera, settings).position();
                assertTrue(Math.hypot(point.x(), point.z()) <= 24 + 1e-9, mode.name());
                double maximumHeight = mode == CameraMotion.Mode.CRANE ? 18 : 12;
                assertTrue(point.y() - center.y() <= maximumHeight + 1e-9, mode.name());
            }
        }
    }

    @Test
    void sceneAndLocalSubjectsAtDifferentElevationsUseTheSameAbsoluteCameraY() {
        for (CameraMotion.Mode mode : CameraMotion.Mode.values()) {
            CameraSettings settings = settings(mode);
            settings.heightY = 120;
            CameraMotion lowMotion = new CameraMotion();
            CameraMotion highMotion = new CameraMotion();
            CameraMotion.Subject low = new CameraMotion.Subject("scene:low", new CameraMotion.Point(0, 20, 0),
                    0, false, false, false, 12);
            CameraMotion.Subject high = new CameraMotion.Subject("local:high", new CameraMotion.Point(0, 180, 0),
                    0, false, false, false, 12);
            for (int tick = 0; tick < 100; tick++) {
                double lowY = lowMotion.sample(low, camera, settings).position().y();
                double highY = highMotion.sample(high, camera, settings).position().y();
                assertEquals(lowY, highY, 1e-9, mode.name());
                if (mode != CameraMotion.Mode.CRANE) {
                    assertEquals(120, lowY, 1e-9, mode.name());
                }
            }
        }
    }

    @Test
    void followingPlayersTracksTheirElevationWithAnIndependentRelativeOffset() {
        for (CameraMotion.Mode mode : CameraMotion.Mode.values()) {
            if (mode == CameraMotion.Mode.CRANE) continue;
            CameraSettings settings = settings(mode);
            settings.heightY = 256;
            settings.playerHeightOffset = 4;
            CameraMotion motion = new CameraMotion();
            for (double y : new double[]{40, 80, 50}) {
                CameraMotion.Subject player = new CameraMotion.Subject("player", new CameraMotion.Point(0, y, 0),
                        30, true, false, true, 12);
                assertEquals(y + 4, motion.sample(player, camera, settings).position().y(), 1e-9, mode.name());
            }
        }
    }
}
