package dev.bilicraft.director.client;

/** Local shot planning only. The controller still checks chunks, world bounds and speed before movement. */
final class CameraMotion {
    enum Mode { AUTO, FOLLOW, ORBIT, DOLLY, TRUCK, CRANE, PAN, STATIC }

    record Point(double x, double y, double z) {
        Point offset(double dx, double dy, double dz) {
            return new Point(x + dx, y + dy, z + dz);
        }
    }

    record Subject(String id, Point center, double yawDegrees, boolean player, boolean area,
                   boolean moving, double radius) { }

    record Shot(Point position, Point lookAt) { }

    /** Expands only the target X/Z composition; movement and terrain checks stay with the controller. */
    static Shot widenForPitch(Shot intended, Point actual, double temporaryLift, double eyeHeight,
                              double maxPitchDegrees, double maximumDistance) {
        double dx = intended.position().x() - intended.lookAt().x();
        double dz = intended.position().z() - intended.lookAt().z();
        double radius = Math.hypot(dx, dz);
        double eyeY = Math.max(intended.position().y() + temporaryLift, actual.y()) + eyeHeight;
        double threshold = Math.max(5, maxPitchDegrees - 5);
        double desiredRadius = Math.min(maximumDistance,
                Math.abs(eyeY - intended.lookAt().y()) / Math.tan(Math.toRadians(threshold)));
        if (desiredRadius <= radius) return intended;
        if (radius == 0) {
            dx = actual.x() - intended.lookAt().x();
            dz = actual.z() - intended.lookAt().z();
            radius = Math.hypot(dx, dz);
            if (radius == 0) {
                dx = 1;
                radius = 1;
            }
        }
        return new Shot(new Point(intended.lookAt().x() + dx / radius * desiredRadius,
                intended.position().y(), intended.lookAt().z() + dz / radius * desiredRadius), intended.lookAt());
    }

    private String subjectId;
    private Mode mode;
    private double baseAngle;
    private double phase;
    private double anchorRadius;
    private double anchorHeight;
    private Point fixedPosition;

    void reset() {
        subjectId = null;
    }

    Shot sample(Subject subject, Point cameraPosition, CameraSettings settings) {
        double radius = subject.player() ? Math.min(24, Math.min(subject.radius(), settings.radius)) : settings.radius;
        double height = subject.player() ? settings.playerHeightOffset : settings.heightY;
        Point center = subject.center();
        double cameraY = subject.player() ? center.y() + height : height;
        if (!subject.id().equals(subjectId) || mode != settings.mode
                || anchorRadius != radius || anchorHeight != height) {
            subjectId = subject.id();
            mode = settings.mode;
            double dx = cameraPosition.x() - center.x();
            double dz = cameraPosition.z() - center.z();
            baseAngle = dx * dx + dz * dz < 0.01 ? 0 : Math.atan2(dz, dx);
            phase = 0;
            anchorRadius = radius;
            anchorHeight = height;
            fixedPosition = new Point(center.x() + Math.cos(baseAngle) * radius, cameraY,
                    center.z() + Math.sin(baseAngle) * radius);
        }
        double radialX = Math.cos(baseAngle);
        double radialZ = Math.sin(baseAngle);
        // Phase advances only when the controller is actually driving, so menus and notice pauses do not jump shots.
        phase += settings.speed / radius / 40;
        Point lookAt = center;
        Point position;
        Mode effectiveMode = mode;
        if (mode == Mode.AUTO && subject.player() && subject.moving()) {
            effectiveMode = Mode.FOLLOW;
        } else if (mode == Mode.AUTO && subject.area()) {
            double width = Math.min(radius, settings.motionAmplitude);
            position = new Point(center.x() + Math.sin(phase) * radius, cameraY,
                    center.z() + Math.sin(2 * phase) * width * 0.5);
            lookAt = center.offset(Math.sin(phase + 0.3) * radius, 0,
                    Math.sin(2 * (phase + 0.3)) * width * 0.5);
            return new Shot(position, lookAt);
        }
        switch (effectiveMode) {
            case FOLLOW -> {
                double angle = subject.player() ? Math.toRadians(subject.yawDegrees()) - Math.PI / 2 + 0.49 : baseAngle;
                position = new Point(center.x() + Math.cos(angle) * radius, cameraY,
                        center.z() + Math.sin(angle) * radius);
                // If AUTO falls back to an orbit after movement stops, begin at the trailing side.
                if (mode == Mode.AUTO) {
                    baseAngle = angle - phase;
                }
            }
            case DOLLY -> {
                double distance = radius - (1 - Math.cos(phase)) * Math.min(radius - 2, settings.motionAmplitude * 0.5) * 0.5;
                position = new Point(center.x() + radialX * distance, cameraY, center.z() + radialZ * distance);
            }
            case TRUCK -> {
                double slide = Math.sin(phase) * Math.min(radius * 0.6, settings.motionAmplitude * 0.5);
                position = new Point(center.x() + radialX * radius * 0.8 - radialZ * slide, cameraY,
                        center.z() + radialZ * radius * 0.8 + radialX * slide);
            }
            case CRANE -> position = new Point(center.x() + radialX * radius,
                    cameraY + Math.sin(phase) * settings.motionAmplitude * 0.25, center.z() + radialZ * radius);
            case PAN -> {
                position = new Point(fixedPosition.x(), cameraY, fixedPosition.z());
                double sweep = Math.sin(phase) * settings.motionAmplitude * 0.5;
                lookAt = center.offset(-radialZ * sweep, 0, radialX * sweep);
            }
            case STATIC -> position = new Point(fixedPosition.x(), cameraY, fixedPosition.z());
            case AUTO, ORBIT -> position = new Point(center.x() + Math.cos(baseAngle + phase) * radius,
                    cameraY, center.z() + Math.sin(baseAngle + phase) * radius);
            default -> throw new IllegalStateException("Unknown camera mode: " + effectiveMode);
        }
        return new Shot(position, lookAt);
    }
}
