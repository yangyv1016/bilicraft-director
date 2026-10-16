package dev.bilicraft.director.client;

import java.util.function.Supplier;

/** Rechecks a suspended camera on the client thread without treating unloaded terrain as safe. */
final class CameraTerrainRecovery {
    private boolean blocked;
    private long nextCheckAt;

    void block() {
        blocked = true;
        nextCheckAt = 0;
    }

    void reset() {
        blocked = false;
        nextCheckAt = 0;
    }

    boolean blocked() {
        return blocked;
    }

    boolean resumeIfClear(long now, Supplier<CameraTerrainAvoidance.Clearance> query) {
        if (!blocked || now < nextCheckAt) return false;
        nextCheckAt = now + 250;
        CameraTerrainAvoidance.Clearance clearance = query.get();
        if (!blocked || clearance != CameraTerrainAvoidance.Clearance.CLEAR) return false;
        reset();
        return true;
    }
}
