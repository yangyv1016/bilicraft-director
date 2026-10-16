package dev.bilicraft.director.client;

import org.junit.jupiter.api.Test;

import static dev.bilicraft.director.client.ConnectionSession.OfflineScreen.*;
import static org.junit.jupiter.api.Assertions.*;

class ConnectionSessionTest {
    @Test
    void oldWorldStillVisibleAfterDisconnectCannotBeMistakenForANewJoin() {
        ConnectionSession session = new ConnectionSession();
        session.joined();
        session.updateReady(0, true, false);
        session.updateReady(2_000, true, false);
        assertTrue(session.ready());
        session.disconnected(true, 3_000);
        session.updateReady(3_001, true, false); // Old player/world/network handler still exist.
        session.updateReady(15_000, true, false);
        assertFalse(session.shouldResume());
        assertFalse(session.ready());
        assertTrue(session.reconnectPending());
        assertTrue(session.takeReconnectAttempt(15_000, DISCONNECTED));
    }

    @Test
    void retryWaitsTenSecondsThenThirtyAndSurvivesTeardownAndConnectingScreens() {
        ConnectionSession session = new ConnectionSession();
        session.disconnected(true, 1_000);
        assertFalse(session.takeReconnectAttempt(2_000, TRANSITION));
        assertTrue(session.reconnectPending());
        assertFalse(session.takeReconnectAttempt(10_999, DISCONNECTED));
        assertTrue(session.takeReconnectAttempt(11_000, DISCONNECTED));
        assertFalse(session.takeReconnectAttempt(11_001, TRANSITION));
        assertFalse(session.takeReconnectAttempt(12_000, CONNECTING));
        assertFalse(session.takeReconnectAttempt(40_999, DISCONNECTED));
        assertTrue(session.takeReconnectAttempt(41_000, DISCONNECTED));
        assertFalse(session.takeReconnectAttempt(41_000, DISCONNECTED));
    }

    @Test
    void returningToMenuOrManuallyStoppingCancelsWithoutInterruptingAnOrdinaryJoin() {
        ConnectionSession session = new ConnectionSession();
        session.disconnected(false, 0);
        assertFalse(session.takeReconnectAttempt(100_000, DISCONNECTED));
        session.disconnected(true, 0);
        assertFalse(session.takeReconnectAttempt(1, EXIT));
        assertFalse(session.reconnectPending());
        session.disconnected(true, 0);
        session.cancelReconnect();
        assertFalse(session.takeReconnectAttempt(100_000, DISCONNECTED));
        session.joined();
        session.updateReady(100_000, true, false);
        session.updateReady(102_000, true, false);
        assertTrue(session.setupReady());
        assertFalse(session.shouldResume());
    }

    @Test
    void newJoinWaitsForAllResourcesAndTwoContinuousSecondsOfVisibleWorld() {
        ConnectionSession session = new ConnectionSession();
        session.disconnected(true, 0);
        session.joined();
        session.updateReady(20_000, true, true);
        session.updateReady(40_000, true, true);
        assertFalse(session.shouldResume());
        assertFalse(session.setupReady());
        session.updateReady(40_001, true, false);
        session.updateReady(42_000, true, false);
        assertFalse(session.ready());
        session.updateReady(42_001, true, false);
        assertTrue(session.shouldResume());
        assertTrue(session.setupReady());
        assertFalse(session.takeReconnectAttempt(100_000, DISCONNECTED), "a joined connection cannot start overlapping attempts");
    }

    @Test
    void loadingScreenOverlayOrAnotherPackRestartsTheStableWindow() {
        ConnectionSession session = new ConnectionSession();
        session.joined();
        session.updateReady(0, true, false);
        session.updateReady(1_000, false, false);
        session.updateReady(5_000, true, false);
        session.updateReady(6_999, true, false);
        assertFalse(session.ready());
        session.updateReady(7_000, true, true);
        session.updateReady(10_000, true, false);
        session.updateReady(11_999, true, false);
        assertFalse(session.ready());
        session.updateReady(12_000, true, false);
        assertTrue(session.ready());
        session.updateReady(13_000, false, false);
        assertFalse(session.ready());
        assertTrue(session.setupReady(), "later overlays must not stop an established connection's heartbeat");
        session.disconnected(false, 14_000);
        assertFalse(session.setupReady());
    }

    @Test
    void multiPackDownloadAndReloadFinishBeforeMapOrAutomaticResumeCanStart() {
        ConnectionSession session = new ConnectionSession();
        ResourcePackTracker packs = new ResourcePackTracker();
        var first = new java.util.UUID(1, 2);
        var second = new java.util.UUID(3, 4);
        packs.accepted(first); // Configuration-stage download, before PLAY/JOIN.
        session.disconnected(true, 0);
        session.joined();
        packs.accepted(second);
        session.updateReady(20_000, true, packs.pending());
        assertFalse(session.ready());
        packs.finished(first);
        session.updateReady(40_000, true, packs.pending());
        assertFalse(session.ready());
        packs.finished(second);
        session.updateReady(50_000, false, packs.pending()); // Resource reload overlay still visible.
        session.updateReady(60_000, true, packs.pending());
        assertFalse(session.ready());
        session.updateReady(62_000, true, packs.pending());
        assertTrue(session.shouldResume());
    }
}
