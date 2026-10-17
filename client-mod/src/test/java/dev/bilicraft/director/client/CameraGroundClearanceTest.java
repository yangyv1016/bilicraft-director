package dev.bilicraft.director.client;

import org.junit.jupiter.api.Test;

import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

class CameraGroundClearanceTest {
    @Test
    void loadedGroundAheadRaisesThePlannedHeightBeforeTheActualStepNeedsToStop() {
        ProbeTerrain terrain = new ProbeTerrain();
        terrain.surface = point -> new CameraMotion.Point(point.x(), point.x() >= 5 ? 108 : 100, point.z());
        CameraGroundClearance planner = new CameraGroundClearance();
        CameraMotion.Point current = new CameraMotion.Point(0, 116, 0);
        CameraMotion.Point waypoint = new CameraMotion.Point(4, 116, 0);

        CameraMotion.Point planned = planner.plan("scene", current, waypoint, current.offset(20, 0, 0),
                new CameraMotion.Point(0.1, 0, 0), 2.5, false, terrain, 1_000);

        assertEquals(new CameraMotion.Point(4, 125, 0), planned);
        CameraMotion.Point next = current.offset(0.05, 0.05, 0);
        assertSame(next, CameraGroundClearance.constrainStep(current, next, 0.05, 0.125, terrain));
        assertTrue(terrain.queries <= 70, "forecast reads must remain bounded local heightmap lookups");
    }

    @Test
    void aLoadedOriginalDestinationSetsItsHeightBeforeAvoidanceCanChooseTheLowerOppositeSide() {
        ProbeTerrain terrain = new ProbeTerrain();
        terrain.surface = point -> new CameraMotion.Point(point.x(), point.x() >= 20 ? 108 : 100, point.z());
        CameraMotion.Point current = new CameraMotion.Point(0, 122, 0);
        CameraMotion.Point goal = current.offset(24, 0, 0);

        CameraMotion.Point planned = new CameraGroundClearance().plan("scene", current, goal, goal,
                new CameraMotion.Point(0.1, 0, 0), 2.5, false, terrain, 1_000);

        assertEquals(125, planned.y(), "a known original endpoint must not lose to a low alternate angle before forecasting");
    }

    @Test
    void aContinuingOrbitSeesPastItsMovingWaypointSoItCanClimbBeforeReachingTheNextRoof() {
        ProbeTerrain terrain = new ProbeTerrain();
        terrain.surface = point -> new CameraMotion.Point(point.x(), point.x() >= 5 ? 116 : 113, point.z());
        CameraMotion.Point current = new CameraMotion.Point(0, 130, 0);
        CameraMotion.Point movingWaypoint = current.offset(3, 0, 0);

        CameraMotion.Point planned = new CameraGroundClearance().plan("scene", current, movingWaypoint, movingWaypoint,
                new CameraMotion.Point(0.1, 0, 0), 2.5, true, terrain, 1_000);

        assertEquals(133, planned.y(), "the instantaneous orbit waypoint is not the end of the continuing route");
    }

    @Test
    void forecastAlsoCoversTheOldInertiaDirectionWhenTheRouteTurns() {
        ProbeTerrain terrain = new ProbeTerrain();
        terrain.surface = point -> new CameraMotion.Point(point.x(), point.z() >= 3 ? 120 : 100, point.z());
        CameraMotion.Point current = new CameraMotion.Point(0, 122, 0);

        CameraMotion.Point planned = new CameraGroundClearance().plan("scene", current, current.offset(4, 0, 0),
                current.offset(20, 0, 0), new CameraMotion.Point(0, 0, 0.1), 2.5, false, terrain, 1_000);

        assertEquals(137, planned.y());
    }

    @Test
    void leavingHighGroundHoldsItsHeightThenReturnsAtHalfABlockPerSecond() {
        ProbeTerrain terrain = new ProbeTerrain();
        terrain.surface = point -> new CameraMotion.Point(point.x(), 120, point.z());
        CameraGroundClearance planner = new CameraGroundClearance();
        CameraMotion.Point current = new CameraMotion.Point(0, 137, 0);
        CameraMotion.Point goal = new CameraMotion.Point(4, 122, 0);
        CameraMotion.Point velocity = new CameraMotion.Point(0.1, 0, 0);
        planner.plan("scene", current, goal, goal, velocity, 2.5, false, terrain, 1_000);
        terrain.surface = point -> new CameraMotion.Point(point.x(), 100, point.z());

        for (long now = 1_050; now < 3_050; now += 50) {
            assertEquals(137, planner.plan("scene", current, goal, goal, velocity, 2.5, false, terrain, now).y());
        }
        assertEquals(136.975, planner.plan("scene", current, goal, goal, velocity, 2.5, false, terrain, 3_050).y(), 1.0E-9);
        assertEquals(136.95, planner.plan("scene", current, goal, goal, velocity, 2.5, false, terrain, 3_100).y(), 1.0E-9);
    }

    @Test
    void unknownForecastCannotReleaseRetainedHeightButDoesNotBlockMovementOverKnownCurrentGround() {
        ProbeTerrain terrain = new ProbeTerrain();
        terrain.surface = point -> new CameraMotion.Point(point.x(), 120, point.z());
        CameraGroundClearance planner = new CameraGroundClearance();
        CameraMotion.Point current = new CameraMotion.Point(0, 137, 0);
        CameraMotion.Point goal = new CameraMotion.Point(4, 122, 0);
        CameraMotion.Point velocity = new CameraMotion.Point(0.1, 0, 0);
        planner.plan("scene", current, goal, goal, velocity, 2.5, false, terrain, 1_000);
        terrain.surface = point -> point.x() >= 3 ? null : new CameraMotion.Point(point.x(), 100, point.z());

        for (long now = 1_050; now <= 6_000; now += 50) {
            assertEquals(137, planner.plan("scene", current, goal, goal, velocity, 2.5, false, terrain, now).y());
        }
        terrain.surface = point -> null;
        assertNull(planner.plan("scene", current, goal, goal, velocity, 2.5, false, terrain, 6_050));
    }

    @Test
    void changingSubjectsOrDisablingClearanceCannotReuseThePreviousScenesFloor() {
        ProbeTerrain terrain = new ProbeTerrain();
        terrain.surface = point -> new CameraMotion.Point(point.x(), 120, point.z());
        CameraGroundClearance planner = new CameraGroundClearance();
        CameraMotion.Point current = new CameraMotion.Point(0, 137, 0);
        CameraMotion.Point goal = new CameraMotion.Point(4, 122, 0);
        CameraMotion.Point velocity = new CameraMotion.Point(0.1, 0, 0);
        planner.plan("scene", current, goal, goal, velocity, 2.5, false, terrain, 1_000);
        assertEquals(137, planner.plannedFloorY("scene"));
        assertTrue(Double.isNaN(planner.plannedFloorY("new-scene")));
        terrain.surface = point -> new CameraMotion.Point(point.x(), 100, point.z());
        assertEquals(goal, planner.plan("new-scene", current, goal, goal, velocity, 2.5, false, terrain, 1_050));

        terrain.clearance = 0;
        terrain.surface = point -> { throw new AssertionError("disabled clearance must not query terrain"); };
        assertSame(goal, planner.plan("new-scene", current, goal, goal, velocity, 2.5, false, terrain, 1_100));
        assertTrue(Double.isNaN(planner.plannedFloorY("new-scene")));
    }

    @Test
    void forecastStopsAtTheRouteDestinationInsteadOfClimbingForGroundBeyondTheShot() {
        ProbeTerrain terrain = new ProbeTerrain();
        terrain.surface = point -> new CameraMotion.Point(point.x(), point.x() > 2 ? 200 : 100, point.z());
        CameraMotion.Point current = new CameraMotion.Point(0, 122, 0);
        CameraMotion.Point goal = current.offset(2, 0, 0);

        assertEquals(goal, new CameraGroundClearance().plan("scene", current, goal, goal,
                new CameraMotion.Point(0.1, 0, 0), 2.5, false, terrain, 1_000));
    }

    @Test
    void disabledClearanceReturnsTheOriginalStepWithoutReadingTerrain() {
        for (double clearance : new double[]{0, -1}) {
            ProbeTerrain terrain = new ProbeTerrain();
            terrain.clearance = clearance;
            terrain.surface = point -> { throw new AssertionError("disabled clearance must not query heightmaps"); };
            CameraMotion.Point current = new CameraMotion.Point(0, 122, 0);
            CameraMotion.Point next = current.offset(0.1, -0.01, 0);

            assertSame(next, CameraGroundClearance.constrainStep(current, next, -0.01, 0.125, terrain));
            assertEquals(0, terrain.queries);
        }
    }

    @Test
    void eitherUnknownSurfaceStopsTheStepInsteadOfAssumingLowGround() {
        for (boolean currentUnknown : new boolean[]{true, false}) {
            ProbeTerrain terrain = new ProbeTerrain();
            terrain.surface = point -> (point.x() == 0) == currentUnknown
                    ? null : new CameraMotion.Point(point.x(), 100, point.z());
            CameraMotion.Point current = new CameraMotion.Point(0, 122, 0);

            assertNull(CameraGroundClearance.constrainStep(current, current.offset(0.1, 0, 0),
                    0, 0.125, terrain));
            assertEquals(currentUnknown ? 1 : 2, terrain.queries);
        }
    }

    @Test
    void eitherHorizontalAxisDeadzoneCannotMoveTheCameraIntoAnUncheckedHighColumn() {
        for (boolean xCleared : new boolean[]{true, false}) {
            ProbeTerrain terrain = new ProbeTerrain();
            CameraMotion.Point current = xCleared ? new CameraMotion.Point(0.999, 116, 0.99)
                    : new CameraMotion.Point(0.99, 116, 0.999);
            CameraMotion.Point next = xCleared ? new CameraMotion.Point(1.001, 115.999, 1.01)
                    : new CameraMotion.Point(1.01, 115.999, 1.001);
            terrain.surface = point -> new CameraMotion.Point(point.x(),
                    (xCleared ? point.x() < 1 && point.z() >= 1 : point.x() >= 1 && point.z() < 1) ? 160 : 100,
                    point.z());

            CameraMotion.Point constrained = CameraGroundClearance.constrainStep(current, next, 0, 0.125, terrain);

            assertNotNull(constrained);
            assertEquals(current.x(), constrained.x());
            assertEquals(current.z(), constrained.z());
            assertTrue(constrained.y() > current.y(), "climb before either quantized axis can enter the high side column");
            assertTrue(distance(current, constrained) <= 0.125);
            assertEquals(4, terrain.queries);
        }
    }

    @Test
    void diagonalCornerCrossingChecksBothSideColumnsEvenWhenBothEndpointsAreLow() {
        ProbeTerrain terrain = new ProbeTerrain();
        terrain.surface = point -> new CameraMotion.Point(point.x(),
                (point.x() < 1) != (point.z() < 1) ? 160 : 100, point.z());
        CameraMotion.Point current = new CameraMotion.Point(0.99, 116, 0.99);
        CameraMotion.Point next = new CameraMotion.Point(1.01, 115.999, 1.01);

        CameraMotion.Point constrained = CameraGroundClearance.constrainStep(current, next, 0, 0.125, terrain);

        assertNotNull(constrained);
        assertEquals(current.x(), constrained.x());
        assertEquals(current.z(), constrained.z());
        assertTrue(constrained.y() > current.y());
        assertEquals(4, terrain.queries);
    }

    @Test
    void eitherUnknownCrossColumnStopsMovementInsteadOfTrustingOnlyTheKnownEndpoints() {
        CameraMotion.Point current = new CameraMotion.Point(0.99, 116, 0.99);
        CameraMotion.Point next = new CameraMotion.Point(1.01, 116, 1.01);
        for (boolean firstCornerUnknown : new boolean[]{true, false}) {
            ProbeTerrain terrain = new ProbeTerrain();
            terrain.surface = point -> (firstCornerUnknown
                    ? point.x() == current.x() && point.z() == next.z()
                    : point.x() == next.x() && point.z() == current.z())
                    ? null : new CameraMotion.Point(point.x(), 100, point.z());

            assertNull(CameraGroundClearance.constrainStep(current, next, 0, 0.125, terrain));
            assertEquals(firstCornerUnknown ? 3 : 4, terrain.queries);
        }
    }

    @Test
    void approachingAHigherPlatformClimbsInPlaceBeforeEnteringItsColumn() {
        ProbeTerrain terrain = new ProbeTerrain();
        terrain.surface = point -> new CameraMotion.Point(point.x(), point.x() == 0 ? 0 : 40, point.z());
        CameraMotion.Point current = new CameraMotion.Point(0, 16, 0);
        CameraMotion.Point next = current.offset(0.1, -0.01, 0);

        CameraMotion.Point constrained = CameraGroundClearance.constrainStep(current, next, -0.01, 0.125, terrain);

        assertNotNull(constrained);
        assertEquals(current.x(), constrained.x());
        assertEquals(current.z(), constrained.z());
        assertTrue(constrained.y() > current.y());
        assertTrue(constrained.y() - current.y() <= 0.125);
    }

    @Test
    void repeatedClimbingEventuallyAllowsHorizontalMovementAboveTheHigherPlatform() {
        ProbeTerrain terrain = new ProbeTerrain();
        terrain.surface = point -> new CameraMotion.Point(point.x(), point.x() == 0 ? 0 : 40, point.z());
        CameraMotion.Point current = new CameraMotion.Point(0, 16, 0);
        double previousVerticalStep = 0;
        for (int tick = 0; tick < 2_000 && current.y() < 56; tick++) {
            CameraMotion.Point next = CameraGroundClearance.constrainStep(current, current.offset(0.1, -0.01, 0),
                    previousVerticalStep, 0.125, terrain);
            assertNotNull(next);
            assertEquals(0, next.x());
            assertEquals(0, next.z());
            assertTrue(next.y() > current.y());
            assertTrue(next.y() - current.y() <= 0.125 + 1.0E-12);
            previousVerticalStep = next.y() - current.y();
            current = next;
        }
        assertTrue(current.y() >= 56, "the final millimeters must not stall below the required clearance");

        CameraMotion.Point entering = CameraGroundClearance.constrainStep(current, current.offset(0.1, -0.01, 0),
                previousVerticalStep, 0.125, terrain);

        assertNotNull(entering);
        assertEquals(0.1, entering.x());
        assertTrue(entering.y() >= 56);
    }

    @Test
    void descendingStepIsClampedToTheFloorWithoutBlockingSafeHorizontalMovement() {
        ProbeTerrain terrain = new ProbeTerrain();
        terrain.surface = point -> new CameraMotion.Point(point.x(), 40, point.z());
        CameraMotion.Point current = new CameraMotion.Point(0, 56.05, 0);
        CameraMotion.Point next = new CameraMotion.Point(0.1, 55.99, 0);

        CameraMotion.Point constrained = CameraGroundClearance.constrainStep(current, next, -0.06, 0.125, terrain);

        assertEquals(new CameraMotion.Point(0.1, 56, 0), constrained);
        assertTrue(distance(current, constrained) <= 0.125);
        assertTrue(distance(current, constrained) <= distance(current, next));
    }

    @Test
    void cameraCanOnlyDescendAfterItHasActuallyLeftTheHighColumn() {
        ProbeTerrain terrain = new ProbeTerrain();
        terrain.surface = point -> new CameraMotion.Point(point.x(), point.x() <= 0 ? 40 : 0, point.z());
        CameraMotion.Point current = new CameraMotion.Point(0, 56, 0);

        CameraMotion.Point leaving = CameraGroundClearance.constrainStep(current, current.offset(0.1, -0.01, 0),
                -0.01, 0.125, terrain);
        assertEquals(new CameraMotion.Point(0.1, 56, 0), leaving);
        CameraMotion.Point descending = leaving.offset(0.1, -0.01, 0);

        assertSame(descending, CameraGroundClearance.constrainStep(leaving, descending, -0.01, 0.125, terrain));
    }

    @Test
    void increasingTheLiveMinimumAllowsTheCameraToClimbFromBelowTheNewFloor() {
        ProbeTerrain terrain = new ProbeTerrain();
        terrain.clearance = 20;
        terrain.surface = point -> new CameraMotion.Point(point.x(), 100, point.z());
        CameraMotion.Point current = new CameraMotion.Point(0, 116, 0);

        CameraMotion.Point constrained = CameraGroundClearance.constrainStep(current, current.offset(0.1, 0, 0),
                0, 0.125, terrain);

        assertNotNull(constrained);
        assertEquals(0, constrained.x());
        assertTrue(constrained.y() > current.y());
        assertTrue(constrained.y() - current.y() <= 0.125);
    }

    @Test
    void minimumPositiveVerticalStepCrossesTheFloorInsteadOfBeingSwallowedByTheMovementDeadzone() {
        ProbeTerrain terrain = new ProbeTerrain();
        terrain.clearance = 20;
        terrain.surface = point -> new CameraMotion.Point(point.x(), 100, point.z());
        CameraMotion.Point current = new CameraMotion.Point(0, 119.999, 0);

        CameraMotion.Point constrained = CameraGroundClearance.constrainStep(current, current.offset(0.1, -0.001, 0),
                0, 0.125, terrain);

        assertNotNull(constrained);
        assertEquals(0.004, constrained.y() - current.y(), 1.0E-12);
        assertTrue(constrained.y() > 120);
        assertEquals(0, constrained.x());
    }

    @Test
    void previousUpwardOrDownwardMomentumCannotExceedTheConfiguredStepLimit() {
        ProbeTerrain terrain = new ProbeTerrain();
        terrain.surface = point -> new CameraMotion.Point(point.x(), 100, point.z());
        CameraMotion.Point current = new CameraMotion.Point(0, 110, 0);
        for (double maxStep : new double[]{0.025, 0.125, 0.25}) {
            for (double previousVerticalStep : new double[]{-1, -0.1, 0, 0.1, 1}) {
                CameraMotion.Point constrained = CameraGroundClearance.constrainStep(current,
                        current.offset(maxStep * 0.5, -maxStep * 0.1, 0), previousVerticalStep, maxStep, terrain);

                assertNotNull(constrained);
                assertTrue(constrained.y() > current.y());
                assertTrue(distance(current, constrained) <= maxStep + 1.0E-12);
                assertEquals(0, constrained.x());
                assertEquals(0, constrained.z());
            }
        }
    }

    @Test
    void anAlreadySafeStepIsReturnedUnchanged() {
        ProbeTerrain terrain = new ProbeTerrain();
        terrain.surface = point -> new CameraMotion.Point(point.x(), 100, point.z());
        CameraMotion.Point current = new CameraMotion.Point(0, 130, 0);
        CameraMotion.Point next = current.offset(0.1, -0.01, 0);

        assertSame(next, CameraGroundClearance.constrainStep(current, next, -0.01, 0.125, terrain));
        assertEquals(4, terrain.queries);
    }

    private static double distance(CameraMotion.Point from, CameraMotion.Point to) {
        return Math.sqrt(Math.pow(to.x() - from.x(), 2) + Math.pow(to.y() - from.y(), 2)
                + Math.pow(to.z() - from.z(), 2));
    }

    private static final class ProbeTerrain implements CameraTerrainAvoidance.Terrain {
        double clearance = 16;
        int queries;
        Function<CameraMotion.Point, CameraMotion.Point> surface;

        @Override
        public CameraTerrainAvoidance.Clearance space(CameraMotion.Point point) {
            throw new AssertionError("the controller validates physical clearance after constraining the step");
        }

        @Override
        public CameraTerrainAvoidance.Clearance path(CameraMotion.Point from, CameraMotion.Point to) {
            throw new AssertionError("the controller validates the final physical path");
        }

        @Override
        public CameraTerrainAvoidance.Clearance view(CameraMotion.Point point, CameraMotion.Point lookAt) {
            throw new AssertionError("ground clearance does not change the camera's look direction");
        }

        @Override
        public CameraMotion.Point surface(CameraMotion.Point point) {
            queries++;
            return surface.apply(point);
        }

        @Override
        public double minimumGroundClearance() {
            return clearance;
        }

        @Override
        public double maxY() {
            return 318;
        }
    }
}
