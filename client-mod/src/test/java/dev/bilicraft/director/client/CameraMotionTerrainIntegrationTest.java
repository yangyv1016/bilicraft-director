package dev.bilicraft.director.client;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.function.ToDoubleFunction;

import static dev.bilicraft.director.client.CameraTerrainAvoidance.Clearance.BLOCKED;
import static dev.bilicraft.director.client.CameraTerrainAvoidance.Clearance.CLEAR;
import static dev.bilicraft.director.client.CameraTerrainAvoidance.Clearance.UNKNOWN;
import static org.junit.jupiter.api.Assertions.*;

/** Exercises the production planning order and inertial controller, rather than moving directly to each waypoint. */
class CameraMotionTerrainIntegrationTest {
    @TempDir Path directory;

    @Test
    void orbitAboveUndulatingRoofsDoesNotAlternateBetweenFlyingAndClimbingEveryBlock() {
        CameraSettings settings = new CameraSettings(directory.resolve("camera.properties"));
        settings.heightY = 112;
        HeightmapTerrain terrain = new HeightmapTerrain(point -> 96 + Math.round(6 * Math.sin(point.z() / 8)));

        MotionTrace trace = simulate(settings, new CameraMotion.Point(0, 64, 0),
                new CameraMotion.Point(136, 112, 0), terrain, 4_800);

        assertTrue(trace.stoppedRuns <= 6, "small roof steps must not repeatedly stop horizontal movement: " + trace);
        assertTrue(trace.significantVerticalReversals <= 12,
                "terrain recovery must not repeatedly fight the orbit: " + trace);
        assertTrue(trace.maxY >= 118, "the camera must actually clear the highest roofs: " + trace);
        assertTrue(trace.horizontalDistance > 200, "smoothing must preserve useful orbit movement: " + trace);
        assertEquals(112, settings.heightY, "terrain adjustments must not rewrite the saved scene Y");
        assertEquals(136, settings.radius, "terrain adjustments must not rewrite the saved radius");
    }

    @Test
    void pitchWideningAndRisingGroundShareOneContinuousSpeedLimitedMove() {
        CameraSettings settings = new CameraSettings(directory.resolve("camera.properties"));
        HeightmapTerrain terrain = new HeightmapTerrain(point -> {
            double radius = Math.hypot(point.x(), point.z());
            return radius < 140 ? 96 : radius < 150 ? 112 : radius < 160 ? 122 : 132;
        });

        MotionTrace trace = simulate(settings, new CameraMotion.Point(0, 0, 0),
                new CameraMotion.Point(136, 122, 0), terrain, 4_800);

        assertTrue(trace.maxY >= 148, "the camera must clear the outer plateau: " + trace);
        assertTrue(trace.maxRadius >= 175, "automatic widening must still ease the steep pitch: " + trace);
        assertTrue(trace.stoppedRuns <= 2, "widening must not restart a separate climb at each height step: " + trace);
        assertTrue(trace.verticalReversals <= 2, "one continuous uphill route must not repeatedly descend: " + trace);
        assertEquals(122, settings.heightY);
        assertEquals(136, settings.radius);
    }

    @Test
    void orbitHighAboveTheConfiguredSceneYKeepsHorizontalProgress() {
        CameraSettings settings = new CameraSettings(directory.resolve("camera.properties"));
        HeightmapTerrain terrain = new HeightmapTerrain(point -> 180);

        MotionTrace trace = simulate(settings, new CameraMotion.Point(0, 64, 0),
                new CameraMotion.Point(136, 196, 0), terrain, 4_800);

        assertTrue(trace.horizontalDistance > 200,
                "a retained terrain altitude must not consume the horizontal waypoint distance: " + trace);
        assertTrue(trace.stoppedRuns <= 1, "a level plateau must allow a continuous orbit: " + trace);
        assertTrue(trace.maxRadius >= 155, "the higher shot must retain automatic pitch widening: " + trace);
        assertEquals(122, settings.heightY);
    }

    @Test
    void smallRoofStepsPreserveForwardOrbitProgressInsteadOfTurningBackTowardLowerGround() {
        CameraSettings settings = new CameraSettings(directory.resolve("camera.properties"));
        settings.radius = 48;
        HeightmapTerrain terrain = new HeightmapTerrain(point -> {
            int z = (int) Math.floor(point.z());
            if (z >= 9_880 && z < 9_900) return 108;
            if (z >= 9_900 && z < 9_920) return 110;
            if (z >= 9_920 && z < 9_940) return 116;
            if (z >= 9_940 && z < 9_960) return 113;
            if (z >= 9_960 && z < 9_980) return 110;
            if (z >= 9_980 && z < 10_000) return 107;
            if (z >= 10_040 && z < 10_080) return 108;
            if (z >= 10_080 && z <= 10_120) return 111;
            return 105;
        });

        MotionTrace trace = simulate(settings, new CameraMotion.Point(10_000.5, 64, 10_000.5),
                new CameraMotion.Point(9_952.5, 123, 10_000.5), terrain, 2_400);

        assertTrue(trace.forwardAngle > 2.5,
                "two minutes must advance around the subject instead of oscillating near the start: " + trace);
        assertTrue(trace.backwardAngle < 0.1,
                "small roof steps must not reverse the orbit to chase a lower angle: " + trace);
        assertTrue(trace.horizontalDistance > 140, "the orbit must make sustained useful progress: " + trace);
        assertTrue(trace.stoppedRuns <= 2, "small roof steps must not repeatedly force a separate climb: " + trace);
    }

    @Test
    void missingLoadedHeightmapStopsTheCoupledControllerWithoutEnteringUnknownSpace() {
        CameraSettings settings = new CameraSettings(directory.resolve("camera.properties"));
        HeightmapTerrain terrain = new HeightmapTerrain(point -> point.z() < 3 ? 96 : Double.NaN);

        MotionTrace trace = simulate(settings, new CameraMotion.Point(0, 64, 0),
                new CameraMotion.Point(136, 122, 0), terrain, 1_200);

        assertTrue(trace.maxZ < 3, "missing heightmaps must never be interpreted as low ground: " + trace);
        assertEquals(122, trace.maxY, 1.0E-9, "unknown terrain must not trigger speculative climbing");
    }

    private MotionTrace simulate(CameraSettings settings, CameraMotion.Point center, CameraMotion.Point initial,
                                 HeightmapTerrain terrain, int ticks) {
        CameraMotion motion = new CameraMotion();
        CameraTerrainAvoidance avoidance = new CameraTerrainAvoidance();
        CameraGroundClearance clearance = new CameraGroundClearance();
        CameraMotion.Subject subject = new CameraMotion.Subject("scene", center, 0, false, false, false, settings.radius);
        CameraMotion.Point current = initial;
        CameraMotion.Point velocity = new CameraMotion.Point(0, 0, 0);
        MotionTrace trace = new MotionTrace();
        trace.maxY = initial.y();
        trace.maxRadius = Math.hypot(initial.x() - center.x(), initial.z() - center.z());
        trace.maxZ = initial.z();
        int lastVerticalDirection = 0;
        int significantVerticalDirection = 0;
        double verticalExtreme = initial.y();
        boolean stopped = false;
        int currentStoppedTicks = 0;
        for (int tick = 0; tick < ticks; tick++) {
            long now = 1_000 + tick * 50L;
            CameraMotion.Shot shot = motion.sample(subject, current, settings);
            double lift = avoidance.temporaryLift();
            double plannedFloor = clearance.plannedFloorY(subject.id());
            if (Double.isFinite(plannedFloor)) {
                lift = Math.max(lift, plannedFloor - shot.position().y());
            }
            double radius = Math.hypot(current.x() - center.x(), current.z() - center.z());
            double maximumDistance = Math.min(256, Math.max(settings.radius, radius) + 16);
            if (settings.autoDistance) {
                shot = CameraMotion.widenForPitch(shot, current, lift, 1.62, settings.maxPitchDegrees,
                        maximumDistance);
            }
            // Forecast the intended route before avoidance can choose a lower, opposite side of the subject.
            if (clearance.plan(subject.id(), current, shot.position(), shot.position(), velocity,
                    settings.speed, settings.mode == CameraMotion.Mode.ORBIT || settings.mode == CameraMotion.Mode.AUTO,
                    terrain, now) == null) {
                velocity = new CameraMotion.Point(0, 0, 0);
                motion.setTerrainPaused(true);
                continue;
            }
            plannedFloor = clearance.plannedFloorY(subject.id());
            if (settings.autoDistance) {
                lift = Math.max(avoidance.temporaryLift(), plannedFloor - shot.position().y());
                shot = CameraMotion.widenForPitch(shot, current, lift, 1.62, settings.maxPitchDegrees, maximumDistance);
            }
            CameraTerrainAvoidance.Decision decision = avoidance.plan(subject.id(), shot, current, terrain, now, false,
                    Double.isFinite(plannedFloor) ? plannedFloor : Double.NEGATIVE_INFINITY);
            if (decision.waiting()) {
                velocity = new CameraMotion.Point(0, 0, 0);
                motion.setTerrainPaused(true);
                continue;
            }
            assertNotNull(avoidance.resolvedDestination());
            CameraMotion.Point desired = new CameraMotion.Point(decision.destination().x(),
                    Math.max(decision.destination().y(), plannedFloor), decision.destination().z());
            double maxStep = settings.speed / 20;
            CameraMotion.Point delta = new CameraMotion.Point(desired.x() - current.x(), desired.y() - current.y(),
                    desired.z() - current.z());
            CameraMotion.Point wantedVelocity = limit(scale(delta, 0.12), maxStep);
            CameraMotion.Point nextVelocity = limit(new CameraMotion.Point(
                    velocity.x() + (wantedVelocity.x() - velocity.x()) * 0.18,
                    velocity.y() + (wantedVelocity.y() - velocity.y()) * 0.18,
                    velocity.z() + (wantedVelocity.z() - velocity.z()) * 0.18), maxStep);
            CameraMotion.Point next = current.offset(nextVelocity.x(), nextVelocity.y(), nextVelocity.z());
            CameraMotion.Point safe = CameraGroundClearance.constrainStep(current, next, velocity.y(), maxStep, terrain);
            if (safe == null) {
                velocity = new CameraMotion.Point(0, 0, 0);
                motion.setTerrainPaused(true);
                continue;
            }
            boolean forcedClimb = Math.hypot(next.x() - current.x(), next.z() - current.z()) > 1.0E-6
                    && Math.hypot(safe.x() - current.x(), safe.z() - current.z()) < 1.0E-6 && safe.y() > current.y();
            // Vanilla clears small velocity components independently before applying actual movement.
            double stepX = safe.x() - current.x();
            double stepY = safe.y() - current.y();
            double stepZ = safe.z() - current.z();
            safe = current.offset(Math.abs(stepX) < 0.003 ? 0 : stepX,
                    Math.abs(stepY) < 0.003 ? 0 : stepY, Math.abs(stepZ) < 0.003 ? 0 : stepZ);
            assertEquals(CLEAR, terrain.path(current, safe), "actual movement must remain physically safe at tick " + tick);
            double surface = terrain.height.applyAsDouble(safe);
            assertTrue(Double.isFinite(surface), "actual movement must stay over loaded terrain at tick " + tick);
            assertTrue(safe.y() + 1.0E-9 >= surface + 16,
                    "the final step must preserve minimum ground clearance at tick " + tick);
            velocity = new CameraMotion.Point(safe.x() - current.x(), safe.y() - current.y(), safe.z() - current.z());
            assertTrue(length(velocity) <= maxStep + 1.0E-9, "the shared speed limit must hold at tick " + tick);
            double horizontal = Math.hypot(velocity.x(), velocity.z());
            motion.setTerrainPaused(forcedClimb);
            boolean nowStopped = horizontal < 1.0E-6;
            if (nowStopped && !stopped) trace.stoppedRuns++;
            currentStoppedTicks = nowStopped ? currentStoppedTicks + 1 : 0;
            if (nowStopped) trace.stoppedTicks++;
            trace.longestStoppedTicks = Math.max(trace.longestStoppedTicks, currentStoppedTicks);
            stopped = nowStopped;
            int verticalDirection = velocity.y() > 0.0001 ? 1 : velocity.y() < -0.0001 ? -1 : 0;
            if (verticalDirection != 0) {
                if (lastVerticalDirection != 0 && lastVerticalDirection != verticalDirection) trace.verticalReversals++;
                lastVerticalDirection = verticalDirection;
            }
            // Ignore tiny inertial settling: a visible reversal must travel a quarter block away from the last extreme.
            if (significantVerticalDirection == 0) {
                if (Math.abs(safe.y() - initial.y()) >= 0.25) {
                    significantVerticalDirection = safe.y() > initial.y() ? 1 : -1;
                    verticalExtreme = safe.y();
                }
            } else if (significantVerticalDirection > 0) {
                verticalExtreme = Math.max(verticalExtreme, safe.y());
                if (verticalExtreme - safe.y() >= 0.25) {
                    trace.significantVerticalReversals++;
                    significantVerticalDirection = -1;
                    verticalExtreme = safe.y();
                }
            } else {
                verticalExtreme = Math.min(verticalExtreme, safe.y());
                if (safe.y() - verticalExtreme >= 0.25) {
                    trace.significantVerticalReversals++;
                    significantVerticalDirection = 1;
                    verticalExtreme = safe.y();
                }
            }
            trace.horizontalDistance += horizontal;
            double angularStep = Math.IEEEremainder(
                    Math.atan2(safe.z() - center.z(), safe.x() - center.x())
                            - Math.atan2(current.z() - center.z(), current.x() - center.x()), 2 * Math.PI);
            if (angularStep >= 0) trace.forwardAngle += angularStep;
            else trace.backwardAngle -= angularStep;
            trace.maxY = Math.max(trace.maxY, safe.y());
            trace.maxRadius = Math.max(trace.maxRadius, Math.hypot(safe.x() - center.x(), safe.z() - center.z()));
            trace.maxZ = Math.max(trace.maxZ, safe.z());
            current = safe;
        }
        return trace;
    }

    private static CameraMotion.Point scale(CameraMotion.Point point, double factor) {
        return new CameraMotion.Point(point.x() * factor, point.y() * factor, point.z() * factor);
    }

    private static CameraMotion.Point limit(CameraMotion.Point point, double maximum) {
        double length = length(point);
        return length > maximum ? scale(point, maximum / length) : point;
    }

    private static double length(CameraMotion.Point point) {
        return Math.sqrt(point.x() * point.x() + point.y() * point.y() + point.z() * point.z());
    }

    private static final class MotionTrace {
        int stoppedRuns;
        int stoppedTicks;
        int longestStoppedTicks;
        int verticalReversals;
        int significantVerticalReversals;
        double horizontalDistance;
        double maxY;
        double maxRadius;
        double maxZ;
        double forwardAngle;
        double backwardAngle;

        @Override
        public String toString() {
            return "stoppedRuns=" + stoppedRuns + ", verticalReversals=" + verticalReversals
                    + ", stoppedTicks=" + stoppedTicks + ", longestStoppedTicks=" + longestStoppedTicks
                    + ", significantVerticalReversals=" + significantVerticalReversals
                    + ", horizontalDistance=" + horizontalDistance + ", maxY=" + maxY + ", maxRadius=" + maxRadius
                    + ", forwardAngle=" + forwardAngle + ", backwardAngle=" + backwardAngle;
        }
    }

    private static final class HeightmapTerrain implements CameraTerrainAvoidance.Terrain {
        private final ToDoubleFunction<CameraMotion.Point> height;

        private HeightmapTerrain(ToDoubleFunction<CameraMotion.Point> height) {
            this.height = height;
        }

        @Override
        public CameraTerrainAvoidance.Clearance space(CameraMotion.Point feet) {
            double surface = height.applyAsDouble(feet);
            if (!Double.isFinite(surface)) return UNKNOWN;
            return feet.y() >= surface + 16 && feet.y() <= maxY() ? CLEAR : BLOCKED;
        }

        @Override
        public CameraTerrainAvoidance.Clearance path(CameraMotion.Point from, CameraMotion.Point to) {
            // Production path() checks physical obstacles, while space()/constrainStep enforce the altitude margin.
            for (int sample = 0; sample <= 20; sample++) {
                double fraction = sample / 20.0;
                CameraMotion.Point point = new CameraMotion.Point(from.x() + (to.x() - from.x()) * fraction,
                        from.y() + (to.y() - from.y()) * fraction, from.z() + (to.z() - from.z()) * fraction);
                double surface = height.applyAsDouble(point);
                if (!Double.isFinite(surface)) return UNKNOWN;
                if (point.y() < surface || point.y() > maxY()) return BLOCKED;
            }
            return CLEAR;
        }

        @Override
        public CameraTerrainAvoidance.Clearance view(CameraMotion.Point feet, CameraMotion.Point lookAt) {
            return CLEAR;
        }

        @Override
        public CameraMotion.Point surface(CameraMotion.Point feet) {
            double surface = height.applyAsDouble(feet);
            return Double.isFinite(surface) ? new CameraMotion.Point(feet.x(), surface, feet.z()) : null;
        }

        @Override
        public double minimumGroundClearance() {
            return 16;
        }

        @Override
        public double maxY() {
            return 318;
        }
    }
}
