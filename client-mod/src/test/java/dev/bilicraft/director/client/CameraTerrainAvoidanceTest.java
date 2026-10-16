package dev.bilicraft.director.client;

import org.junit.jupiter.api.Test;

import java.util.function.BiFunction;
import java.util.function.Function;

import static dev.bilicraft.director.client.CameraTerrainAvoidance.Clearance.*;
import static org.junit.jupiter.api.Assertions.*;

class CameraTerrainAvoidanceTest {
    private final CameraMotion.Point center = new CameraMotion.Point(0, 80, 0);
    private final CameraMotion.Shot intended = new CameraMotion.Shot(new CameraMotion.Point(12, 122, 0), center);

    @Test
    void unobstructedShotKeepsTheIntendedPositionAndAbsoluteHeight() {
        ProbeTerrain terrain = new ProbeTerrain();
        CameraTerrainAvoidance planner = new CameraTerrainAvoidance();

        CameraTerrainAvoidance.Decision decision = planner.plan("scene", intended,
                new CameraMotion.Point(10, 122, 0), terrain, 1_000, false);

        assertEquals(intended.position(), decision.destination());
        assertFalse(decision.waiting());
        assertFalse(decision.adjusted());
        assertEquals(intended.position(), planner.landing("scene", intended, terrain));
        assertEquals(122, intended.position().y(), "planning does not rewrite the saved shot's baseline");
    }

    @Test
    void distantSafeGoalProducesABoundedWaypointRatherThanAShortcutToItsFinalPose() {
        ProbeTerrain terrain = new ProbeTerrain();
        CameraTerrainAvoidance planner = new CameraTerrainAvoidance();
        CameraMotion.Point current = new CameraMotion.Point(-12, 122, 0);

        CameraTerrainAvoidance.Decision decision = planner.plan("scene", intended, current, terrain, 1_000, true);

        assertFalse(decision.waiting());
        assertEquals(4, distance(current, decision.destination()), 1.0E-9);
        assertEquals(122, decision.destination().y());
        assertNotEquals(intended.position(), decision.destination());
        assertEquals(intended.position(), planner.landing("scene", intended, terrain));
    }

    @Test
    void aReachableSameHeightAngleWinsEvenWhenRaisingTheOriginalPositionWouldAlsoWork() {
        ProbeTerrain terrain = new ProbeTerrain();
        terrain.space = point -> Math.abs(point.z()) > 1 || point.y() >= 123 ? CLEAR : BLOCKED;
        CameraTerrainAvoidance planner = new CameraTerrainAvoidance();

        CameraMotion.Point landing = planner.landing("scene", intended, terrain);

        assertNotNull(landing);
        assertEquals(122, landing.y());
        assertTrue(Math.abs(landing.z()) > 1);
        assertEquals(12, Math.hypot(landing.x(), landing.z()), 1.0E-9);
        CameraTerrainAvoidance.Decision decision = planner.plan("scene", intended, landing, terrain, 1_000, false);
        assertFalse(decision.waiting());
        assertEquals(122, decision.destination().y());
    }

    @Test
    void clearDestinationBehindAWallIsReachedThroughClearHorizontalWaypoints() {
        ProbeTerrain terrain = new ProbeTerrain();
        terrain.path = (from, to) -> {
            double deltaX = to.x() - from.x();
            if (Math.abs(deltaX) < 1.0E-9) return CLEAR;
            double fraction = (9 - from.x()) / deltaX;
            if (fraction < 0 || fraction > 1) return CLEAR;
            double zAtWall = from.z() + (to.z() - from.z()) * fraction;
            return Math.abs(zAtWall) <= 0.5 ? BLOCKED : CLEAR;
        };
        CameraTerrainAvoidance planner = new CameraTerrainAvoidance();
        CameraMotion.Point current = new CameraMotion.Point(8, 122, 0);
        assertEquals(BLOCKED, terrain.path(current, intended.position()));

        for (int step = 0; step < 20 && distance(current, intended.position()) > 0.01; step++) {
            CameraTerrainAvoidance.Decision decision = planner.plan("scene", intended, current,
                    terrain, 1_000 + step * 250L, true);
            assertFalse(decision.waiting());
            assertEquals(122, decision.destination().y(), "a horizontal exit must not trigger unnecessary climbing");
            assertTrue(distance(current, decision.destination()) <= 4 + 1.0E-9);
            assertEquals(CLEAR, terrain.path(current, decision.destination()), "each actual leg must miss the wall");
            current = decision.destination();
        }

        assertEquals(intended.position(), current);
    }

    @Test
    void aChosenBypassSideIsRetainedWhenBothSidesLaterBecomeAvailable() {
        ProbeTerrain terrain = new ProbeTerrain();
        terrain.path = (from, to) -> to.z() < -0.01 ? CLEAR : BLOCKED;
        CameraTerrainAvoidance planner = new CameraTerrainAvoidance();
        CameraMotion.Point current = new CameraMotion.Point(8, 122, 0);
        CameraTerrainAvoidance.Decision first = planner.plan("scene", intended, current, terrain, 1_000, false);
        assertTrue(first.destination().z() < 0);

        terrain.path = (from, to) -> Math.abs(to.z()) > 0.01 ? CLEAR : BLOCKED;
        CameraTerrainAvoidance.Decision next = planner.plan("scene", intended, current, terrain, 1_250, false);

        assertFalse(next.waiting());
        assertTrue(next.destination().z() < 0, "minor clearance changes must not alternate left and right");
        assertEquals(122, next.destination().y());
    }

    @Test
    void unavoidableClimbUsesTheLowestSafeHeightWithinHalfABlock() {
        ProbeTerrain terrain = new ProbeTerrain();
        double minimumSafeY = 128.3;
        terrain.space = point -> point.y() >= minimumSafeY ? CLEAR : BLOCKED;
        CameraTerrainAvoidance planner = new CameraTerrainAvoidance();

        CameraMotion.Point landing = planner.landing("scene", intended, terrain);

        assertNotNull(landing);
        assertTrue(landing.y() >= minimumSafeY);
        assertTrue(landing.y() < minimumSafeY + 0.5);
        assertEquals(intended.position().x(), landing.x());
        assertEquals(intended.position().z(), landing.z());
        assertEquals(122, intended.position().y());
    }

    @Test
    void clearingAnObstacleHoldsHeightForTwoSecondsThenRestoresItGradually() {
        ProbeTerrain terrain = new ProbeTerrain();
        terrain.space = point -> point.y() >= 128.3 ? CLEAR : BLOCKED;
        CameraTerrainAvoidance planner = new CameraTerrainAvoidance();
        CameraMotion.Point current = new CameraMotion.Point(12, 128.5, 0);
        CameraTerrainAvoidance.Decision raised = planner.plan("scene", intended, current, terrain, 1_000, false);
        double raisedY = raised.destination().y();
        assertTrue(raisedY >= 128.3);

        terrain.space = point -> CLEAR;
        current = raised.destination();
        for (long now = 1_250; now < 3_250; now += 250) {
            CameraTerrainAvoidance.Decision held = planner.plan("scene", intended, current, terrain, now, false);
            assertEquals(raisedY, held.destination().y(), "briefly clear terrain must not cause immediate descent");
            current = held.destination();
        }

        for (long now = 3_250; now <= 18_250; now += 250) {
            CameraTerrainAvoidance.Decision recovering = planner.plan("scene", intended, current, terrain, now, false);
            assertFalse(recovering.waiting());
            assertTrue(recovering.destination().y() <= current.y());
            assertTrue(current.y() - recovering.destination().y() <= 0.125 + 1.0E-9,
                    "recovery is capped at half a block per second");
            assertTrue(recovering.destination().y() >= 122);
            current = recovering.destination();
        }

        assertEquals(122, current.y(), 1.0E-9);
        assertEquals(122, intended.position().y());
    }

    @Test
    void firstPlanAfterLandingRetainsLiftWhenTheOriginalHeightBecomesClear() {
        ProbeTerrain terrain = new ProbeTerrain();
        terrain.space = point -> point.y() >= 128.3 ? CLEAR : BLOCKED;
        CameraTerrainAvoidance planner = new CameraTerrainAvoidance();
        CameraMotion.Point landing = planner.landing("scene", intended, terrain);
        assertNotNull(landing);
        assertTrue(landing.y() >= 128.3);

        terrain.space = point -> CLEAR;
        CameraTerrainAvoidance.Decision first = planner.plan("scene", intended, landing, terrain, 1_000, false);
        assertFalse(first.waiting());
        assertEquals(landing.y(), first.destination().y());
        assertEquals(landing.y() - 122, planner.temporaryLift());
        CameraTerrainAvoidance.Decision held = planner.plan("scene", intended, landing, terrain, 2_999, false);
        assertEquals(landing.y(), held.destination().y());
        CameraTerrainAvoidance.Decision recovering = planner.plan("scene", intended, landing, terrain, 3_249, false);
        assertEquals(landing.y() - 0.125, recovering.destination().y(), 1.0E-9);
    }

    @Test
    void unknownCameraSpaceOrViewNeverCountsAsASafePose() {
        for (boolean unknownSpace : new boolean[]{true, false}) {
            ProbeTerrain terrain = new ProbeTerrain();
            if (unknownSpace) terrain.space = point -> UNKNOWN;
            else terrain.view = (point, lookAt) -> UNKNOWN;
            CameraTerrainAvoidance planner = new CameraTerrainAvoidance();
            CameraMotion.Point current = new CameraMotion.Point(10, 122, 0);

            assertNull(planner.landing("scene", intended, terrain));
            CameraTerrainAvoidance.Decision decision = planner.plan("scene", intended, current, terrain, 1_000, false);

            assertTrue(decision.waiting());
            assertEquals(current, decision.destination());
        }
    }

    @Test
    void unknownPathsCauseWaitingWithoutTreatingMissingChunksAsAnObstacleToClimb() {
        ProbeTerrain terrain = new ProbeTerrain();
        terrain.path = (from, to) -> UNKNOWN;
        CameraTerrainAvoidance planner = new CameraTerrainAvoidance();
        CameraMotion.Point current = new CameraMotion.Point(8, 122, 0);

        CameraTerrainAvoidance.Decision decision = planner.plan("scene", intended, current, terrain, 1_000, true);

        assertTrue(decision.waiting());
        assertEquals(current, decision.destination());
        assertEquals(122, decision.destination().y());
    }

    @Test
    void travelTowardAnUnknownRemotePoseOnlyAdvancesThroughConfirmedLocalSpace() {
        ProbeTerrain terrain = new ProbeTerrain();
        terrain.space = point -> Math.hypot(point.x(), point.z()) <= 5 ? CLEAR : UNKNOWN;
        terrain.path = (from, to) -> Math.hypot(from.x(), from.z()) <= 5
                && Math.hypot(to.x(), to.z()) <= 5 ? CLEAR : UNKNOWN;
        CameraTerrainAvoidance planner = new CameraTerrainAvoidance();
        CameraMotion.Point current = new CameraMotion.Point(0, 122, 0);
        assertNull(planner.landing("scene", intended, terrain));

        CameraTerrainAvoidance.Decision decision = planner.plan("scene", intended, current, terrain, 1_000, true);

        assertFalse(decision.waiting());
        assertEquals(4, distance(current, decision.destination()), 1.0E-9);
        assertEquals(CLEAR, terrain.path(current, decision.destination()));
        assertEquals(122, decision.destination().y());
    }

    @Test
    void noLegalPoseOrWorldHeightHeadroomLeavesTheCameraWaiting() {
        ProbeTerrain terrain = new ProbeTerrain();
        terrain.space = point -> BLOCKED;
        terrain.path = (from, to) -> BLOCKED;
        terrain.maximumY = 122;
        CameraTerrainAvoidance planner = new CameraTerrainAvoidance();
        CameraMotion.Point current = new CameraMotion.Point(8, 122, 0);

        assertNull(planner.landing("scene", intended, terrain));
        CameraTerrainAvoidance.Decision decision = planner.plan("scene", intended, current, terrain, 1_000, false);

        assertTrue(decision.waiting());
        assertEquals(current, decision.destination());
    }

    @Test
    void planningIsCachedFor250MillisecondsAndExhaustiveSearchHasABoundedProbeCount() {
        ProbeTerrain terrain = new ProbeTerrain();
        terrain.view = (point, lookAt) -> BLOCKED;
        CameraTerrainAvoidance planner = new CameraTerrainAvoidance();
        CameraMotion.Point current = new CameraMotion.Point(8, 122, 0);
        CameraTerrainAvoidance.Decision first = planner.plan("scene", intended, current, terrain, 1_000, false);
        int firstQueries = terrain.queries;

        assertTrue(first.waiting());
        assertTrue(firstQueries > 0 && firstQueries <= 512);
        assertEquals(first, planner.plan("scene", intended, current, terrain, 1_249, false));
        assertEquals(firstQueries, terrain.queries, "render/tick calls inside the interval must not rescan terrain");
        planner.plan("scene", intended, current, terrain, 1_250, false);
        assertTrue(terrain.queries > firstQueries);
        assertTrue(terrain.queries - firstQueries <= 512);
    }

    @Test
    void changingSubjectsDiscardsThePreviousSubjectsTemporaryLiftAndPlanningCache() {
        ProbeTerrain terrain = new ProbeTerrain();
        terrain.space = point -> point.y() >= 128.3 ? CLEAR : BLOCKED;
        CameraTerrainAvoidance planner = new CameraTerrainAvoidance();
        CameraTerrainAvoidance.Decision first = planner.plan("old-scene", intended,
                new CameraMotion.Point(12, 128.5, 0), terrain, 1_000, false);
        assertTrue(first.destination().y() >= 128.3);

        terrain.space = point -> CLEAR;
        CameraMotion.Shot nextShot = new CameraMotion.Shot(new CameraMotion.Point(12, 75, 0), center);
        CameraTerrainAvoidance.Decision next = planner.plan("new-scene", nextShot,
                nextShot.position(), terrain, 1_001, false);

        assertFalse(next.waiting());
        assertEquals(nextShot.position(), next.destination());
        assertFalse(next.adjusted());
    }

    @Test
    void unobstructedPlayerShotsKeepTheirDynamicRelativeHeightRatherThanTheSceneBaseline() {
        ProbeTerrain terrain = new ProbeTerrain();
        CameraTerrainAvoidance planner = new CameraTerrainAvoidance();
        for (int step = 0; step < 3; step++) {
            double playerY = 80 + step * 2;
            CameraMotion.Point playerCenter = new CameraMotion.Point(0, playerY + 1.2, 0);
            CameraMotion.Shot playerShot = new CameraMotion.Shot(new CameraMotion.Point(12, playerCenter.y() + 4, 0), playerCenter);

            CameraTerrainAvoidance.Decision decision = planner.plan("player", playerShot,
                    playerShot.position(), terrain, 1_000 + step * 250L, false);

            assertEquals(playerY + 1.2 + 4, decision.destination().y(), 1.0E-9);
            assertFalse(decision.waiting());
            assertFalse(decision.adjusted());
        }
    }

    private static double distance(CameraMotion.Point first, CameraMotion.Point second) {
        return Math.sqrt(Math.pow(first.x() - second.x(), 2) + Math.pow(first.y() - second.y(), 2)
                + Math.pow(first.z() - second.z(), 2));
    }

    private static final class ProbeTerrain implements CameraTerrainAvoidance.Terrain {
        Function<CameraMotion.Point, CameraTerrainAvoidance.Clearance> space = point -> CLEAR;
        BiFunction<CameraMotion.Point, CameraMotion.Point, CameraTerrainAvoidance.Clearance> path = (from, to) -> CLEAR;
        BiFunction<CameraMotion.Point, CameraMotion.Point, CameraTerrainAvoidance.Clearance> view = (point, lookAt) -> CLEAR;
        double maximumY = 320;
        int queries;

        @Override
        public CameraTerrainAvoidance.Clearance space(CameraMotion.Point feet) {
            queries++;
            return feet.y() > maximumY ? BLOCKED : space.apply(feet);
        }

        @Override
        public CameraTerrainAvoidance.Clearance path(CameraMotion.Point from, CameraMotion.Point to) {
            queries++;
            return from.y() > maximumY || to.y() > maximumY ? BLOCKED : path.apply(from, to);
        }

        @Override
        public CameraTerrainAvoidance.Clearance view(CameraMotion.Point feet, CameraMotion.Point lookAt) {
            queries++;
            return view.apply(feet, lookAt);
        }

        @Override
        public double maxY() {
            return maximumY;
        }
    }
}
