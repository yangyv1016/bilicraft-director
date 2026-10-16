package dev.bilicraft.director.client;

/** Hold the transition opaque until travel and the first shot's orientation are ready. */
final class ShotTransition {
    private long startedAt;
    private boolean active;
    private boolean preparing;

    void begin(long now) {
        startedAt = now;
        active = true;
        preparing = true;
    }

    void clear() {
        active = false;
        preparing = false;
    }

    boolean preparing() {
        return preparing;
    }

    boolean opaque(long now) {
        return active && now - startedAt >= 250;
    }

    void reveal(long now) {
        startedAt = now - 250;
        active = true;
        preparing = false;
    }

    double opacity(long now, boolean arriving) {
        if (!active) return 0;
        long elapsed = Math.max(0, now - startedAt);
        if (elapsed < 250) return elapsed / 250.0;
        if (arriving || preparing) return 1;
        return Math.max(0, 1 - (elapsed - 250) / 750.0);
    }
}
