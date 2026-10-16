package dev.bilicraft.director.client;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static dev.bilicraft.director.client.CameraTerrainAvoidance.Clearance.*;
import static org.junit.jupiter.api.Assertions.*;

class CameraTerrainRecoveryTest {
    @Test
    void normalCameraDoesNotReadTerrainOrTriggerAResume() {
        CameraTerrainRecovery recovery = new CameraTerrainRecovery();
        assertFalse(recovery.resumeIfClear(1_000, () -> fail("A normal camera must not query recovery terrain")));
        assertFalse(recovery.blocked());
    }

    @Test
    void unloadedTerrainKeepsTheHoldUntilAConfirmedClearCheck() {
        CameraTerrainRecovery recovery = new CameraTerrainRecovery();
        recovery.block();
        assertFalse(recovery.resumeIfClear(1_000, () -> UNKNOWN));
        assertTrue(recovery.blocked());
        assertTrue(recovery.resumeIfClear(1_250, () -> CLEAR));
        assertFalse(recovery.blocked());
    }

    @Test
    void knownCollisionNeverReleasesTheHold() {
        CameraTerrainRecovery recovery = new CameraTerrainRecovery();
        recovery.block();
        for (long now = 1_000; now <= 3_000; now += 250) {
            assertFalse(recovery.resumeIfClear(now, () -> BLOCKED));
            assertTrue(recovery.blocked());
        }
    }

    @Test
    void repeatedTicksWaitForTheNextQuarterSecondBeforeReadingTerrain() {
        CameraTerrainRecovery recovery = new CameraTerrainRecovery();
        AtomicInteger checks = new AtomicInteger();
        recovery.block();
        assertFalse(recovery.resumeIfClear(1_000, () -> {
            checks.incrementAndGet();
            return UNKNOWN;
        }));
        assertFalse(recovery.resumeIfClear(1_001, () -> fail("Repeated tick must be throttled")));
        assertFalse(recovery.resumeIfClear(1_249, () -> fail("Quarter-second interval has not elapsed")));
        assertTrue(recovery.resumeIfClear(1_250, () -> {
            checks.incrementAndGet();
            return CLEAR;
        }));
        assertEquals(2, checks.get());
    }

    @Test
    void aClearRecoveryResumesOnlyOnce() {
        CameraTerrainRecovery recovery = new CameraTerrainRecovery();
        recovery.block();
        assertTrue(recovery.resumeIfClear(1_000, () -> CLEAR));
        assertFalse(recovery.resumeIfClear(1_250, () -> fail("Recovered camera must not resume again")));
    }

    @Test
    void manualResetCancelsTheScheduledCheckAndItsResume() {
        CameraTerrainRecovery recovery = new CameraTerrainRecovery();
        recovery.block();
        assertFalse(recovery.resumeIfClear(1_000, () -> UNKNOWN));
        recovery.reset();
        assertFalse(recovery.resumeIfClear(1_250, () -> fail("Manual stop cancels the old safety check")));
        assertFalse(recovery.blocked());
    }

    @Test
    void resetDuringTheSafetyCheckCannotResumeTheStoppedCamera() {
        CameraTerrainRecovery recovery = new CameraTerrainRecovery();
        recovery.block();
        assertFalse(recovery.resumeIfClear(1_000, () -> {
            recovery.reset();
            return CLEAR;
        }));
        assertFalse(recovery.blocked());
    }

    @Test
    void aNewHoldCanBeCheckedImmediatelyDespiteThePreviousThrottle() {
        CameraTerrainRecovery recovery = new CameraTerrainRecovery();
        recovery.block();
        assertFalse(recovery.resumeIfClear(1_000, () -> UNKNOWN));
        recovery.block();
        assertTrue(recovery.resumeIfClear(1_001, () -> CLEAR));
    }
}
