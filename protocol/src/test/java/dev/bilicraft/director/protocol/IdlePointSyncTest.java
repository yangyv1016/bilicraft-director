package dev.bilicraft.director.protocol;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class IdlePointSyncTest {
    @Test
    void synchronizePoseAndExplicitRemovalForCurrentWorld() throws IOException {
        for (Message message : List.of(new Message.IdlePointSync("minecraft:overworld", null),
                new Message.IdlePointSync("minecraft:the_nether", new Message.IdlePose(10.25, 122.75, -30.125, 450, -23.5f)))) {
            assertEquals(message, Codec.decode(Codec.encode(message)));
        }
        assertThrows(IOException.class, () -> Codec.encode(new Message.IdlePointSync(" ", null)));
    }

    @Test
    void invalidPosesCannotBeCreatedOrDecodedAndTruncationIsRejected() throws IOException {
        assertThrows(IllegalArgumentException.class, () -> new Message.IdlePose(Double.NaN, 80, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new Message.IdlePose(0, Double.POSITIVE_INFINITY, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new Message.IdlePose(0, 80, 30_000_001, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new Message.IdlePose(0, 80, 0, Float.NaN, 0));
        assertThrows(IllegalArgumentException.class, () -> new Message.IdlePose(0, 80, 0, 0, -91));
        byte[] valid = Codec.encode(new Message.IdlePointSync("world", new Message.IdlePose(1, 80, 2, 90, 30)));
        for (int length = 0; length < valid.length; length++) {
            byte[] truncated = Arrays.copyOf(valid, length);
            assertThrows(IOException.class, () -> Codec.decode(truncated));
        }
        for (float invalid : new float[]{91, Float.NaN, Float.POSITIVE_INFINITY}) {
            byte[] bad = valid.clone();
            ByteBuffer.wrap(bad).putFloat(bad.length - 4, invalid);
            assertThrows(IOException.class, () -> Codec.decode(bad));
        }
        byte[] old = valid.clone();
        ByteBuffer.wrap(old).putShort(4, (short) 5);
        assertThrows(IOException.class, () -> Codec.decode(old));
    }
}
