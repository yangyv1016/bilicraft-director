package dev.bilicraft.director.client;

/** Constrains an already speed-limited step; the controller still checks the resulting physical path. */
final class CameraGroundClearance {
    static CameraMotion.Point constrainStep(CameraMotion.Point current, CameraMotion.Point next,
                                            double previousVerticalStep, double maxStep,
                                            CameraTerrainAvoidance.Terrain terrain) {
        double clearance = terrain.minimumGroundClearance();
        if (clearance <= 0) return next;
        CameraMotion.Point currentSurface = terrain.surface(current);
        if (currentSurface == null) return null;
        CameraMotion.Point nextSurface = terrain.surface(next);
        if (nextSurface == null) return null;
        // Vanilla can clear either small horizontal velocity component independently.
        CameraMotion.Point xHeldSurface = terrain.surface(new CameraMotion.Point(current.x(), current.y(), next.z()));
        if (xHeldSurface == null) return null;
        CameraMotion.Point zHeldSurface = terrain.surface(new CameraMotion.Point(next.x(), current.y(), current.z()));
        if (zHeldSurface == null) return null;
        double floor = Math.max(Math.max(currentSurface.y(), nextSurface.y()),
                Math.max(xHeldSurface.y(), zHeldSurface.y())) + clearance;
        if (current.y() < floor) {
            double wantedVerticalStep = Math.min(maxStep, (floor - current.y()) * 0.12);
            double verticalStep = Math.clamp(previousVerticalStep + (wantedVerticalStep - previousVerticalStep) * 0.18,
                    Math.min(0.004, maxStep), maxStep);
            return current.offset(0, verticalStep, 0);
        }
        if (next.y() < floor) return new CameraMotion.Point(next.x(), floor, next.z());
        return next;
    }
}
