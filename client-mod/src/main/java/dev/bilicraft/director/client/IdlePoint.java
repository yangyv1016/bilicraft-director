package dev.bilicraft.director.client;

import dev.bilicraft.director.protocol.Message;

/** A synchronized server camera pose, never an orbit center. */
record IdlePoint(Message.SceneView scene, float yaw, float pitch) {
    static final String SCENE_ID = "server:idle";

    IdlePoint(String world, double x, double y, double z, float yaw, float pitch) {
        this(new Message.SceneView(SCENE_ID, "挂机点", Message.SceneKind.VIEWPOINT, world, x, y, z, 6), yaw, pitch);
    }

    IdlePoint {
        if (!scene.id().equals(SCENE_ID) || scene.kind() != Message.SceneKind.VIEWPOINT || scene.world().isBlank()) {
            throw new IllegalArgumentException("挂机点场景标识无效");
        }
        Message.IdlePose pose = new Message.IdlePose(scene.x(), scene.y(), scene.z(), yaw, pitch);
        yaw = pose.yaw();
    }

    boolean matches(Message.SceneView target) {
        return scene.equals(target);
    }

    CameraMotion.Point position() {
        return new CameraMotion.Point(scene.x(), scene.y(), scene.z());
    }
}
