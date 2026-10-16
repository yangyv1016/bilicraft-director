package dev.bilicraft.director.client;

import java.math.BigDecimal;

/** Plans scene travel; the controller confirms the real player position and loaded destination chunk. */
final class SceneArrival {
    private final CameraMotion.Point destination;
    private final long startedAt;
    private final boolean teleport;
    private final long timeoutMillis;
    private boolean commandSent;

    SceneArrival(CameraMotion.Point destination, long startedAt) {
        this(destination, startedAt, true, 15_000);
    }

    private SceneArrival(CameraMotion.Point destination, long startedAt, boolean teleport, long timeoutMillis) {
        this.destination = destination;
        this.startedAt = startedAt;
        this.teleport = teleport;
        this.timeoutMillis = timeoutMillis;
    }

    static SceneArrival plan(CameraMotion.Point actualPosition, CameraMotion.Point center,
                             double radius, double heightY, double speed, long now) {
        return plan(actualPosition, center, radius, heightY, speed, now, true);
    }

    static SceneArrival planFlight(CameraMotion.Point actualPosition, CameraMotion.Point center,
                                   double radius, double heightY, double speed, long now) {
        return plan(actualPosition, center, radius, heightY, speed, now, false);
    }

    private static SceneArrival plan(CameraMotion.Point actualPosition, CameraMotion.Point center,
                                     double radius, double heightY, double speed, long now, boolean teleportAllowed) {
        double dx = actualPosition.x() - center.x();
        double dz = actualPosition.z() - center.z();
        double horizontalDistance = Math.hypot(dx, dz);
        CameraMotion.Point destination = new CameraMotion.Point(
                center.x() + (horizontalDistance == 0 ? radius : dx / horizontalDistance * radius), heightY,
                center.z() + (horizontalDistance == 0 ? 0 : dz / horizontalDistance * radius));
        double travelX = actualPosition.x() - destination.x();
        double travelY = actualPosition.y() - destination.y();
        double travelZ = actualPosition.z() - destination.z();
        double distance = Math.sqrt(travelX * travelX + travelY * travelY + travelZ * travelZ);
        boolean teleport = teleportAllowed && distance > 128;
        long timeoutMillis = teleport ? 15_000 : (long) Math.ceil(distance / speed * 1_000) + 15_000;
        return new SceneArrival(destination, now, teleport, timeoutMillis);
    }

    CameraMotion.Point destination() {
        return destination;
    }

    boolean teleport() {
        return teleport;
    }

    boolean takeTeleportCommand(long now) {
        if (!teleport || commandSent || now - startedAt < 250 || timedOut(now)) {
            return false;
        }
        commandSent = true;
        return true;
    }

    String teleportCommand() {
        return "minecraft:tp @s " + BigDecimal.valueOf(destination.x()).toPlainString() + " "
                + BigDecimal.valueOf(destination.y()).toPlainString() + " "
                + BigDecimal.valueOf(destination.z()).toPlainString();
    }

    boolean arrived(CameraMotion.Point actualPosition, boolean chunksLoaded) {
        double dx = actualPosition.x() - destination.x();
        double dy = actualPosition.y() - destination.y();
        double dz = actualPosition.z() - destination.z();
        return (!teleport || commandSent) && chunksLoaded && dx * dx + dy * dy + dz * dz <= (teleport ? 16 : 1);
    }

    boolean timedOut(long now) {
        return now - startedAt >= timeoutMillis;
    }
}
