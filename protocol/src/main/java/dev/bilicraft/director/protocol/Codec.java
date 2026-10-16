package dev.bilicraft.director.protocol;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

public final class Codec {
    private static final int MAX_NAME_BYTES = 64;
    private static final int MAX_WORLD_BYTES = 256;
    private static final int MAX_TEXT_BYTES = 1_024;
    private static final int MAX_SCENE_ID_BYTES = 64;
    private static final int MAX_SCENE_NAME_BYTES = 256;

    private record SceneKey(String world, String id) {
    }

    private Codec() {
    }

    public static byte[] encode(Message message) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream output = new DataOutputStream(bytes);
        output.writeInt(Protocol.MAGIC);
        output.writeShort(Protocol.VERSION);
        switch (message) {
            case Message.IdlePointSync sync -> {
                output.writeByte(14);
                if (sync.worldKey() == null || sync.worldKey().isBlank()) throw new IOException("Idle world is required");
                writeString(output, sync.worldKey(), MAX_WORLD_BYTES);
                output.writeBoolean(sync.point() != null);
                if (sync.point() != null) {
                    Message.IdlePose point = sync.point();
                    output.writeDouble(point.x());
                    output.writeDouble(point.y());
                    output.writeDouble(point.z());
                    output.writeFloat(point.yaw());
                    output.writeFloat(point.pitch());
                }
            }
            case Message.Hello hello -> {
                output.writeByte(0);
                output.writeInt(hello.version());
            }
            case Message.HelloReply reply -> {
                output.writeByte(1);
                output.writeInt(reply.version());
                output.writeBoolean(reply.allowed());
                writeString(output, reply.worldKey(), MAX_WORLD_BYTES);
                writeString(output, reply.detail(), MAX_TEXT_BYTES);
            }
            case Message.SnapshotRequest request -> {
                output.writeByte(2);
                if (request.requestId() <= 0) throw new IOException("Snapshot request ID must be positive");
                output.writeLong(request.requestId());
            }
            case Message.SnapshotPage page -> {
                output.writeByte(15);
                validateSnapshotPage(page);
                output.writeLong(page.requestId());
                output.writeShort(page.pageIndex());
                output.writeShort(page.pageCount());
                output.writeShort(page.players().size());
                for (Message.PlayerSummary player : page.players()) {
                    writeUuid(output, player.id());
                    writeString(output, player.name(), MAX_NAME_BYTES);
                    writeString(output, player.world(), MAX_WORLD_BYTES);
                    output.writeByte(player.availability().ordinal());
                }
                output.writeShort(page.scenes().size());
                for (Message.SceneView scene : page.scenes()) {
                    writeScene(output, scene);
                }
            }
            case Message.Snapshot snapshot -> {
                output.writeByte(3);
                if (snapshot.players().size() > Protocol.MAX_PLAYERS) {
                    throw new IOException("Too many players in snapshot");
                }
                output.writeShort(snapshot.players().size());
                HashSet<UUID> ids = new HashSet<>();
                for (Message.PlayerView player : snapshot.players()) {
                    if (!ids.add(player.id())) {
                        throw new IOException("Duplicate player ID");
                    }
                    writePlayer(output, player);
                }
                if (snapshot.scenes().size() > Protocol.MAX_SCENES) {
                    throw new IOException("Too many scenes in snapshot");
                }
                output.writeShort(snapshot.scenes().size());
                HashSet<SceneKey> sceneKeys = new HashSet<>();
                for (Message.SceneView scene : snapshot.scenes()) {
                    if (!sceneKeys.add(new SceneKey(scene.world(), scene.id()))) {
                        throw new IOException("Duplicate scene ID in the same world");
                    }
                    writeScene(output, scene);
                }
            }
            case Message.ObserveRequest request -> {
                output.writeByte(4);
                writeUuid(output, request.targetId());
                if (request.requestedDurationSeconds() != 0) {
                    validateDuration(request.requestedDurationSeconds());
                }
                output.writeInt(request.requestedDurationSeconds());
            }
            case Message.ObserveUpdate update -> {
                output.writeByte(5);
                validateObserveUpdate(update);
                output.writeByte(update.state().ordinal());
                output.writeBoolean(update.targetId() != null);
                if (update.targetId() != null) {
                    writeUuid(output, update.targetId());
                }
                output.writeLong(update.deadlineEpochMillis());
                output.writeInt(update.durationSeconds());
                writeString(output, update.reason(), MAX_TEXT_BYTES);
                output.writeBoolean(update.sessionId() != null);
                if (update.sessionId() != null) writeUuid(output, update.sessionId());
                output.writeBoolean(update.targetPose() != null);
                if (update.targetPose() != null) writePlayer(output, update.targetPose());
            }
            case Message.ObserveReady ready -> {
                output.writeByte(16);
                writeUuid(output, ready.sessionId());
                writeUuid(output, ready.targetId());
            }
            case Message.Stop ignored -> output.writeByte(6);
            case Message.Heartbeat ignored -> output.writeByte(7);
            case Message.Error error -> {
                output.writeByte(8);
                writeString(output, error.reason(), MAX_TEXT_BYTES);
            }
            case Message.DurationRequest request -> {
                output.writeByte(9);
                writeUuid(output, request.targetId());
                // Untrusted totals reach the dedicated business reply instead of stopping an active session.
                output.writeInt(request.requestedDurationSeconds());
            }
            case Message.DurationReply reply -> {
                output.writeByte(10);
                writeUuid(output, reply.targetId());
                output.writeBoolean(reply.accepted());
                validateDurationReply(reply);
                output.writeByte(reply.state().ordinal());
                output.writeInt(reply.durationSeconds());
                output.writeLong(reply.deadlineEpochMillis());
                writeString(output, reply.reason(), MAX_TEXT_BYTES);
            }
            case Message.MapRequest request -> {
                output.writeByte(11);
                if (request.requestId() <= 0) throw new IOException("Map request ID must be positive");
                output.writeLong(request.requestId());
            }
            case Message.MapSource source -> {
                output.writeByte(12);
                validateMapSource(source);
                output.writeLong(source.requestId());
                writeString(output, source.worldKey(), MAX_WORLD_BYTES);
                output.writeBoolean(source.configured());
                writeString(output, source.baseUrl(), 1_024);
                writeString(output, source.dynmapWorld(), MAX_WORLD_BYTES);
                output.writeShort(source.exclusions().size());
                for (Message.RegionBounds region : source.exclusions()) {
                    output.writeDouble(region.minX());
                    output.writeDouble(region.minZ());
                    output.writeDouble(region.maxX());
                    output.writeDouble(region.maxZ());
                }
                writeString(output, source.detail(), 512);
            }
            case Message.WorldPolicy policy -> {
                output.writeByte(13);
                validateWorldPolicy(policy);
                writeString(output, policy.worldKey(), MAX_WORLD_BYTES);
                writeUuid(output, policy.worldId());
                output.writeBoolean(policy.automaticPatrol());
                output.writeShort(policy.exclusions().size());
                for (Message.RegionBounds region : policy.exclusions()) {
                    output.writeDouble(region.minX());
                    output.writeDouble(region.minZ());
                    output.writeDouble(region.maxX());
                    output.writeDouble(region.maxZ());
                }
            }
            case null -> throw new IOException("Message is required");
        }
        if (bytes.size() > Protocol.MAX_PACKET_BYTES) {
            throw new IOException("Packet exceeds " + Protocol.MAX_PACKET_BYTES + " bytes");
        }
        return bytes.toByteArray();
    }

    public static Message decode(byte[] bytes) throws IOException {
        if (bytes == null || bytes.length > Protocol.MAX_PACKET_BYTES) {
            throw new IOException("Missing or oversized packet");
        }
        DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes));
        if (input.readInt() != Protocol.MAGIC) {
            throw new IOException("Invalid packet magic");
        }
        if (input.readUnsignedShort() != Protocol.VERSION) {
            throw new IOException("Unsupported wire version");
        }
        Message message = switch (input.readUnsignedByte()) {
            case 0 -> new Message.Hello(input.readInt());
            case 1 -> new Message.HelloReply(input.readInt(), readBoolean(input),
                    readString(input, MAX_WORLD_BYTES), readString(input, MAX_TEXT_BYTES));
            case 2 -> {
                long requestId = input.readLong();
                if (requestId <= 0) throw new IOException("Snapshot request ID must be positive");
                yield new Message.SnapshotRequest(requestId);
            }
            case 3 -> {
                int count = input.readUnsignedShort();
                if (count > Protocol.MAX_PLAYERS) {
                    throw new IOException("Too many players in snapshot");
                }
                ArrayList<Message.PlayerView> players = new ArrayList<>(count);
                HashSet<UUID> ids = new HashSet<>();
                for (int index = 0; index < count; index++) {
                    Message.PlayerView player = readPlayer(input);
                    if (!ids.add(player.id())) {
                        throw new IOException("Duplicate player ID");
                    }
                    players.add(player);
                }
                int sceneCount = input.readUnsignedShort();
                if (sceneCount > Protocol.MAX_SCENES) {
                    throw new IOException("Too many scenes in snapshot");
                }
                ArrayList<Message.SceneView> scenes = new ArrayList<>(sceneCount);
                HashSet<SceneKey> sceneKeys = new HashSet<>();
                for (int index = 0; index < sceneCount; index++) {
                    Message.SceneView scene = readScene(input);
                    if (!sceneKeys.add(new SceneKey(scene.world(), scene.id()))) {
                        throw new IOException("Duplicate scene ID in the same world");
                    }
                    scenes.add(scene);
                }
                yield new Message.Snapshot(players, scenes);
            }
            case 4 -> {
                UUID targetId = readUuid(input);
                int seconds = input.readInt();
                if (seconds != 0) {
                    validateDuration(seconds);
                }
                yield new Message.ObserveRequest(targetId, seconds);
            }
            case 5 -> {
                int stateId = input.readUnsignedByte();
                Message.ObserveState[] states = Message.ObserveState.values();
                if (stateId >= states.length) {
                    throw new IOException("Unknown observation state");
                }
                Message.ObserveState state = states[stateId];
                UUID targetId = readBoolean(input) ? readUuid(input) : null;
                long deadline = input.readLong();
                int seconds = input.readInt();
                String reason = readString(input, MAX_TEXT_BYTES);
                UUID sessionId = readBoolean(input) ? readUuid(input) : null;
                Message.PlayerView pose = readBoolean(input) ? readPlayer(input) : null;
                Message.ObserveUpdate update = new Message.ObserveUpdate(state, targetId, deadline, seconds, reason, sessionId, pose);
                validateObserveUpdate(update);
                yield update;
            }
            case 6 -> new Message.Stop();
            case 7 -> new Message.Heartbeat();
            case 8 -> new Message.Error(readString(input, MAX_TEXT_BYTES));
            case 9 -> new Message.DurationRequest(readUuid(input), input.readInt());
            case 10 -> {
                UUID targetId = readUuid(input);
                boolean accepted = readBoolean(input);
                int stateId = input.readUnsignedByte();
                Message.ObserveState[] states = Message.ObserveState.values();
                if (stateId >= states.length) {
                    throw new IOException("Unknown observation state");
                }
                Message.DurationReply reply = new Message.DurationReply(targetId, accepted, states[stateId],
                        input.readInt(), input.readLong(), readString(input, MAX_TEXT_BYTES));
                validateDurationReply(reply);
                yield reply;
            }
            case 11 -> {
                long requestId = input.readLong();
                if (requestId <= 0) throw new IOException("Map request ID must be positive");
                yield new Message.MapRequest(requestId);
            }
            case 12 -> {
                long requestId = input.readLong();
                String worldKey = readString(input, MAX_WORLD_BYTES);
                boolean configured = readBoolean(input);
                String baseUrl = readString(input, 1_024);
                String dynmapWorld = readString(input, MAX_WORLD_BYTES);
                int count = input.readUnsignedShort();
                if (count > Protocol.MAX_MAP_EXCLUSIONS) {
                    throw new IOException("Too many map exclusion regions");
                }
                ArrayList<Message.RegionBounds> regions = new ArrayList<>(count);
                for (int index = 0; index < count; index++) {
                    regions.add(new Message.RegionBounds(input.readDouble(), input.readDouble(), input.readDouble(), input.readDouble()));
                }
                Message.MapSource source = new Message.MapSource(requestId, worldKey, configured, baseUrl, dynmapWorld, regions,
                        readString(input, 512));
                validateMapSource(source);
                yield source;
            }
            case 13 -> {
                String worldKey = readString(input, MAX_WORLD_BYTES);
                UUID worldId = readUuid(input);
                boolean automaticPatrol = readBoolean(input);
                int count = input.readUnsignedShort();
                if (count > Protocol.MAX_MAP_EXCLUSIONS) {
                    throw new IOException("Too many world exclusion regions");
                }
                ArrayList<Message.RegionBounds> regions = new ArrayList<>(count);
                for (int index = 0; index < count; index++) {
                    regions.add(new Message.RegionBounds(input.readDouble(), input.readDouble(), input.readDouble(), input.readDouble()));
                }
                Message.WorldPolicy policy = new Message.WorldPolicy(worldKey, worldId, automaticPatrol, regions);
                validateWorldPolicy(policy);
                yield policy;
            }
            case 14 -> {
                String world = readString(input, MAX_WORLD_BYTES);
                if (world.isBlank()) throw new IOException("Idle world is required");
                Message.IdlePose point = null;
                if (readBoolean(input)) {
                    try {
                        point = new Message.IdlePose(input.readDouble(), input.readDouble(), input.readDouble(), input.readFloat(), input.readFloat());
                    } catch (IllegalArgumentException exception) {
                        throw new IOException("Invalid idle pose", exception);
                    }
                }
                yield new Message.IdlePointSync(world, point);
            }
            case 15 -> {
                long requestId = input.readLong();
                int pageIndex = input.readUnsignedShort();
                int pageCount = input.readUnsignedShort();
                int playerCount = input.readUnsignedShort();
                if (playerCount > Protocol.MAX_SNAPSHOT_PAGE_PLAYERS) throw new IOException("Too many page players");
                ArrayList<Message.PlayerSummary> players = new ArrayList<>(playerCount);
                Message.PlayerAvailability[] availabilities = Message.PlayerAvailability.values();
                for (int index = 0; index < playerCount; index++) {
                    UUID id = readUuid(input);
                    String name = readString(input, MAX_NAME_BYTES);
                    String world = readString(input, MAX_WORLD_BYTES);
                    int availability = input.readUnsignedByte();
                    if (availability >= availabilities.length) throw new IOException("Unknown player availability");
                    players.add(new Message.PlayerSummary(id, name, world, availabilities[availability]));
                }
                int sceneCount = input.readUnsignedShort();
                if (sceneCount > Protocol.MAX_SNAPSHOT_PAGE_SCENES) throw new IOException("Too many page scenes");
                ArrayList<Message.SceneView> scenes = new ArrayList<>(sceneCount);
                for (int index = 0; index < sceneCount; index++) scenes.add(readScene(input));
                Message.SnapshotPage page = new Message.SnapshotPage(requestId, pageIndex, pageCount, players, scenes);
                validateSnapshotPage(page);
                yield page;
            }
            case 16 -> new Message.ObserveReady(readUuid(input), readUuid(input));
            default -> throw new IOException("Unknown message type");
        };
        if (input.available() != 0) {
            throw new IOException("Trailing packet data");
        }
        return message;
    }

    private static void writePlayer(DataOutputStream output, Message.PlayerView player) throws IOException {
        validatePosition(player);
        writeUuid(output, player.id());
        writeString(output, player.name(), MAX_NAME_BYTES);
        writeString(output, player.world(), MAX_WORLD_BYTES);
        output.writeDouble(player.x());
        output.writeDouble(player.y());
        output.writeDouble(player.z());
        output.writeFloat(player.yaw());
        output.writeFloat(player.pitch());
        output.writeLong(player.lastActivityEpochMillis());
    }

    private static Message.PlayerView readPlayer(DataInputStream input) throws IOException {
        Message.PlayerView player = new Message.PlayerView(readUuid(input),
                readString(input, MAX_NAME_BYTES), readString(input, MAX_WORLD_BYTES),
                input.readDouble(), input.readDouble(), input.readDouble(),
                input.readFloat(), input.readFloat(), input.readLong());
        validatePosition(player);
        return player;
    }

    private static void writeScene(DataOutputStream output, Message.SceneView scene) throws IOException {
        validateScene(scene);
        writeString(output, scene.id(), MAX_SCENE_ID_BYTES);
        writeString(output, scene.name(), MAX_SCENE_NAME_BYTES);
        output.writeByte(scene.kind().ordinal());
        writeString(output, scene.world(), MAX_WORLD_BYTES);
        output.writeDouble(scene.x());
        output.writeDouble(scene.y());
        output.writeDouble(scene.z());
        output.writeDouble(scene.radius());
    }

    private static Message.SceneView readScene(DataInputStream input) throws IOException {
        String id = readString(input, MAX_SCENE_ID_BYTES);
        String name = readString(input, MAX_SCENE_NAME_BYTES);
        int kindId = input.readUnsignedByte();
        Message.SceneKind[] kinds = Message.SceneKind.values();
        if (kindId >= kinds.length) throw new IOException("Unknown scene kind");
        Message.SceneView scene = new Message.SceneView(id, name, kinds[kindId], readString(input, MAX_WORLD_BYTES),
                input.readDouble(), input.readDouble(), input.readDouble(), input.readDouble());
        validateScene(scene);
        return scene;
    }

    private static void validateSnapshotPage(Message.SnapshotPage page) throws IOException {
        if (page.requestId() <= 0 || page.pageCount() < 1 || page.pageCount() > Protocol.MAX_SNAPSHOT_PAGES
                || page.pageIndex() < 0 || page.pageIndex() >= page.pageCount()
                || page.players().size() > Protocol.MAX_SNAPSHOT_PAGE_PLAYERS
                || page.scenes().size() > Protocol.MAX_SNAPSHOT_PAGE_SCENES) {
            throw new IOException("Invalid snapshot page identity or size");
        }
        HashSet<UUID> playerIds = new HashSet<>();
        for (Message.PlayerSummary player : page.players()) {
            if (player.id() == null || !playerIds.add(player.id()) || player.availability() == null) {
                throw new IOException("Missing or duplicate page player identity or availability");
            }
            validateIdentity(player.name());
            validateIdentity(player.world());
        }
        HashSet<SceneKey> sceneKeys = new HashSet<>();
        for (Message.SceneView scene : page.scenes()) {
            validateIdentity(scene.id());
            validateIdentity(scene.name());
            validateIdentity(scene.world());
            if (!sceneKeys.add(new SceneKey(scene.world(), scene.id()))) {
                throw new IOException("Duplicate page scene ID in the same world");
            }
            validateScene(scene);
        }
    }

    private static void validateIdentity(String value) throws IOException {
        if (value == null || value.isBlank() || value.codePoints().anyMatch(Character::isISOControl)) {
            throw new IOException("Missing or invalid identity text");
        }
    }

    private static void validateObserveUpdate(Message.ObserveUpdate update) throws IOException {
        if (update.state() == null) throw new IOException("Observation state is required");
        if (update.state() == Message.ObserveState.ENDED) {
            if (update.durationSeconds() != 0 || update.deadlineEpochMillis() != 0) {
                throw new IOException("Ended observation must have zero duration and deadline");
            }
        } else {
            if (update.targetId() == null || update.sessionId() == null || update.deadlineEpochMillis() <= 0) {
                throw new IOException("Active observation requires target, session and positive deadline");
            }
            validateDuration(update.durationSeconds());
        }
        if (update.state() == Message.ObserveState.APPROACHING) {
            Message.PlayerView pose = update.targetPose();
            if (pose == null || !update.targetId().equals(pose.id())) {
                throw new IOException("Approaching observation requires the matching target pose");
            }
            validatePosition(pose);
            validateIdentity(pose.name());
            validateIdentity(pose.world());
            if (Math.abs(pose.x()) > 30_000_000 || Math.abs(pose.y()) > 30_000_000
                    || Math.abs(pose.z()) > 30_000_000 || pose.pitch() < -90 || pose.pitch() > 90
                    || pose.lastActivityEpochMillis() < 0) {
                throw new IOException("Invalid approach target coordinates, pitch or activity time");
            }
        } else if (update.targetPose() != null) {
            throw new IOException("Only approaching observations may carry a target pose");
        }
    }

    private static void writeUuid(DataOutputStream output, UUID id) throws IOException {
        if (id == null) {
            throw new IOException("UUID is required");
        }
        output.writeLong(id.getMostSignificantBits());
        output.writeLong(id.getLeastSignificantBits());
    }

    private static UUID readUuid(DataInputStream input) throws IOException {
        return new UUID(input.readLong(), input.readLong());
    }

    private static void writeString(DataOutputStream output, String value, int maxBytes) throws IOException {
        if (value == null || value.length() > maxBytes) {
            throw new IOException("Missing or oversized string");
        }
        ByteBuffer encoded = StandardCharsets.UTF_8.newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .encode(java.nio.CharBuffer.wrap(value));
        if (encoded.remaining() > maxBytes) {
            throw new IOException("String exceeds " + maxBytes + " UTF-8 bytes");
        }
        output.writeShort(encoded.remaining());
        output.write(encoded.array(), encoded.position(), encoded.remaining());
    }

    private static String readString(DataInputStream input, int maxBytes) throws IOException {
        int length = input.readUnsignedShort();
        if (length > maxBytes) {
            throw new IOException("String exceeds " + maxBytes + " UTF-8 bytes");
        }
        byte[] bytes = new byte[length];
        input.readFully(bytes);
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException exception) {
            throw new IOException("Invalid UTF-8 string", exception);
        }
    }

    private static boolean readBoolean(DataInputStream input) throws IOException {
        return switch (input.readUnsignedByte()) {
            case 0 -> false;
            case 1 -> true;
            default -> throw new IOException("Invalid boolean value");
        };
    }

    private static void validatePosition(Message.PlayerView player) throws IOException {
        if (!Double.isFinite(player.x()) || !Double.isFinite(player.y()) || !Double.isFinite(player.z())
                || !Float.isFinite(player.yaw()) || !Float.isFinite(player.pitch())) {
            throw new IOException("Player coordinates and rotation must be finite");
        }
    }

    private static void validateScene(Message.SceneView scene) throws IOException {
        if (scene.kind() == null) {
            throw new IOException("Scene kind is required");
        }
        if (!Double.isFinite(scene.x()) || !Double.isFinite(scene.y()) || !Double.isFinite(scene.z())
                || !Double.isFinite(scene.radius()) || scene.radius() < 6 || scene.radius() > 48) {
            throw new IOException("Scene coordinates must be finite and radius must be between 6 and 48");
        }
    }

    private static void validateDuration(int seconds) throws IOException {
        if (seconds < Protocol.MIN_FOLLOW_SECONDS || seconds > Protocol.MAX_FOLLOW_SECONDS) {
            throw new IOException("Follow duration must be between 180 and 480 seconds");
        }
    }

    private static void validateMapSource(Message.MapSource source) throws IOException {
        if (source.requestId() <= 0 || source.worldKey() == null || source.worldKey().isBlank()
                || source.baseUrl() == null || source.dynmapWorld() == null
                || source.exclusions().size() > Protocol.MAX_MAP_EXCLUSIONS) {
            throw new IOException("Invalid map source metadata");
        }
        if (source.configured()) {
            try {
                java.net.URI uri = java.net.URI.create(source.baseUrl());
                if (!("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                        || uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                        || source.dynmapWorld().isBlank() || source.dynmapWorld().codePoints().anyMatch(Character::isISOControl)) {
                    throw new IllegalArgumentException("Invalid source URL or world");
                }
            } catch (IllegalArgumentException exception) {
                throw new IOException("Invalid map source URL or world", exception);
            }
        } else if (!source.baseUrl().isEmpty() || !source.dynmapWorld().isEmpty() || !source.exclusions().isEmpty()) {
            throw new IOException("Unconfigured map source must not carry a URL, world or regions");
        }
        validateRegions(source.exclusions());
    }

    private static void validateWorldPolicy(Message.WorldPolicy policy) throws IOException {
        if (policy.worldKey() == null || policy.worldKey().isBlank()
                || policy.worldKey().codePoints().anyMatch(Character::isISOControl) || policy.worldId() == null
                || policy.exclusions().size() > Protocol.MAX_MAP_EXCLUSIONS) {
            throw new IOException("Invalid world policy");
        }
        validateRegions(policy.exclusions());
    }

    private static void validateRegions(List<Message.RegionBounds> regions) throws IOException {
        for (Message.RegionBounds region : regions) {
            if (!Double.isFinite(region.minX()) || !Double.isFinite(region.minZ())
                    || !Double.isFinite(region.maxX()) || !Double.isFinite(region.maxZ())
                    || region.minX() > region.maxX() || region.minZ() > region.maxZ()) {
                throw new IOException("Invalid map exclusion region");
            }
        }
    }

    private static void validateDurationReply(Message.DurationReply reply) throws IOException {
        if (reply.state() == null) {
            throw new IOException("Duration reply requires an observation state");
        }
        if (!reply.accepted() && reply.state() == Message.ObserveState.ENDED) {
            if (reply.durationSeconds() != 0 || reply.deadlineEpochMillis() != 0) {
                throw new IOException("Rejected ended duration reply must have zero duration and deadline");
            }
        } else {
            validateDuration(reply.durationSeconds());
        }
    }
}
