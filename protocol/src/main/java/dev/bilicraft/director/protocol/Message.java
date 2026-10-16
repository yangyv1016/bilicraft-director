package dev.bilicraft.director.protocol;

import java.util.List;
import java.util.UUID;

public sealed interface Message {
    record IdlePointSync(String worldKey, IdlePose point) implements Message {
    }

    record IdlePose(double x, double y, double z, float yaw, float pitch) {
        public IdlePose {
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
                    || Math.abs(x) > 30_000_000 || Math.abs(z) > 30_000_000
                    || !Float.isFinite(yaw) || !Float.isFinite(pitch) || pitch < -90 || pitch > 90) {
                throw new IllegalArgumentException("挂机点坐标或方向无效，pitch须在-90～90之间");
            }
            yaw = ((yaw % 360) + 540) % 360 - 180;
        }
    }

    record Hello(int version) implements Message {
    }

    record HelloReply(int version, boolean allowed, String worldKey, String detail) implements Message {
    }

    record SnapshotRequest() implements Message {
    }

    record Snapshot(List<PlayerView> players, List<SceneView> scenes) implements Message {
        public Snapshot {
            players = List.copyOf(players);
            scenes = List.copyOf(scenes);
        }
    }

    record ObserveRequest(UUID targetId, int requestedDurationSeconds) implements Message {
        public ObserveRequest(UUID targetId) {
            this(targetId, 0);
        }
    }

    record ObserveUpdate(ObserveState state, UUID targetId, long deadlineEpochMillis,
                         int durationSeconds, String reason) implements Message {
    }

    record Stop() implements Message {
    }

    record Heartbeat() implements Message {
    }

    record Error(String reason) implements Message {
    }

    record DurationRequest(UUID targetId, int requestedDurationSeconds) implements Message {
    }

    record DurationReply(UUID targetId, boolean accepted, ObserveState state, int durationSeconds,
                         long deadlineEpochMillis, String reason) implements Message {
    }

    record MapRequest(long requestId) implements Message {
    }

    record MapSource(long requestId, String worldKey, boolean configured, String baseUrl, String dynmapWorld,
                     List<RegionBounds> exclusions, String detail) implements Message {
        public MapSource {
            exclusions = List.copyOf(exclusions);
        }
    }

    record WorldPolicy(String worldKey, UUID worldId, boolean automaticPatrol,
                       List<RegionBounds> exclusions) implements Message {
        public WorldPolicy {
            exclusions = List.copyOf(exclusions);
        }
    }

    record RegionBounds(double minX, double minZ, double maxX, double maxZ) {
    }

    enum ObserveState {
        NOTICE,
        FOLLOWING,
        ENDED
    }

    enum SceneKind {
        VIEWPOINT,
        AREA
    }

    record PlayerView(UUID id, String name, String world, double x, double y, double z,
                      float yaw, float pitch, long lastActivityEpochMillis) {
    }

    record SceneView(String id, String name, SceneKind kind, String world,
                     double x, double y, double z, double radius) {
    }
}
