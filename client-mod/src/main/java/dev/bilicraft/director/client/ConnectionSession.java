package dev.bilicraft.director.client;

/** Client-thread connection lifecycle; an old world can never count as a completed reconnect. */
final class ConnectionSession {
    enum OfflineScreen { DISCONNECTED, CONNECTING, EXIT, TRANSITION }

    private boolean reconnectPending;
    private boolean joined;
    private boolean ready;
    private boolean setupReady;
    private long stableSince = -1;
    private long nextAttemptAt;

    void disconnected(boolean wasRunning, long now) {
        nextAttemptAt = now + (reconnectPending ? 30_000 : 10_000);
        reconnectPending |= wasRunning;
        joined = false;
        ready = false;
        setupReady = false;
        stableSince = -1;
    }

    void joined() {
        joined = true;
        ready = false;
        setupReady = false;
        stableSince = -1;
    }

    void updateReady(long now, boolean worldVisible, boolean resourcePacksPending) {
        if (!joined || !worldVisible || resourcePacksPending) {
            stableSince = -1;
            ready = false;
            return;
        }
        if (stableSince < 0) stableSince = now;
        ready = now - stableSince >= 2_000;
        setupReady |= ready;
    }

    boolean ready() { return ready; }

    boolean setupReady() { return setupReady; }

    boolean reconnectPending() { return reconnectPending; }

    boolean shouldResume() { return reconnectPending && ready; }

    boolean takeReconnectAttempt(long now, OfflineScreen screen) {
        if (!reconnectPending || joined) return false;
        if (screen == OfflineScreen.EXIT) cancelReconnect();
        if (!reconnectPending || screen != OfflineScreen.DISCONNECTED || now < nextAttemptAt) return false;
        nextAttemptAt = now + 30_000;
        return true;
    }

    void cancelReconnect() {
        reconnectPending = false;
    }
}
