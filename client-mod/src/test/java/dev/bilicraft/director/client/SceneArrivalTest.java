package dev.bilicraft.director.client;

import dev.bilicraft.director.protocol.Message;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SceneArrivalTest {
    private static final CameraMotion.Point DESTINATION = new CameraMotion.Point(10012.5, 84, -5000);

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
