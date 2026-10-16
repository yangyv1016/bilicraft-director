package dev.bilicraft.director.client;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ResourcePackTrackerTest {
    @Test
    void acceptedAndDownloadedRemainPendingUntilATerminalStatusForEveryPack() {
        ResourcePackTracker tracker = new ResourcePackTracker();
        UUID first = new UUID(1, 2);
        UUID second = new UUID(3, 4);
        assertFalse(tracker.pending());
        tracker.accepted(first);
        tracker.accepted(first); // DOWNLOADED must not be treated as APPLIED or counted twice.
        tracker.accepted(second);
        assertTrue(tracker.pending());
        tracker.finished(first);
        assertTrue(tracker.pending());
        tracker.finished(second);
        assertFalse(tracker.pending());
        tracker.finished(first);
        assertFalse(tracker.pending());
    }

    @Test
    void failedDeclinedAndRemovedPacksAndOldConnectionsDoNotBlockANewConnection() {
        ResourcePackTracker oldConnection = new ResourcePackTracker();
        ResourcePackTracker newConnection = new ResourcePackTracker();
        UUID id = new UUID(1, 2);
        oldConnection.accepted(id);
        assertFalse(newConnection.pending());
        newConnection.accepted(id);
        oldConnection.finished(id);
        assertTrue(newConnection.pending());
        newConnection.finished(id);
        assertFalse(newConnection.pending());
        newConnection.finished(new UUID(3, 4)); // Declined before acceptance.
        assertFalse(newConnection.pending());
    }
}
