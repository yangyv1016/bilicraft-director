package dev.bilicraft.director.protocol;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ObservationSessionCodecTest {
    private static final UUID TARGET = new UUID(1, 2);
    private static final UUID SESSION = new UUID(3, 4);
    private static final Message.PlayerView POSE = new Message.PlayerView(TARGET, "Alice", "world",
            20_000, 100, -50_000, 90, 0, 1_790_000_000_000L);

    @Test
    void consentApproachAndFollowCarryTheSameSessionButOnlyApproachCarriesCoordinates() throws IOException {
        for (Message message : List.of(
                new Message.ObserveUpdate(Message.ObserveState.NOTICE, TARGET, 15_000, 180, "预告", SESSION, null),
                new Message.ObserveUpdate(Message.ObserveState.APPROACHING, TARGET, 45_000, 180, "接近", SESSION, POSE),
                new Message.ObserveReady(SESSION, TARGET),
                new Message.ObserveUpdate(Message.ObserveState.FOLLOWING, TARGET, 180_000, 180, "跟随", SESSION, null),
                new Message.ObserveUpdate(Message.ObserveState.ENDED, TARGET, 0, 0, "结束", SESSION, null),
                new Message.ObserveUpdate(Message.ObserveState.ENDED, null, 0, 0, "已停止"))) {
            assertEquals(message, Codec.decode(Codec.encode(message)));
        }
    }

    @Test
    void activeUpdatesCannotUseLegacyConstructorOrOmitIdentityDeadlineOrDuration() {
        for (Message.ObserveState state : List.of(Message.ObserveState.NOTICE, Message.ObserveState.APPROACHING,
                Message.ObserveState.FOLLOWING)) {
            assertThrows(IOException.class, () -> Codec.encode(new Message.ObserveUpdate(state, TARGET, 10, 180, "")));
            Message.PlayerView pose = state == Message.ObserveState.APPROACHING ? POSE : null;
            assertThrows(IOException.class, () -> Codec.encode(new Message.ObserveUpdate(state, null, 10, 180, "", SESSION, pose)));
            assertThrows(IOException.class, () -> Codec.encode(new Message.ObserveUpdate(state, TARGET, 0, 180, "", SESSION, pose)));
            assertThrows(IOException.class, () -> Codec.encode(new Message.ObserveUpdate(state, TARGET, 10, 0, "", SESSION, pose)));
        }
        assertThrows(IOException.class, () -> Codec.encode(new Message.ObserveReady(null, TARGET)));
        assertThrows(IOException.class, () -> Codec.encode(new Message.ObserveReady(SESSION, null)));
    }

    @Test
    void approachRequiresMatchingPoseAndOtherStatesRejectAllCoordinatePayloads() {
        assertThrows(IOException.class, () -> Codec.encode(new Message.ObserveUpdate(
                Message.ObserveState.APPROACHING, TARGET, 45_000, 180, "", SESSION, null)));
        Message.PlayerView wrong = new Message.PlayerView(SESSION, POSE.name(), POSE.world(),
                POSE.x(), POSE.y(), POSE.z(), POSE.yaw(), POSE.pitch(), POSE.lastActivityEpochMillis());
        assertThrows(IOException.class, () -> Codec.encode(new Message.ObserveUpdate(
                Message.ObserveState.APPROACHING, TARGET, 45_000, 180, "", SESSION, wrong)));
        for (Message.ObserveState state : List.of(Message.ObserveState.NOTICE, Message.ObserveState.FOLLOWING,
                Message.ObserveState.ENDED)) {
            assertThrows(IOException.class, () -> Codec.encode(new Message.ObserveUpdate(state, TARGET,
                    state == Message.ObserveState.ENDED ? 0 : 45_000,
                    state == Message.ObserveState.ENDED ? 0 : 180, "", SESSION, POSE)));
        }
    }

    @Test
    void approachRejectsInvalidCoordinatesPitchActivityAndPoseIdentityInBothDirections() throws IOException {
        for (Message.PlayerView invalid : List.of(
                new Message.PlayerView(TARGET, "Alice", "world", Double.NaN, 100, 0, 0, 0, 1),
                new Message.PlayerView(TARGET, "Alice", "world", 30_000_001, 100, 0, 0, 0, 1),
                new Message.PlayerView(TARGET, "Alice", "world", 0, 100, 0, 0, 91, 1),
                new Message.PlayerView(TARGET, "Alice", "world", 0, 100, 0, 0, 0, -1),
                new Message.PlayerView(null, "Alice", "world", 0, 100, 0, 0, 0, 1),
                new Message.PlayerView(TARGET, "Alice", "", 0, 100, 0, 0, 0, 1))) {
            assertThrows(IOException.class, () -> Codec.encode(new Message.ObserveUpdate(
                    Message.ObserveState.APPROACHING, TARGET, 45_000, 180, "", SESSION, invalid)));
        }
        byte[] valid = Codec.encode(new Message.ObserveUpdate(Message.ObserveState.APPROACHING,
                TARGET, 45_000, 180, "", SESSION, POSE));
        byte[] badPitch = valid.clone();
        ByteBuffer.wrap(badPitch).putFloat(badPitch.length - 12, 91);
        assertThrows(IOException.class, () -> Codec.decode(badPitch));
        byte[] mismatchedPose = valid.clone();
        int poseStart = 7 + 1 + 1 + 16 + 8 + 4 + 2 + 1 + 16 + 1;
        ByteBuffer.wrap(mismatchedPose).putLong(poseStart, 99);
        assertThrows(IOException.class, () -> Codec.decode(mismatchedPose));
        byte[] missingSession = valid.clone();
        missingSession[7 + 1 + 1 + 16 + 8 + 4 + 2] = 0;
        assertThrows(IOException.class, () -> Codec.decode(missingSession));
    }

    @Test
    void readyAndApproachRejectEveryTruncatedPrefixAndTrailingBytes() throws IOException {
        for (Message message : List.of(new Message.ObserveReady(SESSION, TARGET),
                new Message.ObserveUpdate(Message.ObserveState.APPROACHING, TARGET, 45_000, 480, "接近", SESSION, POSE))) {
            byte[] bytes = Codec.encode(message);
            for (int length = 0; length < bytes.length; length++) {
                byte[] prefix = Arrays.copyOf(bytes, length);
                assertThrows(IOException.class, () -> Codec.decode(prefix));
            }
            assertThrows(IOException.class, () -> Codec.decode(Arrays.copyOf(bytes, bytes.length + 1)));
        }
    }
}
