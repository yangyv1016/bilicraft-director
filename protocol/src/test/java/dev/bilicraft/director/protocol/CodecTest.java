package dev.bilicraft.director.protocol;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CodecTest {
    private static final UUID TARGET = UUID.fromString("f3baec1d-b51a-4b5a-8328-870ef4badc91");
    private static final Message.PlayerView PLAYER = new Message.PlayerView(TARGET, "Alice", "world",
            123.5, 72, -42.25, 125, -15, 1_790_000_000_000L);
    private static final Message.SceneView SCENE = new Message.SceneView("station", "车站",
            Message.SceneKind.VIEWPOINT, "world", 12.5, 80, -24.5, 12);

    @Test
    void allMessagesRoundTripIncludingOptionalEndedTargetAndUnicode() throws IOException {
        List<Message> messages = List.of(
                new Message.Hello(Protocol.VERSION),
                new Message.HelloReply(Protocol.VERSION, true, "minecraft:overworld", "已允许连接"),
                new Message.HelloReply(Protocol.VERSION, false, "minecraft:overworld", "权限不足"),
                new Message.SnapshotRequest(1),
                new Message.Snapshot(List.of(), List.of()),
                new Message.Snapshot(List.of(PLAYER), List.of(SCENE)),
                new Message.Snapshot(List.of(), List.of(new Message.SceneView("city", "城区",
                        Message.SceneKind.AREA, "world", 30, 90, 60, 48))),
                new Message.ObserveRequest(TARGET),
                new Message.ObserveRequest(TARGET, 480),
                new Message.ObserveUpdate(Message.ObserveState.NOTICE, TARGET, 15_000, 180, "预告", TARGET, null),
                new Message.ObserveUpdate(Message.ObserveState.FOLLOWING, TARGET, 180_000, 180, "", TARGET, null),
                new Message.ObserveUpdate(Message.ObserveState.ENDED, TARGET, 0, 0, "已拒绝"),
                new Message.ObserveUpdate(Message.ObserveState.ENDED, null, 0, 0, "停止"),
                new Message.Stop(),
                new Message.Heartbeat(),
                new Message.Error("无效请求"),
                new Message.DurationRequest(TARGET, 190),
                new Message.DurationReply(TARGET, true, Message.ObserveState.NOTICE, 190, 15_000, "已调整"),
                new Message.DurationReply(TARGET, false, Message.ObserveState.FOLLOWING, 480, 500_000, "已到上限"),
                new Message.DurationReply(TARGET, false, Message.ObserveState.ENDED, 0, 0, "无对应会话"),
                new Message.DurationReply(TARGET, true, Message.ObserveState.ENDED, 180, 195_000, "缩短后到期"));
        for (Message message : messages) {
            assertEquals(message, Codec.decode(Codec.encode(message)));
        }
    }

    @Test
    void unknownApplicationVersionCanReachHandshakeValidation() throws IOException {
        Message.Hello hello = new Message.Hello(99);
        assertEquals(hello, Codec.decode(Codec.encode(hello)));
        Message.HelloReply reply = new Message.HelloReply(99, false, "minecraft:overworld", "Unsupported version");
        assertEquals(reply, Codec.decode(Codec.encode(reply)));
    }

    @Test
    void rejectsEveryTruncatedPrefix() throws IOException {
        byte[] valid = Codec.encode(new Message.Snapshot(List.of(PLAYER), List.of(SCENE)));
        for (int length = 0; length < valid.length; length++) {
            byte[] truncated = Arrays.copyOf(valid, length);
            assertThrows(IOException.class, () -> Codec.decode(truncated), "prefix length " + length);
        }
    }

    @Test
    void rejectsUnknownWireVersionMagicAndMessageType() throws IOException {
        byte[] invalidMagic = Codec.encode(new Message.Heartbeat());
        invalidMagic[0] = 0;
        assertThrows(IOException.class, () -> Codec.decode(invalidMagic));

        byte[] invalidVersion = Codec.encode(new Message.Heartbeat());
        ByteBuffer.wrap(invalidVersion).putShort(4, (short) (Protocol.VERSION + 1));
        assertThrows(IOException.class, () -> Codec.decode(invalidVersion));

        for (int oldVersion : List.of(1, 2, 3, 4, 5, 6)) {
            byte[] previousVersion = Codec.encode(new Message.Heartbeat());
            ByteBuffer.wrap(previousVersion).putShort(4, (short) oldVersion);
            assertThrows(IOException.class, () -> Codec.decode(previousVersion));
        }

        byte[] invalidType = Codec.encode(new Message.Heartbeat());
        invalidType[6] = (byte) 255;
        assertThrows(IOException.class, () -> Codec.decode(invalidType));
    }

    @Test
    void rejectsTrailingDataInsteadOfAcceptingExtraFieldsOrMessages() throws IOException {
        byte[] valid = Codec.encode(new Message.Stop());
        byte[] trailing = Arrays.copyOf(valid, valid.length + 1);
        assertThrows(IOException.class, () -> Codec.decode(trailing));

        byte[] combined = new byte[valid.length * 2];
        System.arraycopy(valid, 0, combined, 0, valid.length);
        System.arraycopy(valid, 0, combined, valid.length, valid.length);
        assertThrows(IOException.class, () -> Codec.decode(combined));
    }

    @Test
    void rejectsOversizedInputAndEncodedSnapshots() {
        assertThrows(IOException.class, () -> Codec.decode(new byte[Protocol.MAX_PACKET_BYTES + 1]));

        ArrayList<Message.PlayerView> players = new ArrayList<>();
        for (int index = 0; index < Protocol.MAX_PLAYERS; index++) {
            players.add(new Message.PlayerView(new UUID(0, index), "n".repeat(64), "w".repeat(256),
                    0, 64, 0, 0, 0, 0));
        }
        assertThrows(IOException.class, () -> Codec.encode(new Message.Snapshot(players, List.of())));
    }

    @Test
    void rejectsCollectionCountBeforeReadingOrAllocatingEntries() throws IOException {
        byte[] invalidCount = Codec.encode(new Message.Snapshot(List.of(), List.of()));
        ByteBuffer.wrap(invalidCount).putShort(7, (short) (Protocol.MAX_PLAYERS + 1));
        assertThrows(IOException.class, () -> Codec.decode(invalidCount));

        ArrayList<Message.PlayerView> players = new ArrayList<>();
        for (int index = 0; index <= Protocol.MAX_PLAYERS; index++) {
            players.add(new Message.PlayerView(new UUID(0, index), "A", "world",
                    0, 0, 0, 0, 0, 0));
        }
        assertThrows(IOException.class, () -> Codec.encode(new Message.Snapshot(players, List.of())));
    }

    @Test
    void rejectsDuplicatePlayersInBothDirections() throws IOException {
        assertThrows(IOException.class, () -> Codec.encode(new Message.Snapshot(List.of(PLAYER, PLAYER), List.of())));

        Message.PlayerView other = new Message.PlayerView(new UUID(0, 2), PLAYER.name(), PLAYER.world(),
                PLAYER.x(), PLAYER.y(), PLAYER.z(), PLAYER.yaw(), PLAYER.pitch(), PLAYER.lastActivityEpochMillis());
        int secondPlayerOffset = Codec.encode(new Message.Snapshot(List.of(PLAYER), List.of())).length - 2;
        byte[] duplicateId = Codec.encode(new Message.Snapshot(List.of(PLAYER, other), List.of()));
        System.arraycopy(duplicateId, 9, duplicateId, secondPlayerOffset, 16);
        assertThrows(IOException.class, () -> Codec.decode(duplicateId));
    }

    @Test
    void rejectsNonFiniteCoordinatesAndAnglesDuringEncoding() {
        List<Message.PlayerView> invalidPlayers = List.of(
                new Message.PlayerView(TARGET, "A", "world", Double.NaN, 0, 0, 0, 0, 0),
                new Message.PlayerView(TARGET, "A", "world", 0, Double.POSITIVE_INFINITY, 0, 0, 0, 0),
                new Message.PlayerView(TARGET, "A", "world", 0, 0, Double.NEGATIVE_INFINITY, 0, 0, 0),
                new Message.PlayerView(TARGET, "A", "world", 0, 0, 0, Float.NaN, 0, 0),
                new Message.PlayerView(TARGET, "A", "world", 0, 0, 0, 0, Float.POSITIVE_INFINITY, 0));
        for (Message.PlayerView player : invalidPlayers) {
            assertThrows(IOException.class, () -> Codec.encode(new Message.Snapshot(List.of(player), List.of())));
        }
    }

    @Test
    void rejectsNonFiniteCoordinatesAndAnglesDuringDecoding() throws IOException {
        int positionOffset = 9 + 16 + 2 + PLAYER.name().length() + 2 + PLAYER.world().length();
        byte[] invalidPosition = Codec.encode(new Message.Snapshot(List.of(PLAYER), List.of()));
        ByteBuffer.wrap(invalidPosition).putDouble(positionOffset, Double.NaN);
        assertThrows(IOException.class, () -> Codec.decode(invalidPosition));

        byte[] invalidRotation = Codec.encode(new Message.Snapshot(List.of(PLAYER), List.of()));
        ByteBuffer.wrap(invalidRotation).putFloat(positionOffset + 24, Float.NEGATIVE_INFINITY);
        assertThrows(IOException.class, () -> Codec.decode(invalidRotation));
    }

    @Test
    void activeStatesRequireTargetDuringEncodingAndDecoding() throws IOException {
        for (Message.ObserveState state : List.of(Message.ObserveState.NOTICE, Message.ObserveState.FOLLOWING)) {
            assertThrows(IOException.class, () -> Codec.encode(new Message.ObserveUpdate(state, null, 10, 180, "")));
            byte[] missingTarget = Codec.encode(new Message.ObserveUpdate(Message.ObserveState.ENDED, null, 0, 0, ""));
            missingTarget[7] = (byte) state.ordinal();
            assertThrows(IOException.class, () -> Codec.decode(missingTarget));
        }
    }

    @Test
    void rejectsInvalidObservationEnumAndBoolean() throws IOException {
        byte[] invalidState = Codec.encode(new Message.ObserveUpdate(Message.ObserveState.ENDED, null, 0, 0, ""));
        invalidState[7] = (byte) 255;
        assertThrows(IOException.class, () -> Codec.decode(invalidState));

        byte[] invalidPresence = Codec.encode(new Message.ObserveUpdate(Message.ObserveState.ENDED, null, 0, 0, ""));
        invalidPresence[8] = 2;
        assertThrows(IOException.class, () -> Codec.decode(invalidPresence));

        byte[] invalidAllowed = Codec.encode(new Message.HelloReply(Protocol.VERSION, true, "minecraft:overworld", ""));
        invalidAllowed[11] = 2;
        assertThrows(IOException.class, () -> Codec.decode(invalidAllowed));
    }

    @Test
    void rejectsOversizedStringsByUtf8BytesAndDeclaredLength() throws IOException {
        assertThrows(IOException.class, () -> Codec.encode(new Message.Error("r".repeat(1_025))));
        assertThrows(IOException.class, () -> Codec.encode(new Message.Snapshot(List.of(
                new Message.PlayerView(TARGET, "界".repeat(22), "world", 0, 0, 0, 0, 0, 0)), List.of())));
        assertThrows(IOException.class, () -> Codec.encode(new Message.Snapshot(List.of(
                new Message.PlayerView(TARGET, "A", "w".repeat(257), 0, 0, 0, 0, 0, 0)), List.of())));

        byte[] invalidLength = Codec.encode(new Message.Error(""));
        ByteBuffer.wrap(invalidLength).putShort(7, (short) 1_025);
        assertThrows(IOException.class, () -> Codec.decode(invalidLength));
    }

    @Test
    void rejectsInvalidUtf8AndUnpairedSurrogates() throws IOException {
        byte[] invalidUtf8 = Codec.encode(new Message.Error("ok"));
        invalidUtf8[9] = (byte) 0xc3;
        invalidUtf8[10] = 0x28;
        assertThrows(IOException.class, () -> Codec.decode(invalidUtf8));
        assertThrows(IOException.class, () -> Codec.encode(new Message.Error("\ud800")));
    }

    @Test
    void helloReplyCarriesABoundedWorldKeyBeforeItsDetail() throws IOException {
        Message.HelloReply reply = new Message.HelloReply(Protocol.VERSION, true, "界".repeat(85) + "a", "已授权");
        assertEquals(reply, Codec.decode(Codec.encode(reply)));
        assertThrows(IOException.class, () -> Codec.encode(new Message.HelloReply(Protocol.VERSION, true,
                "界".repeat(86), "")));
        byte[] invalidLength = Codec.encode(reply);
        ByteBuffer.wrap(invalidLength).putShort(12, (short) 257);
        assertThrows(IOException.class, () -> Codec.decode(invalidLength));
    }

    @Test
    void rejectsMissingRequiredMessageFields() {
        assertThrows(IOException.class, () -> Codec.encode(null));
        assertThrows(IOException.class, () -> Codec.decode(null));
        assertThrows(IOException.class, () -> Codec.encode(new Message.ObserveRequest(null)));
        assertThrows(IOException.class, () -> Codec.encode(new Message.Error(null)));
        assertThrows(IOException.class, () -> Codec.encode(new Message.ObserveUpdate(null, TARGET, 0, 0, "")));
    }

    @Test
    void sceneByteAndRadiusBoundariesRoundTrip() throws IOException {
        String id = "界".repeat(21) + "a";
        String name = "界".repeat(85) + "a";
        Message.Snapshot snapshot = new Message.Snapshot(List.of(PLAYER), List.of(
                new Message.SceneView(id, name, Message.SceneKind.VIEWPOINT, name, 0, 80, 0, 6),
                new Message.SceneView("area", "区域", Message.SceneKind.AREA, "world", -12.5, 90, 4.5, 48)));
        assertEquals(snapshot, Codec.decode(Codec.encode(snapshot)));
    }

    @Test
    void permitsMaximumCountsWhenTheCombinedPacketFits() throws IOException {
        ArrayList<Message.PlayerView> players = new ArrayList<>();
        for (int index = 0; index < Protocol.MAX_PLAYERS; index++) {
            players.add(new Message.PlayerView(new UUID(0, index), "P", "world", 0, 80, 0, 0, 0, 0));
        }
        ArrayList<Message.SceneView> scenes = new ArrayList<>();
        for (int index = 0; index < Protocol.MAX_SCENES; index++) {
            scenes.add(new Message.SceneView("s" + index, "S", Message.SceneKind.AREA, "world", 0, 80, 0, 12));
        }
        Message.Snapshot snapshot = new Message.Snapshot(players, scenes);
        assertEquals(snapshot, Codec.decode(Codec.encode(snapshot)));
    }

    @Test
    void rejectsTooManyScenesBeforeReadingOrAllocatingEntries() throws IOException {
        ArrayList<Message.SceneView> scenes = new ArrayList<>();
        for (int index = 0; index <= Protocol.MAX_SCENES; index++) {
            scenes.add(new Message.SceneView("s" + index, "S", Message.SceneKind.AREA, "world", 0, 80, 0, 12));
        }
        assertThrows(IOException.class, () -> Codec.encode(new Message.Snapshot(List.of(), scenes)));

        byte[] invalidCount = Codec.encode(new Message.Snapshot(List.of(), List.of()));
        ByteBuffer.wrap(invalidCount).putShort(9, (short) (Protocol.MAX_SCENES + 1));
        assertThrows(IOException.class, () -> Codec.decode(invalidCount));
    }

    @Test
    void rejectsDuplicateSceneIdsInBothDirections() throws IOException {
        assertThrows(IOException.class, () -> Codec.encode(new Message.Snapshot(List.of(), List.of(SCENE, SCENE))));

        Message.SceneView other = new Message.SceneView("statio2", SCENE.name(), SCENE.kind(), SCENE.world(),
                SCENE.x(), SCENE.y(), SCENE.z(), SCENE.radius());
        int secondSceneOffset = Codec.encode(new Message.Snapshot(List.of(), List.of(SCENE))).length;
        byte[] duplicateId = Codec.encode(new Message.Snapshot(List.of(), List.of(SCENE, other)));
        System.arraycopy(duplicateId, 11, duplicateId, secondSceneOffset, 2 + SCENE.id().length());
        assertThrows(IOException.class, () -> Codec.decode(duplicateId));
    }

    @Test
    void sceneIdsAreScopedToTheirWorldAndTuplePartsRemainDistinct() throws IOException {
        Message.Snapshot snapshot = new Message.Snapshot(List.of(), List.of(
                SCENE,
                new Message.SceneView(SCENE.id(), SCENE.name(), SCENE.kind(), "artist",
                        SCENE.x(), SCENE.y(), SCENE.z(), SCENE.radius()),
                new Message.SceneView("c", "One", Message.SceneKind.AREA, "a:b", 0, 80, 0, 12),
                new Message.SceneView("b:c", "Two", Message.SceneKind.AREA, "a", 0, 80, 0, 12)));
        assertEquals(snapshot, Codec.decode(Codec.encode(snapshot)));
    }

    @Test
    void rejectsNullAndUnknownSceneKind() throws IOException {
        assertThrows(IOException.class, () -> Codec.encode(new Message.Snapshot(List.of(), List.of(
                new Message.SceneView("scene", "S", null, "world", 0, 80, 0, 12)))));

        byte[] invalidKind = Codec.encode(new Message.Snapshot(List.of(), List.of(SCENE)));
        int kindOffset = 11 + 2 + SCENE.id().getBytes(StandardCharsets.UTF_8).length
                + 2 + SCENE.name().getBytes(StandardCharsets.UTF_8).length;
        invalidKind[kindOffset] = (byte) 255;
        assertThrows(IOException.class, () -> Codec.decode(invalidKind));
    }

    @Test
    void rejectsNonFiniteSceneCoordinatesAndInvalidRadiusDuringEncoding() {
        List<Message.SceneView> scenes = List.of(
                new Message.SceneView("s", "S", Message.SceneKind.AREA, "world", Double.NaN, 0, 0, 12),
                new Message.SceneView("s", "S", Message.SceneKind.AREA, "world", 0, Double.POSITIVE_INFINITY, 0, 12),
                new Message.SceneView("s", "S", Message.SceneKind.AREA, "world", 0, 0, Double.NEGATIVE_INFINITY, 12),
                new Message.SceneView("s", "S", Message.SceneKind.AREA, "world", 0, 0, 0, 5.999),
                new Message.SceneView("s", "S", Message.SceneKind.AREA, "world", 0, 0, 0, 48.001),
                new Message.SceneView("s", "S", Message.SceneKind.AREA, "world", 0, 0, 0, Double.NaN),
                new Message.SceneView("s", "S", Message.SceneKind.AREA, "world", 0, 0, 0, Double.POSITIVE_INFINITY));
        for (Message.SceneView scene : scenes) {
            assertThrows(IOException.class, () -> Codec.encode(new Message.Snapshot(List.of(), List.of(scene))));
        }
    }

    @Test
    void rejectsNonFiniteSceneCoordinatesAndInvalidRadiusDuringDecoding() throws IOException {
        int positionOffset = 11 + 2 + SCENE.id().getBytes(StandardCharsets.UTF_8).length
                + 2 + SCENE.name().getBytes(StandardCharsets.UTF_8).length + 1
                + 2 + SCENE.world().getBytes(StandardCharsets.UTF_8).length;
        for (int fieldOffset : List.of(0, 8, 16, 24)) {
            byte[] invalidNumber = Codec.encode(new Message.Snapshot(List.of(), List.of(SCENE)));
            ByteBuffer.wrap(invalidNumber).putDouble(positionOffset + fieldOffset, Double.NaN);
            assertThrows(IOException.class, () -> Codec.decode(invalidNumber));
        }
        for (double radius : List.of(5.999, 48.001, Double.POSITIVE_INFINITY)) {
            byte[] invalidRadius = Codec.encode(new Message.Snapshot(List.of(), List.of(SCENE)));
            ByteBuffer.wrap(invalidRadius).putDouble(positionOffset + 24, radius);
            assertThrows(IOException.class, () -> Codec.decode(invalidRadius));
        }
    }

    @Test
    void rejectsOversizedSceneStringsByUtf8BytesAndDeclaredLength() throws IOException {
        List<Message.SceneView> scenes = List.of(
                new Message.SceneView("i".repeat(65), "S", Message.SceneKind.AREA, "world", 0, 80, 0, 12),
                new Message.SceneView("界".repeat(22), "S", Message.SceneKind.AREA, "world", 0, 80, 0, 12),
                new Message.SceneView("s", "n".repeat(257), Message.SceneKind.AREA, "world", 0, 80, 0, 12),
                new Message.SceneView("s", "界".repeat(86), Message.SceneKind.AREA, "world", 0, 80, 0, 12),
                new Message.SceneView("s", "S", Message.SceneKind.AREA, "w".repeat(257), 0, 80, 0, 12));
        for (Message.SceneView scene : scenes) {
            assertThrows(IOException.class, () -> Codec.encode(new Message.Snapshot(List.of(), List.of(scene))));
        }
        int nameOffset = 11 + 2 + SCENE.id().getBytes(StandardCharsets.UTF_8).length;
        int worldOffset = nameOffset + 2 + SCENE.name().getBytes(StandardCharsets.UTF_8).length + 1;
        for (int offset : List.of(11, nameOffset, worldOffset)) {
            byte[] invalidLength = Codec.encode(new Message.Snapshot(List.of(), List.of(SCENE)));
            ByteBuffer.wrap(invalidLength).putShort(offset, (short) (offset == 11 ? 65 : 257));
            assertThrows(IOException.class, () -> Codec.decode(invalidLength));
        }
    }

    @Test
    void sceneCountDoesNotOverrideTheCombinedPacketSizeLimit() {
        ArrayList<Message.SceneView> scenes = new ArrayList<>();
        for (int index = 0; index < Protocol.MAX_SCENES; index++) {
            String id = "s".repeat(64 - Integer.toString(index).length()) + index;
            scenes.add(new Message.SceneView(id, "n".repeat(256), Message.SceneKind.AREA, "w".repeat(256),
                    0, 80, 0, 12));
        }
        assertThrows(IOException.class, () -> Codec.encode(new Message.Snapshot(List.of(), scenes)));
    }

    @Test
    void snapshotOwnsBothListsSoLaterCallerChangesCannotAlterTheMessage() {
        ArrayList<Message.PlayerView> players = new ArrayList<>(List.of(PLAYER));
        ArrayList<Message.SceneView> scenes = new ArrayList<>(List.of(SCENE));
        Message.Snapshot snapshot = new Message.Snapshot(players, scenes);
        players.clear();
        scenes.clear();
        assertEquals(List.of(PLAYER), snapshot.players());
        assertEquals(List.of(SCENE), snapshot.scenes());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.players().clear());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.scenes().clear());
    }

    @Test
    void observeRequestAcceptsRandomOrBoundedExplicitSecondsAndRejectsInvalidWireTotals() throws IOException {
        assertEquals(0, new Message.ObserveRequest(TARGET).requestedDurationSeconds());
        for (int seconds : List.of(0, 180, 480)) {
            Message request = new Message.ObserveRequest(TARGET, seconds);
            assertEquals(request, Codec.decode(Codec.encode(request)));
        }
        for (int invalid : List.of(-1, 179, 481, Integer.MAX_VALUE)) {
            assertThrows(IOException.class, () -> Codec.encode(new Message.ObserveRequest(TARGET, invalid)));
            byte[] bytes = Codec.encode(new Message.ObserveRequest(TARGET, 180));
            ByteBuffer.wrap(bytes).putInt(23, invalid);
            assertThrows(IOException.class, () -> Codec.decode(bytes));
        }
    }

    @Test
    void durationRequestsPreserveUntrustedTotalsForDedicatedBusinessRejection() throws IOException {
        for (int seconds : List.of(Integer.MIN_VALUE, -10, 0, 179, 190, 481, Integer.MAX_VALUE)) {
            Message request = new Message.DurationRequest(TARGET, seconds);
            assertEquals(request, Codec.decode(Codec.encode(request)));
        }
        assertThrows(IOException.class, () -> Codec.encode(new Message.DurationRequest(null, 190)));
    }

    @Test
    void observationUpdatesRequireConfirmedActiveDurationAndZeroEndedDuration() throws IOException {
        for (int invalid : List.of(0, 179, 481)) {
            assertThrows(IOException.class, () -> Codec.encode(new Message.ObserveUpdate(
                    Message.ObserveState.FOLLOWING, TARGET, 500_000, invalid, "", TARGET, null)));
            byte[] bytes = Codec.encode(new Message.ObserveUpdate(Message.ObserveState.FOLLOWING, TARGET, 500_000, 480, "", TARGET, null));
            ByteBuffer.wrap(bytes).putInt(33, invalid);
            assertThrows(IOException.class, () -> Codec.decode(bytes));
        }
        assertThrows(IOException.class, () -> Codec.encode(new Message.ObserveUpdate(
                Message.ObserveState.ENDED, TARGET, 0, 180, "")));
    }

    @Test
    void durationReplyRejectsImpossibleStatusTotalsEnumsAndBooleans() throws IOException {
        for (Message.DurationReply invalid : List.of(
                new Message.DurationReply(null, true, Message.ObserveState.NOTICE, 180, 15_000, ""),
                new Message.DurationReply(TARGET, true, null, 180, 15_000, ""),
                new Message.DurationReply(TARGET, true, Message.ObserveState.ENDED, 0, 0, ""),
                new Message.DurationReply(TARGET, false, Message.ObserveState.NOTICE, 0, 15_000, ""),
                new Message.DurationReply(TARGET, false, Message.ObserveState.ENDED, 0, 42, ""),
                new Message.DurationReply(TARGET, false, Message.ObserveState.ENDED, 180, 0, ""),
                new Message.DurationReply(TARGET, true, Message.ObserveState.FOLLOWING, 179, 500_000, ""),
                new Message.DurationReply(TARGET, false, Message.ObserveState.FOLLOWING, 481, 500_000, ""))) {
            assertThrows(IOException.class, () -> Codec.encode(invalid));
        }
        byte[] invalidEnum = Codec.encode(new Message.DurationReply(TARGET, true, Message.ObserveState.NOTICE, 180, 15_000, ""));
        invalidEnum[24] = (byte) 255;
        assertThrows(IOException.class, () -> Codec.decode(invalidEnum));
        byte[] invalidBoolean = Codec.encode(new Message.DurationReply(TARGET, true, Message.ObserveState.NOTICE, 180, 15_000, ""));
        invalidBoolean[23] = 2;
        assertThrows(IOException.class, () -> Codec.decode(invalidBoolean));
        byte[] invalidTotal = Codec.encode(new Message.DurationReply(TARGET, true, Message.ObserveState.NOTICE, 180, 15_000, ""));
        ByteBuffer.wrap(invalidTotal).putInt(25, 179);
        assertThrows(IOException.class, () -> Codec.decode(invalidTotal));
    }

    @Test
    void v3DurationMessagesRejectEveryTruncatedPrefixAndTrailingBytes() throws IOException {
        for (Message message : List.of(new Message.ObserveRequest(TARGET, 180),
                new Message.ObserveUpdate(Message.ObserveState.NOTICE, TARGET, 15_000, 180, "预告", TARGET, null),
                new Message.DurationRequest(TARGET, 190),
                new Message.DurationReply(TARGET, true, Message.ObserveState.FOLLOWING, 190, 205_000, "已调整"))) {
            byte[] valid = Codec.encode(message);
            for (int length = 0; length < valid.length; length++) {
                byte[] truncated = Arrays.copyOf(valid, length);
                assertThrows(IOException.class, () -> Codec.decode(truncated));
            }
            assertThrows(IOException.class, () -> Codec.decode(Arrays.copyOf(valid, valid.length + 1)));
        }
    }
}
