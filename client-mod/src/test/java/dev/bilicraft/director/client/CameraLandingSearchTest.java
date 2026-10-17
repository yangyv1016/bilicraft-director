package dev.bilicraft.director.client;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Function;

import static dev.bilicraft.director.client.CameraTerrainAvoidance.Clearance.*;
import static org.junit.jupiter.api.Assertions.*;

class CameraLandingSearchTest {
    private final CameraMotion.Shot intended = new CameraMotion.Shot(
            new CameraMotion.Point(136, 122, 0), new CameraMotion.Point(0, 80, 0));

    @Test
    void searchesEverySameHeightAngleBeforeTryingAnyRaisedPosition() {
        ProbeTerrain terrain = new ProbeTerrain();
        terrain.space = point -> point.x() < -135 || point.y() > 122 ? CLEAR : BLOCKED;
        CameraLandingSearch search = new CameraLandingSearch(intended);

        CameraMotion.Point result = search.poll(terrain, 1_000);

        assertNotNull(result);
        assertEquals(122, result.y());
        assertTrue(result.x() < -135);
        assertTrue(terrain.probed.stream().allMatch(point -> point.y() == 122));
        assertEquals(0, terrain.surfaceQueries, "same-height composition has priority over the heightmap hint");
    }

    @Test
    void aLoadedMountainSurfaceReturnsItsVerifiedClearanceOnTheFirstPoll() {
        CameraMotion.Shot deepShot = new CameraMotion.Shot(new CameraMotion.Point(136, 64, 0), intended.lookAt());
        ProbeTerrain terrain = new ProbeTerrain();
        terrain.space = point -> point.y() >= 300 ? CLEAR : BLOCKED;
        terrain.surface = point -> new CameraMotion.Point(point.x(), 300, point.z());
        CameraLandingSearch search = new CameraLandingSearch(deepShot);

        CameraMotion.Point result = search.poll(terrain, 1_000);

        assertEquals(new CameraMotion.Point(136, 310, 0), result);
        assertEquals(1, terrain.surfaceQueries);
        assertEquals(13, terrain.probed.size(), "the hint follows all twelve same-height candidates");
        assertTrue(terrain.maximumPassQueries <= 16);
        assertEquals(64, deepShot.position().y());
    }

    @Test
    void configuredGroundClearanceRaisesTheFirstSurfaceHintAboveTheActualGround() {
        ProbeTerrain terrain = new ProbeTerrain();
        terrain.minimumGroundClearance = 16;
        terrain.surface = point -> new CameraMotion.Point(point.x(), 260, point.z());
        terrain.space = point -> point.y() >= 276 ? CLEAR : BLOCKED;
        CameraLandingSearch search = new CameraLandingSearch(intended);

        CameraMotion.Point result = search.poll(terrain, 1_000);

        assertEquals(new CameraMotion.Point(136, 276, 0), result);
        assertEquals(13, terrain.probed.size());
        assertTrue(terrain.maximumPassQueries <= 16);
    }

    @Test
    void anUnverifiedGroundClearanceCannotBeUsedAsASafeLanding() {
        ProbeTerrain terrain = new ProbeTerrain();
        terrain.minimumGroundClearance = 16;
        terrain.space = point -> UNKNOWN;
        CameraLandingSearch search = new CameraLandingSearch(intended);

        for (int pass = 0; pass < 16; pass++) {
            terrain.beginPass();
            assertNull(search.poll(terrain, 1_000 + pass * 250L));
        }
        assertTrue(terrain.maximumPassQueries <= 16);
    }

    @Test
    void aSurfaceHintBecomesAvailableOnTheNextPollWithoutWaitingForAnEntireSearchRound() {
        for (String unavailable : new String[]{"surface", "space", "view"}) {
            ProbeTerrain terrain = new ProbeTerrain();
            terrain.surface = point -> unavailable.equals("surface") ? null
                    : new CameraMotion.Point(point.x(), 260, point.z());
            terrain.space = point -> point.y() < 270 ? BLOCKED : unavailable.equals("space") ? UNKNOWN : CLEAR;
            terrain.view = (point, lookAt) -> unavailable.equals("view") ? UNKNOWN : CLEAR;
            CameraLandingSearch search = new CameraLandingSearch(intended);
            assertNull(search.poll(terrain, 1_000), unavailable);
            int firstPassQueries = terrain.probed.size();
            assertEquals(1, terrain.surfaceQueries);
            terrain.surface = point -> new CameraMotion.Point(point.x(), 260, point.z());
            terrain.space = point -> point.y() >= 270 ? CLEAR : BLOCKED;
            terrain.view = (point, lookAt) -> CLEAR;
            terrain.beginPass();

            CameraMotion.Point result = search.poll(terrain, 1_250);

            assertEquals(new CameraMotion.Point(136, 270, 0), result, unavailable);
            assertEquals(2, terrain.surfaceQueries);
            assertEquals(firstPassQueries + 1, terrain.probed.size(), unavailable);
            assertTrue(terrain.maximumPassQueries <= 16);
        }
    }

    @Test
    void aReturnedSurfaceHintIsNotReturnedAgainButANewSurfaceHeightCanBeUsed() {
        ProbeTerrain terrain = new ProbeTerrain();
        terrain.surface = point -> new CameraMotion.Point(point.x(), 260, point.z());
        terrain.space = point -> point.y() >= 270 ? CLEAR : BLOCKED;
        CameraLandingSearch search = new CameraLandingSearch(intended);
        CameraMotion.Point first = search.poll(terrain, 1_000);
        assertEquals(new CameraMotion.Point(136, 270, 0), first);
        terrain.beginPass();

        assertNull(search.poll(terrain, 1_250), "a second correction must continue beyond an already delivered hint");
        terrain.surface = point -> new CameraMotion.Point(point.x(), 280, point.z());
        terrain.beginPass();

        assertEquals(new CameraMotion.Point(136, 290, 0), search.poll(terrain, 1_500));
        assertTrue(terrain.maximumPassQueries <= 16);
    }

    @Test
    void anUnsafeHeightmapHintFallsBackToTheIncrementalVerifiedSearch() {
        ProbeTerrain terrain = new ProbeTerrain();
        terrain.space = point -> point.y() >= 130 && point.y() < 200 ? CLEAR : BLOCKED;
        terrain.surface = point -> new CameraMotion.Point(point.x(), 300, point.z());
        CameraLandingSearch search = new CameraLandingSearch(intended);

        CameraMotion.Point result = find(search, terrain, 1_000);

        assertNotNull(result);
        assertTrue(result.y() >= 130 && result.y() < 130.5);
        assertEquals(310, terrain.probed.get(12).y());
        assertTrue(terrain.maximumPassQueries <= 16);
    }

    @Test
    void aHighSurfaceHintIsClampedToTheHighestLegalCameraPosition() {
        ProbeTerrain terrain = new ProbeTerrain();
        terrain.surface = point -> new CameraMotion.Point(point.x(), 319, point.z());
        terrain.space = point -> point.y() >= terrain.maximumY ? CLEAR : BLOCKED;
        CameraLandingSearch search = new CameraLandingSearch(intended);

        CameraMotion.Point result = search.poll(terrain, 1_000);

        assertNotNull(result);
        assertEquals(terrain.maximumY, result.y(), 1.0E-9);
        assertTrue(terrain.maximumPassQueries <= 16);
    }

    @Test
    void unknownSpaceOrViewAtTheSurfaceHintCannotAuthorizeATeleport() {
        for (boolean spaceUnknown : new boolean[]{true, false}) {
            ProbeTerrain terrain = new ProbeTerrain();
            terrain.surface = point -> new CameraMotion.Point(point.x(), 300, point.z());
            terrain.space = point -> point.y() < 300 ? BLOCKED : spaceUnknown ? UNKNOWN : CLEAR;
            terrain.view = (point, lookAt) -> spaceUnknown ? CLEAR : UNKNOWN;
            CameraLandingSearch search = new CameraLandingSearch(intended);

            assertNull(search.poll(terrain, 1_000));
            assertEquals(310, terrain.probed.get(12).y());
            assertTrue(terrain.maximumPassQueries <= 16);
        }
    }

    @Test
    void searchesRaisedAnglesBeyondTheOriginalAndItsTwoClosestSides() {
        ProbeTerrain terrain = new ProbeTerrain();
        terrain.space = point -> point.z() > 135 && point.y() >= 130 ? CLEAR : BLOCKED;
        CameraLandingSearch search = new CameraLandingSearch(intended);

        CameraMotion.Point result = find(search, terrain, 1_000);

        assertNotNull(result);
        assertTrue(result.z() > 135);
        assertTrue(result.y() >= 130 && result.y() < 130.5);
        assertEquals(136, Math.hypot(result.x(), result.z()), 1.0E-9);
    }

    @Test
    void deepMountainSearchContinuesAcrossFreshBudgetsInsteadOfRepeatingTheFirstLevels() {
        CameraMotion.Shot deepShot = new CameraMotion.Shot(new CameraMotion.Point(136, 64, 0), intended.lookAt());
        ProbeTerrain terrain = new ProbeTerrain();
        terrain.space = point -> point.y() >= 300 ? CLEAR : BLOCKED;
        terrain.scanCost = 320;
        CameraLandingSearch search = new CameraLandingSearch(deepShot);

        CameraMotion.Point result = find(search, terrain, 1_000);

        assertNotNull(result);
        assertTrue(result.y() >= 300 && result.y() < 300.5);
        assertTrue(terrain.probed.size() > 32, "the result lies beyond one query budget's first levels");
        assertTrue(terrain.maximumPassQueries <= 16);
        assertTrue(terrain.remainingScans >= 0);
        assertEquals(64, deepShot.position().y(), "search must not accumulate height into the original shot");
    }

    @Test
    void includesTheExactWorldHeightLimitBeyondTheLastConfiguredHeightStep() {
        ProbeTerrain terrain = new ProbeTerrain();
        terrain.maximumY = 317.1;
        terrain.space = point -> point.y() >= 317.1 ? CLEAR : BLOCKED;
        CameraLandingSearch search = new CameraLandingSearch(intended);

        CameraMotion.Point result = find(search, terrain, 1_000);

        assertNotNull(result);
        assertEquals(terrain.maximumY, result.y(), 1.0E-9);
        assertTrue(terrain.probed.stream().allMatch(point -> point.y() <= terrain.maximumY + 1.0E-9));
    }

    @Test
    void pollingIsLimitedToSixteenCandidateChecksPer250Milliseconds() {
        ProbeTerrain terrain = new ProbeTerrain();
        terrain.space = point -> BLOCKED;
        CameraLandingSearch search = new CameraLandingSearch(intended);

        assertNull(search.poll(terrain, 1_000));
        assertEquals(16, terrain.probed.size());
        assertNull(search.poll(terrain, 1_249));
        assertEquals(16, terrain.probed.size());
        terrain.beginPass();
        assertNull(search.poll(terrain, 1_250));
        assertEquals(32, terrain.probed.size());
        assertTrue(terrain.maximumPassQueries <= 16);
    }

    @Test
    void unknownSpaceAndUnknownViewsNeverAuthorizeLandingAndAreRetriedAfterLoading() {
        for (boolean spaceUnknown : new boolean[]{true, false}) {
            ProbeTerrain terrain = new ProbeTerrain();
            if (spaceUnknown) terrain.space = point -> UNKNOWN;
            else terrain.view = (point, lookAt) -> UNKNOWN;
            CameraLandingSearch search = new CameraLandingSearch(intended);
            for (int pass = 0; pass < 16; pass++) {
                terrain.beginPass();
                assertNull(search.poll(terrain, 1_000 + pass * 250L));
            }

            terrain.space = point -> CLEAR;
            terrain.view = (point, lookAt) -> CLEAR;
            CameraMotion.Point result = find(search, terrain, 5_000);

            assertNotNull(result);
            assertEquals(CLEAR, terrain.space(result));
            assertEquals(CLEAR, terrain.view(result, intended.lookAt()));
        }
    }

    @Test
    void aSecondRepairContinuesToAnotherCandidateWithoutChangingItsOriginalBaseline() {
        ProbeTerrain terrain = new ProbeTerrain();
        CameraLandingSearch search = new CameraLandingSearch(intended);
        CameraMotion.Point first = search.poll(terrain, 1_000);
        terrain.beginPass();

        CameraMotion.Point second = search.poll(terrain, 1_250);

        assertEquals(intended.position(), first);
        assertNotNull(second);
        assertNotEquals(first, second);
        assertEquals(122, second.y());
        assertEquals(136, Math.hypot(second.x(), second.z()), 1.0E-9);
        assertSame(intended, search.intended());
    }

    @Test
    void refinementDoesNotReturnAFormerlySafePositionThatBecameBlockedBetweenTicks() {
        ProbeTerrain terrain = new ProbeTerrain();
        terrain.space = point -> point.y() >= 123 && point.z() > 67 && point.x() > 117 ? CLEAR : BLOCKED;
        CameraLandingSearch search = new CameraLandingSearch(intended);
        assertNull(search.poll(terrain, 1_000));
        terrain.space = point -> BLOCKED;

        terrain.beginPass();
        assertNull(search.poll(terrain, 1_250));
        assertTrue(terrain.maximumPassQueries <= 16);
    }

    @Test
    void adoptingADeepRepairedLandingRetainsItsLiftForTheNextOrdinaryPlan() {
        ProbeTerrain terrain = new ProbeTerrain();
        terrain.space = point -> point.y() >= 300 ? CLEAR : BLOCKED;
        CameraLandingSearch search = new CameraLandingSearch(intended);
        CameraMotion.Point repaired = find(search, terrain, 1_000);
        assertNotNull(repaired);
        CameraTerrainAvoidance planner = new CameraTerrainAvoidance();
        planner.adoptLanding("scene", search.intended(), repaired);
        terrain.beginPass();

        CameraTerrainAvoidance.Decision decision = planner.plan("scene", intended, repaired, terrain, 20_000, false);

        assertFalse(decision.waiting());
        assertEquals(repaired, decision.destination());
        assertEquals(repaired.y() - 122, planner.temporaryLift());
        assertEquals(122, intended.position().y());
    }

    private static CameraMotion.Point find(CameraLandingSearch search, ProbeTerrain terrain, long startedAt) {
        for (int pass = 0; pass < 100; pass++) {
            terrain.beginPass();
            CameraMotion.Point result = search.poll(terrain, startedAt + pass * 250L);
            if (result != null) return result;
        }
        return null;
    }

    private static final class ProbeTerrain implements CameraTerrainAvoidance.Terrain {
        Function<CameraMotion.Point, CameraTerrainAvoidance.Clearance> space = point -> CLEAR;
        BiFunction<CameraMotion.Point, CameraMotion.Point, CameraTerrainAvoidance.Clearance> view = (point, lookAt) -> CLEAR;
        Function<CameraMotion.Point, CameraMotion.Point> surface = point -> null;
        final List<CameraMotion.Point> probed = new ArrayList<>();
        double maximumY = 317.1;
        double minimumGroundClearance;
        int scanCost = 80;
        int remainingScans = 8_192;
        int passQueries;
        int maximumPassQueries;
        int surfaceQueries;

        void beginPass() {
            remainingScans = 8_192;
            passQueries = 0;
        }

        @Override
        public CameraTerrainAvoidance.Clearance space(CameraMotion.Point point) {
            probed.add(point);
            maximumPassQueries = Math.max(maximumPassQueries, ++passQueries);
            if (remainingScans < scanCost) return UNKNOWN;
            remainingScans -= scanCost;
            return point.y() > maximumY ? BLOCKED : space.apply(point);
        }

        @Override
        public CameraTerrainAvoidance.Clearance path(CameraMotion.Point from, CameraMotion.Point to) {
            return CLEAR;
        }

        @Override
        public CameraTerrainAvoidance.Clearance view(CameraMotion.Point point, CameraMotion.Point lookAt) {
            return view.apply(point, lookAt);
        }

        @Override
        public CameraMotion.Point surface(CameraMotion.Point feet) {
            surfaceQueries++;
            return surface.apply(feet);
        }

        @Override
        public double minimumGroundClearance() {
            return minimumGroundClearance;
        }

        @Override
        public double maxY() {
            return maximumY;
        }
    }
}
