package dev.bilicraft.director.client;

import java.math.BigDecimal;

/** One self-teleport per scene transition; the controller confirms the real player position and loaded chunks. */
final class SceneArrival {
    private final CameraMotion.Point destination;
    private final long startedAt;
    private boolean commandSent;

    SceneArrival(CameraMotion.Point destination, long startedAt) {
        this.destination = destination;
        this.startedAt = startedAt;
    }

    CameraMotion.Point destination() {
        return destination;
    }

    boolean takeTeleportCommand(long now) {
        if (commandSent || now - startedAt < 250 || timedOut(now)) {
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
        return commandSent && chunksLoaded && dx * dx + dy * dy + dz * dz <= 16;
    }

    boolean timedOut(long now) {
        return now - startedAt >= 15_000;
    }
}
