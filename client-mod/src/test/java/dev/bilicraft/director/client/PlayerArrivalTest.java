package dev.bilicraft.director.client;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class PlayerArrivalTest {
    private final UUID session = new UUID(1, 2);
    private final UUID target = new UUID(3, 4);

    @Test
    void nearbySafePlayerCanConfirmWithoutAnyTeleportAndOnlyOnce() {
        PlayerArrival arrival = new PlayerArrival(session, target, "world", new CameraMotion.Point(1, 90, 2), 0, 20_000);
        assertTrue(arrival.takeReady(250, true));
        assertTrue(arrival.readySent());
        assertFalse(arrival.takeReady(251, true));
        assertFalse(arrival.takeTeleport(251, true));
    }

    @Test
    void farPlayerTravelWaitsForAnOpaqueTransitionAndLoadedSafeConfirmation() {
        PlayerArrival arrival = new PlayerArrival(session, target, "world", new CameraMotion.Point(2_000, 90.5, -3_000), 0, 20_000);
        assertFalse(arrival.takeTeleport(100, false));
        assertFalse(arrival.takeReady(100, false));
        assertTrue(arrival.takeTeleport(250, true));
        assertFalse(arrival.takeTeleport(300, true));
        assertEquals("minecraft:tp @s 2000.0 90.5 -3000.0", arrival.teleportCommand());
        assertFalse(arrival.takeReady(500, false));
        assertTrue(arrival.takeReady(1_000, true));
    }

    @Test
    void preparationExpiresWithinFifteenSecondsAndNeverOutlivesTheServerPermit() {
        PlayerArrival local = new PlayerArrival(session, target, "world", new CameraMotion.Point(1, 90, 2), 1_000, 30_000);
        assertFalse(local.timedOut(15_999));
        assertTrue(local.timedOut(16_000));
        assertFalse(local.takeTeleport(16_000, true));
        assertFalse(local.takeReady(16_000, true));
        PlayerArrival shorter = new PlayerArrival(session, target, "world", new CameraMotion.Point(1, 90, 2), 1_000, 5_000);
        assertTrue(shorter.timedOut(5_000));
    }

    @Test
    void correctionsAreBoundedAndAuthorizationCannotTransferToAnotherWorldOrSession() {
        PlayerArrival arrival = new PlayerArrival(session, target, "world", new CameraMotion.Point(1, 90, 2), 0, 20_000);
        assertTrue(arrival.matches(session, target, "world"));
        assertFalse(arrival.matches(new UUID(9, 9), target, "world"));
        assertFalse(arrival.matches(session, target, "other"));
        assertFalse(arrival.matches(session, new UUID(9, 9), "world"));
        assertTrue(arrival.takeTeleport(250, true));
        assertTrue(arrival.redirect(new CameraMotion.Point(2, 90, 2)));
        assertTrue(arrival.takeTeleport(300, true));
        assertTrue(arrival.redirect(new CameraMotion.Point(3, 90, 2)));
        assertTrue(arrival.takeTeleport(350, true));
        assertFalse(arrival.redirect(new CameraMotion.Point(4, 90, 2)));
    }
}
