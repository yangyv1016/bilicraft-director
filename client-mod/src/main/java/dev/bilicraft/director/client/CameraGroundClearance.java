package dev.bilicraft.director.client;

/** Plans a retained local height before smoothing, then guards the actual speed-limited step. */
final class CameraGroundClearance {
    private String subjectId;
    private double floorY = Double.NaN;
    private long previousPlanAt;
    private long lowerSince = -1;

    void reset() {
        subjectId = null;
        floorY = Double.NaN;
        previousPlanAt = 0;
        lowerSince = -1;
    }

    double plannedFloorY(String id) {
        return id.equals(subjectId) ? floorY : Double.NaN;
    }

    CameraMotion.Point plan(String id, CameraMotion.Point current, CameraMotion.Point waypoint,
                            CameraMotion.Point routeDestination, CameraMotion.Point previousVelocity, double speed,
                            boolean continuing, CameraTerrainAvoidance.Terrain terrain, long now) {
        if (!id.equals(subjectId)) {
            reset();
            subjectId = id;
        }
        double clearance = terrain.minimumGroundClearance();
        if (clearance <= 0) {
            reset();
            return waypoint;
        }
        double elapsed = previousPlanAt == 0 ? 0 : Math.clamp((now - previousPlanAt) / 1000.0, 0, 0.25);
        previousPlanAt = now;
        CameraMotion.Point surface = terrain.surface(current);
        if (surface == null) {
            lowerSince = -1;
            return null;
        }
        double requiredY = surface.y() + clearance;
        CameraMotion.Point destinationSurface = terrain.surface(routeDestination);
        boolean forecastKnown = destinationSurface != null;
        if (destinationSurface != null) requiredY = Math.max(requiredY, destinationSurface.y() + clearance);
        // Look ahead along both the planned leg and the remaining inertia. Heightmap reads never load chunks.
        double lookAhead = Math.min(16, speed * 3 + 2);
        double remaining = Math.hypot(routeDestination.x() - current.x(), routeDestination.z() - current.z());
        for (CameraMotion.Point direction : new CameraMotion.Point[]{
                new CameraMotion.Point(waypoint.x() - current.x(), 0, waypoint.z() - current.z()), previousVelocity}) {
            double horizontal = Math.hypot(direction.x(), direction.z());
            if (horizontal < 0.0001) continue;
            double extent = continuing ? lookAhead : Math.min(lookAhead, remaining);
            for (double distance = 0.5; distance <= extent + 0.5; distance += 0.5) {
                double sampleDistance = Math.min(distance, extent);
                surface = terrain.surface(current.offset(direction.x() / horizontal * sampleDistance,
                        0, direction.z() / horizontal * sampleDistance));
                if (surface == null) {
                    forecastKnown = false;
                    break;
                }
                requiredY = Math.max(requiredY, surface.y() + clearance);
                if (distance >= extent) break;
            }
        }
        // A small reserve prevents block-height quantization from repeatedly engaging the hard climb guard.
        requiredY = Math.min(terrain.maxY(), requiredY + 1);
        if (Double.isNaN(floorY) || requiredY >= floorY) {
            floorY = requiredY;
            lowerSince = -1;
        } else if (!forecastKnown) {
            lowerSince = -1;
        } else {
            if (lowerSince < 0) lowerSince = now;
            if (now - lowerSince >= 2_000) floorY = Math.max(requiredY, floorY - elapsed * 0.5);
        }
        return new CameraMotion.Point(waypoint.x(), Math.max(waypoint.y(), floorY), waypoint.z());
    }

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
