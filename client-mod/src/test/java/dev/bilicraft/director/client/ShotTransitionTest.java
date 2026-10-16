package dev.bilicraft.director.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ShotTransitionTest {
    @Test
    void orientationCannotBePreparedUntilFadeOutIsOpaque() {
        ShotTransition transition = new ShotTransition();
        assertEquals(0, transition.opacity(0, false));
        transition.begin(0);
        assertTrue(transition.preparing());
        assertEquals(0, transition.opacity(0, false));
        assertEquals(0.5, transition.opacity(125, false));
        assertFalse(transition.opaque(249));
        assertTrue(transition.opaque(250));
        assertEquals(1, transition.opacity(250, false));
    }

    @Test
    void finishingTravelDoesNotRevealAnUnpreparedOrientationEvenAfterALongWait() {
        ShotTransition transition = new ShotTransition();
        transition.begin(100);
        assertEquals(1, transition.opacity(10_000, true));
        assertEquals(1, transition.opacity(10_050, false));
        assertTrue(transition.preparing());
        transition.reveal(10_100);
        assertFalse(transition.preparing());
        assertEquals(1, transition.opacity(10_100, false));
        assertEquals(0.5, transition.opacity(10_475, false));
        assertEquals(0, transition.opacity(10_850, false));
        assertEquals(0, transition.opacity(20_000, false));
    }

    @Test
    void flightCanBeVisibleAfterItsInitialOrientationWhileTeleportStillHoldsTheMask() {
        ShotTransition transition = new ShotTransition();
        transition.begin(1_000);
        transition.reveal(1_250);
        assertEquals(0, transition.opacity(2_000, false), "a visible flight does not hold the travel mask");
        assertEquals(1, transition.opacity(2_000, true), "an unfinished teleport must remain hidden");
    }

    @Test
    void cancelAndStartingAnotherTargetCannotReuseAPreviousReadyState() {
        ShotTransition transition = new ShotTransition();
        transition.begin(0);
        transition.reveal(250);
        transition.begin(300);
        assertTrue(transition.preparing());
        assertFalse(transition.opaque(549));
        transition.clear();
        assertFalse(transition.preparing());
        assertFalse(transition.opaque(5_000));
        assertEquals(0, transition.opacity(5_000, true));
    }
}
