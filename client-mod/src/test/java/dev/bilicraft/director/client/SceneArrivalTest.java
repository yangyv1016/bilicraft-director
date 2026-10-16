package dev.bilicraft.director.client;

import dev.bilicraft.director.protocol.Message;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SceneArrivalTest {
    private static final CameraMotion.Point DESTINATION = new CameraMotion.Point(10012.5, 84, -5000);

    @Test
    void nearbySceneFliesToTheCameraSideOfTheNewCenterAtAbsoluteY() {
        SceneArrival arrival = SceneArrival.plan(new CameraMotion.Point(9, 80, 12),
                new CameraMotion.Point(0, 300, 0), 10, 122, 2.5, 0);

        assertFalse(arrival.teleport());
        assertEquals(new CameraMotion.Point(6, 122, 8), arrival.destination());
        assertFalse(arrival.takeTeleportCommand(250));
    }

    @Test
    void matchingHorizontalCoordinatesChooseThePositiveXSide() {
        CameraMotion.Point center = new CameraMotion.Point(100, 70, -20);
        SceneArrival arrival = SceneArrival.plan(new CameraMotion.Point(100, 80, -20), center, 12, 122, 2.5, 0);

        assertEquals(new CameraMotion.Point(112, 122, -20), arrival.destination());
        assertFalse(arrival.teleport());
    }

    @Test
    void travelModeUsesThreeDimensionalDistanceWithAnInclusive128BlockFlightBoundary() {
        CameraMotion.Point center = new CameraMotion.Point(0, 70, 0);
        SceneArrival atBoundary = SceneArrival.plan(new CameraMotion.Point(6, -6, 0), center, 6, 122, 2.5, 0);
        SceneArrival beyondBoundary = SceneArrival.plan(new CameraMotion.Point(6, -6.001, 0), center, 6, 122, 2.5, 0);

        assertFalse(atBoundary.teleport(), "a 128 block vertical flight remains a flight");
        assertTrue(beyondBoundary.teleport(), "a large Y difference can require teleport even at the correct X/Z");
        assertFalse(beyondBoundary.takeTeleportCommand(249));
        assertTrue(beyondBoundary.takeTeleportCommand(250));
        assertFalse(beyondBoundary.takeTeleportCommand(251));
    }

    @Test
    void distantSceneKeepsTheExistingSideRatherThanAlwaysTeleportingToPositiveX() {
        SceneArrival arrival = SceneArrival.plan(new CameraMotion.Point(-1_000, 80, 0),
                new CameraMotion.Point(0, 70, 0), 136, 122, 2.5, 0);

        assertTrue(arrival.teleport());
        assertEquals(new CameraMotion.Point(-136, 122, 0), arrival.destination());
    }

    @Test
    void unloadedFlightDestinationNeverTurnsIntoATeleportAndStillNeedsRealArrival() {
        SceneArrival arrival = SceneArrival.plan(new CameraMotion.Point(0, 122, 0),
                new CameraMotion.Point(106, 70, 0), 6, 122, 2.5, 0);

        assertFalse(arrival.teleport());
        assertFalse(arrival.arrived(arrival.destination(), false));
        assertFalse(arrival.takeTeleportCommand(8_000));
        assertFalse(arrival.teleport());
        assertFalse(arrival.arrived(arrival.destination().offset(0.8, 0.8, 0), true));
        assertFalse(arrival.arrived(arrival.destination().offset(1.001, 0, 0), true));
        assertTrue(arrival.arrived(arrival.destination().offset(1, 0, 0), true));
        assertTrue(arrival.arrived(arrival.destination(), true), "flight does not require a teleport command");
    }

    @Test
    void flightTimeoutIncludesTravelTimeAtTheConfiguredSpeedAndFifteenSecondsForConvergence() {
        SceneArrival arrival = SceneArrival.plan(new CameraMotion.Point(0, 122, 0),
                new CameraMotion.Point(106, 70, 0), 6, 122, 2.5, 1_000);

        assertEquals(new CameraMotion.Point(100, 122, 0), arrival.destination());
        assertFalse(arrival.timedOut(55_999));
        assertTrue(arrival.timedOut(56_000));
        assertFalse(arrival.takeTeleportCommand(56_000));
    }

    @Test
    void replanningALiveFlightKeepsItFlyingAndUsesTheNewDistanceSpeedAndStartTime() {
        SceneArrival original = SceneArrival.plan(new CameraMotion.Point(0, 122, 0),
                new CameraMotion.Point(106, 70, 0), 6, 122, 5, 0);
        assertFalse(original.teleport());
        assertTrue(original.timedOut(35_000));

        SceneArrival replanned = SceneArrival.planFlight(new CameraMotion.Point(10, 122, 0),
                new CameraMotion.Point(516, 70, 0), 6, 122, 2, 2_000);

        assertEquals(new CameraMotion.Point(510, 122, 0), replanned.destination());
        assertFalse(replanned.teleport(), "live parameter changes must not turn a flight into a teleport");
        assertFalse(replanned.takeTeleportCommand(2_250));
        assertFalse(replanned.timedOut(35_000), "the old flight budget must not expire the new route");
        assertFalse(replanned.timedOut(266_999));
        assertTrue(replanned.timedOut(267_000));
    }

    @Test
    void fadesOutBeforeSendingExactlyOneSelfTeleport() {
        SceneArrival arrival = new SceneArrival(DESTINATION, 1_000);
        assertFalse(arrival.takeTeleportCommand(1_249));
        assertTrue(arrival.takeTeleportCommand(1_250));
        assertEquals("minecraft:tp @s 10012.5 84.0 -5000.0", arrival.teleportCommand());
        assertFalse(arrival.takeTeleportCommand(1_251));
        assertFalse(arrival.takeTeleportCommand(14_000));
    }

    @Test
    void arrivalNeedsCommandRealPositionAndLoadedChunksTogether() {
        SceneArrival arrival = new SceneArrival(DESTINATION, 0);
        assertFalse(arrival.arrived(DESTINATION, true), "The old location cannot acknowledge an unsent command");
        arrival.takeTeleportCommand(250);
        assertFalse(arrival.arrived(new CameraMotion.Point(0, 84, 0), true));
        assertFalse(arrival.arrived(DESTINATION, false));
        assertFalse(arrival.arrived(DESTINATION.offset(0, 5, 0), true));
        assertTrue(arrival.arrived(DESTINATION.offset(0, 4, 0), true));
    }

    @Test
    void deniedOrUnloadedTeleportHasBoundedWaitAndNeverRetries() {
        SceneArrival arrival = new SceneArrival(DESTINATION, 0);
        assertTrue(arrival.takeTeleportCommand(250));
        assertFalse(arrival.timedOut(14_999));
        assertTrue(arrival.timedOut(15_000));
        assertFalse(arrival.takeTeleportCommand(15_000));
        assertFalse(arrival.arrived(new CameraMotion.Point(0, 0, 0), false));
    }

    @Test
    void expiredTransitionDoesNotSendALateCommandAfterAMenuPause() {
        SceneArrival arrival = new SceneArrival(DESTINATION, 0);
        assertTrue(arrival.timedOut(15_000));
        assertFalse(arrival.takeTeleportCommand(15_000));
    }

    @Test
    void distantWorldCoordinatesDoNotUseScientificNotationInTheVanillaCommand() {
        SceneArrival arrival = new SceneArrival(new CameraMotion.Point(20_000_000, 84, -20_000_000), 0);
        assertEquals("minecraft:tp @s 20000000 84.0 -20000000", arrival.teleportCommand());
    }

    @Test
    void extendedSceneArrivalWaitsForTheCameraPositionRatherThanTheMarkerCenter() {
        CameraMotion.Point markerCenter = new CameraMotion.Point(1_000, 70, 2_000);
        SceneArrival arrival = new SceneArrival(new CameraMotion.Point(markerCenter.x() + 256, 256, markerCenter.z()), 0);
        assertTrue(arrival.takeTeleportCommand(250));
        assertEquals("minecraft:tp @s 1256.0 256.0 2000.0", arrival.teleportCommand());
        assertFalse(arrival.arrived(markerCenter, true));
        assertFalse(arrival.arrived(arrival.destination(), false));
        assertTrue(arrival.arrived(arrival.destination(), true));
    }

    @Test
    void travelAndArrivalTicksPreservePlaybackDurationAndAdjustments() {
        ShowcaseQueue queue = new ShowcaseQueue();
        ShowcaseTarget scene = new ShowcaseTarget.Scene(new Message.SceneView("far", "远处景点",
                Message.SceneKind.VIEWPOINT, "minecraft:overworld", 10_000.5, 80, -5000, 12));
        ShowcaseQueue.Entry entry = queue.manualEntry(scene, 30);
        queue.start(entry, 0);
        SceneArrival arrival = new SceneArrival(DESTINATION, 0);
        assertTrue(arrival.takeTeleportCommand(250));
        assertFalse(queue.tickScene(8_000, true));
        queue.adjust(entry.entryId(), 10, 9_000, true);
        assertEquals(40, queue.remainingSeconds());
        assertTrue(arrival.arrived(DESTINATION, true));
        assertFalse(queue.tickScene(10_000, true), "The arrival tick only resets the playback clock origin");
        assertEquals(40, queue.remainingSeconds());
        assertFalse(queue.tickScene(11_000, false));
        assertEquals(39, queue.remainingSeconds());
    }
}
