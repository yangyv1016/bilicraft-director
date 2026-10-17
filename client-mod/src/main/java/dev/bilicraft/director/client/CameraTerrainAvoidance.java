package dev.bilicraft.director.client;

/** Bounded local terrain planning; never changes the saved shot or directly moves the camera. */
final class CameraTerrainAvoidance {
    enum Clearance { CLEAR, BLOCKED, UNKNOWN }

    interface Terrain {
        Clearance space(CameraMotion.Point feet);
        Clearance path(CameraMotion.Point from, CameraMotion.Point to);
        Clearance view(CameraMotion.Point feet, CameraMotion.Point lookAt);
        /** Highest non-air block at this X/Z, or null if its loaded heightmap is unavailable. */
        CameraMotion.Point surface(CameraMotion.Point feet);
        double minimumGroundClearance();
        double maxY();
    }

    record Decision(CameraMotion.Point destination, boolean waiting, boolean adjusted) { }
    private record Pose(CameraMotion.Point point, double angle) { }
    private record Step(CameraMotion.Point point, boolean blocked) { }
    static final double[] ANGLES = {0, 15, -15, 30, -30, 60, -60, 90, -90, 120, -120, 180};
    static final double[] HEIGHTS = {1, 2, 4, 8, 12, 16, 24, 32, 48, 64, 96, 128, 160, 192};
    private String subjectId;
    private long nextPlanAt;
    private long previousPlanAt;
    private long baseClearSince = -1;
    private long originalClearSince = -1;
    private double angle;
    private double lift;
    private int turnSide = 1;
    private Decision previous;
    private CameraMotion.Point resolvedDestination;

    void reset() {
        subjectId = null;
        nextPlanAt = 0;
        previousPlanAt = 0;
        baseClearSince = -1;
        originalClearSince = -1;
        angle = 0;
        lift = 0;
        turnSide = 1;
        previous = null;
        resolvedDestination = null;
    }

    CameraMotion.Point resolvedDestination() {
        return resolvedDestination;
    }

    double temporaryLift() {
        return lift;
    }

    Decision plan(String id, CameraMotion.Shot intended, CameraMotion.Point current,
                  Terrain terrain, long now, boolean travelling) {
        if (!id.equals(subjectId)) {
            reset();
            subjectId = id;
        }
        if (now < nextPlanAt) return previous;
        double elapsed = previousPlanAt == 0 ? 0 : Math.clamp((now - previousPlanAt) / 1000.0, 0, 0.5);
        previousPlanAt = now;
        nextPlanAt = now + 250;
        Pose base = sameHeight(intended, 0, terrain, 0);
        Step baseStep = base == null ? new Step(null, false) : steer(current, base.point(), terrain);
        if (base != null && baseStep.point() != null) {
            if (baseClearSince < 0) baseClearSince = now;
        } else baseClearSince = -1;
        if (base != null && base.angle() == 0 && baseStep.point() != null) {
            if (originalClearSince < 0) originalClearSince = now;
        } else originalClearSince = -1;

        Pose chosen = base;
        if (lift > 0) {
            double recoveringLift = baseClearSince >= 0 && now - baseClearSince >= 2_000
                    ? Math.max(0, lift - elapsed * 0.5) : lift;
            chosen = sameHeight(intended, recoveringLift, terrain, angle);
            if (chosen != null) lift = recoveringLift;
        } else if (angle != 0 && (originalClearSince < 0 || now - originalClearSince < 2_000)) {
            CameraMotion.Point held = offset(intended, angle, 0);
            if (clearPose(held, intended.lookAt(), terrain)) chosen = new Pose(held, angle);
        }
        if (chosen == null) chosen = raised(intended, terrain);
        resolvedDestination = chosen == null ? null : chosen.point();
        if (chosen != null) {
            angle = chosen.angle();
            lift = Math.max(0, chosen.point().y() - intended.position().y());
            Step step = steer(current, chosen.point(), terrain);
            if (step.point() != null) return remember(step.point(), intended.position());
            if (step.blocked()) {
                // An enclosure can have a clear remote pose but no reachable horizontal exit.
                CameraMotion.Point up = current.offset(0, 2, 0);
                if (up.y() <= terrain.maxY() && terrain.path(current, up) == Clearance.CLEAR) {
                    lift = Math.max(lift, up.y() - intended.position().y());
                    baseClearSince = -1;
                    return remember(up, intended.position());
                }
            }
        } else if (travelling) {
            // Continue only through confirmed local free space; an unknown destination is not acknowledged as safe.
            Step step = steer(current, intended.position(), terrain);
            if (step.point() != null) return remember(step.point(), intended.position());
        }
        previous = new Decision(current, true, lift > 0 || angle != 0);
        return previous;
    }

    /** Loaded TP landing repair runs under the existing opaque shot transition, without a path through the mountain. */
    CameraMotion.Point landing(String id, CameraMotion.Shot intended, Terrain terrain) {
        if (!id.equals(subjectId)) {
            reset();
            subjectId = id;
        }
        Pose pose = sameHeight(intended, 0, terrain, 0);
        if (pose == null) pose = raised(intended, terrain);
        if (pose == null) return null;
        adoptLanding(id, intended, pose.point());
        return pose.point();
    }

    void adoptLanding(String id, CameraMotion.Shot intended, CameraMotion.Point point) {
        if (!id.equals(subjectId)) {
            reset();
            subjectId = id;
        }
        double originalAngle = Math.atan2(intended.position().z() - intended.lookAt().z(),
                intended.position().x() - intended.lookAt().x());
        double landingAngle = Math.atan2(point.z() - intended.lookAt().z(), point.x() - intended.lookAt().x());
        angle = Math.IEEEremainder(Math.toDegrees(landingAngle - originalAngle), 360);
        lift = Math.max(0, point.y() - intended.position().y());
        nextPlanAt = 0;
        previousPlanAt = 0;
        baseClearSince = -1;
        originalClearSince = -1;
        previous = null;
        resolvedDestination = point;
    }

    private Decision remember(CameraMotion.Point destination, CameraMotion.Point intended) {
        previous = new Decision(destination, false, angle != 0 || lift > 0
                || distance(destination, intended) > 0.01);
        return previous;
    }

    private Pose sameHeight(CameraMotion.Shot intended, double height, Terrain terrain, double preferredAngle) {
        if (preferredAngle != 0) {
            CameraMotion.Point held = offset(intended, preferredAngle, height);
            if (clearPose(held, intended.lookAt(), terrain)) return new Pose(held, preferredAngle);
        }
        for (double offset : ANGLES) {
            CameraMotion.Point point = offset(intended, offset, height);
            if (clearPose(point, intended.lookAt(), terrain)) return new Pose(point, offset);
        }
        return null;
    }

    private Pose raised(CameraMotion.Shot intended, Terrain terrain) {
        for (double offset : new double[]{angle, angle + 30, angle - 30}) {
            CameraMotion.Point surface = terrain.surface(offset(intended, offset, 0));
            if (surface == null) continue;
            double height = Math.min(terrain.maxY(), Math.max(intended.position().y(),
                    surface.y() + Math.max(10, terrain.minimumGroundClearance()))) - intended.position().y();
            CameraMotion.Point point = offset(intended, offset, height);
            if (clearPose(point, intended.lookAt(), terrain)) return new Pose(point, offset);
        }
        double previousHeight = 0;
        double maximumHeight = terrain.maxY() - intended.position().y();
        for (int level = 0; level <= HEIGHTS.length; level++) {
            double height = level == HEIGHTS.length ? maximumHeight : Math.min(HEIGHTS[level], maximumHeight);
            if (height <= previousHeight) break;
            // The full same-height ring was tried first. Vertical search stays on the current/nearest side.
            for (double offset : new double[]{angle, angle + 30, angle - 30}) {
                CameraMotion.Point point = offset(intended, offset, height);
                if (!clearPose(point, intended.lookAt(), terrain)) continue;
                double low = previousHeight, high = height;
                while (high - low > 0.5) {
                    double middle = (low + high) / 2;
                    if (clearPose(offset(intended, offset, middle), intended.lookAt(), terrain)) high = middle;
                    else low = middle;
                }
                return new Pose(offset(intended, offset, high), offset);
            }
            previousHeight = height;
        }
        return null;
    }

    private Step steer(CameraMotion.Point current, CameraMotion.Point goal, Terrain terrain) {
        double distance = distance(current, goal);
        if (distance < 0.01) return new Step(terrain.space(goal) == Clearance.CLEAR
                && terrain.path(current, goal) == Clearance.CLEAR ? goal : null, false);
        double scale = Math.min(1, 4 / distance);
        double dx = (goal.x() - current.x()) * scale;
        double dy = (goal.y() - current.y()) * scale;
        double dz = (goal.z() - current.z()) * scale;
        CameraMotion.Point direct = current.offset(dx, dy, dz);
        Clearance directPath = terrain.path(current, direct);
        if (directPath == Clearance.CLEAR) return new Step(distance <= 4 ? goal : direct, false);
        boolean blocked = directPath == Clearance.BLOCKED;
        double horizontal = Math.hypot(dx, dz);
        if (horizontal > 0.01) {
            double heading = Math.atan2(dz, dx);
            for (int side : new int[]{turnSide, -turnSide}) {
                for (int degrees : new int[]{30, 60, 90, 120, 150}) {
                    double direction = heading + Math.toRadians(degrees * side);
                    CameraMotion.Point point = current.offset(Math.cos(direction) * 4, 0, Math.sin(direction) * 4);
                    Clearance path = terrain.path(current, point);
                    blocked |= path == Clearance.BLOCKED;
                    if (path == Clearance.CLEAR) {
                        turnSide = side;
                        return new Step(point, blocked);
                    }
                }
            }
        }
        return new Step(null, blocked);
    }

    static boolean clearPose(CameraMotion.Point point, CameraMotion.Point lookAt, Terrain terrain) {
        return terrain.space(point) == Clearance.CLEAR && terrain.view(point, lookAt) == Clearance.CLEAR;
    }

    static CameraMotion.Point offset(CameraMotion.Shot intended, double degrees, double height) {
        if (degrees == 0) return intended.position().offset(0, height, 0);
        double dx = intended.position().x() - intended.lookAt().x();
        double dz = intended.position().z() - intended.lookAt().z();
        if (Math.hypot(dx, dz) < 2) dx = 6;
        double angle = Math.toRadians(degrees);
        return new CameraMotion.Point(intended.lookAt().x() + dx * Math.cos(angle) - dz * Math.sin(angle),
                intended.position().y() + height,
                intended.lookAt().z() + dx * Math.sin(angle) + dz * Math.cos(angle));
    }

    private static double distance(CameraMotion.Point first, CameraMotion.Point second) {
        return Math.sqrt(Math.pow(first.x() - second.x(), 2) + Math.pow(first.y() - second.y(), 2)
                + Math.pow(first.z() - second.z(), 2));
    }
}
