package dev.bilicraft.director.protocol;

import java.util.List;
import java.util.UUID;

public sealed interface Message {
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
