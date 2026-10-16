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
        double radius = Math.min(subject.radius(), settings.radius);
        Point center = subject.center();
        if (!subject.id().equals(subjectId) || mode != settings.mode
                || anchorRadius != radius || anchorHeight != settings.heightOffset) {
            subjectId = subject.id();
            mode = settings.mode;
            double dx = cameraPosition.x() - center.x();
            double dz = cameraPosition.z() - center.z();
            baseAngle = dx * dx + dz * dz < 0.01 ? 0 : Math.atan2(dz, dx);
            phase = 0;
            anchorRadius = radius;
            anchorHeight = settings.heightOffset;
            fixedPosition = center.offset(Math.cos(baseAngle) * radius, settings.heightOffset, Math.sin(baseAngle) * radius);
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
            position = center.offset(Math.sin(phase) * radius, settings.heightOffset,
                    Math.sin(2 * phase) * width * 0.5);
            lookAt = center.offset(Math.sin(phase + 0.3) * radius, 0,
                    Math.sin(2 * (phase + 0.3)) * width * 0.5);
            return new Shot(position, lookAt);
        }
        switch (effectiveMode) {
            case FOLLOW -> {
                double angle = subject.player() ? Math.toRadians(subject.yawDegrees()) - Math.PI / 2 + 0.49 : baseAngle;
                position = center.offset(Math.cos(angle) * radius, settings.heightOffset, Math.sin(angle) * radius);
                // If AUTO falls back to an orbit after movement stops, begin at the trailing side.
                if (mode == Mode.AUTO) {
                    baseAngle = angle - phase;
                }
            }
            case DOLLY -> {
                double distance = radius - (1 - Math.cos(phase)) * Math.min(radius - 2, settings.motionAmplitude * 0.5) * 0.5;
                position = center.offset(radialX * distance, settings.heightOffset, radialZ * distance);
            }
            case TRUCK -> {
                double slide = Math.sin(phase) * Math.min(radius * 0.6, settings.motionAmplitude * 0.5);
                position = center.offset(radialX * radius * 0.8 - radialZ * slide, settings.heightOffset,
                        radialZ * radius * 0.8 + radialX * slide);
            }
            case CRANE -> position = center.offset(radialX * radius,
                    settings.heightOffset + Math.sin(phase) * settings.motionAmplitude * 0.25, radialZ * radius);
            case PAN -> {
                position = fixedPosition;
                double sweep = Math.sin(phase) * settings.motionAmplitude * 0.5;
                lookAt = center.offset(-radialZ * sweep, 0, radialX * sweep);
            }
            case STATIC -> position = fixedPosition;
            case AUTO, ORBIT -> position = center.offset(Math.cos(baseAngle + phase) * radius,
                    settings.heightOffset, Math.sin(baseAngle + phase) * radius);
            default -> throw new IllegalStateException("Unknown camera mode: " + effectiveMode);
        }
        return new Shot(position, lookAt);
    }
}
