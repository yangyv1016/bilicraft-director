package dev.bilicraft.director.client;

import java.math.BigDecimal;
import java.util.UUID;

/** Authorized player preparation only; the server starts follow time after ObserveReady. */
final class PlayerArrival {
    private final UUID sessionId;
    private final UUID targetId;
    private final String world;
    private final long deadline;
    private CameraMotion.Point destination;
    private int teleports;
    private boolean teleportPending = true;
    private boolean readySent;

    PlayerArrival(UUID sessionId, UUID targetId, String world, CameraMotion.Point destination, long now, long serverDeadline) {
        this.sessionId = sessionId;
        this.targetId = targetId;
        this.world = world;
        this.destination = destination;
        deadline = Math.min(serverDeadline, now + 15_000);
    }

    boolean matches(UUID session, UUID target, String currentWorld) {
        return sessionId.equals(session) && targetId.equals(target) && world.equals(currentWorld);
    }

    boolean takeTeleport(long now, boolean opaque) {
        if (timedOut(now) || !opaque || readySent || !teleportPending) return false;
        teleportPending = false;
        teleports++;
        return true;
    }

    boolean redirect(CameraMotion.Point safe) {
        if (readySent || teleports >= 3 || destination.equals(safe)) return false;
        destination = safe;
        teleportPending = true;
        return true;
    }

    boolean takeReady(long now, boolean safeAndLoaded) {
        if (timedOut(now) || readySent || !safeAndLoaded) return false;
        readySent = true;
        return true;
    }

    boolean timedOut(long now) { return now >= deadline; }

    boolean readySent() { return readySent; }

    CameraMotion.Point destination() { return destination; }

    String teleportCommand() {
        return "minecraft:tp @s " + BigDecimal.valueOf(destination.x()).toPlainString() + " "
                + BigDecimal.valueOf(destination.y()).toPlainString() + " "
                + BigDecimal.valueOf(destination.z()).toPlainString();
    }
}
