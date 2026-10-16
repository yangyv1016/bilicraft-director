package dev.bilicraft.director.protocol;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldPolicyTest {
    private static final UUID WORLD_ID = UUID.fromString("f3baec1d-b51a-4b5a-8328-870ef4badc91");
    private static final String WORLD_KEY = "minecraft:overworld";

    @Test
    void patrolAndManualOnlyPoliciesRoundTripWithoutAMapSource() throws IOException {
        List<Message.RegionBounds> exclusions = List.of(new Message.RegionBounds(-20, -10, 30, 40));
        for (boolean enabled : List.of(true, false)) {
            Message.WorldPolicy policy = new Message.WorldPolicy(WORLD_KEY, WORLD_ID, enabled, exclusions);
            assertEquals(policy, Codec.decode(Codec.encode(policy)));
        }
        Message.WorldPolicy absent = new Message.WorldPolicy(WORLD_KEY, WORLD_ID, false, List.of());
        assertEquals(absent, Codec.decode(Codec.encode(absent)));
        assertEquals(13, Codec.encode(absent)[6]);
        assertThrows(UnsupportedOperationException.class, () -> absent.exclusions().clear());
    }

    @Test
    void allRulesFitAndIncompleteOrMalformedPolicyCannotBeAccepted() throws IOException {
        Message.RegionBounds region = new Message.RegionBounds(-10, -20, 10, 20);
        Message.WorldPolicy maximum = new Message.WorldPolicy(WORLD_KEY, WORLD_ID, false,
                Collections.nCopies(Protocol.MAX_MAP_EXCLUSIONS, region));
        assertEquals(maximum, Codec.decode(Codec.encode(maximum)));
        assertTrue(Codec.encode(maximum).length < Protocol.MAX_PACKET_BYTES);
        assertThrows(IOException.class, () -> Codec.encode(new Message.WorldPolicy(WORLD_KEY, WORLD_ID, false,
                Collections.nCopies(Protocol.MAX_MAP_EXCLUSIONS + 1, region))));
        assertThrows(IOException.class, () -> Codec.encode(new Message.WorldPolicy("", WORLD_ID, true, List.of())));
        assertThrows(IOException.class, () -> Codec.encode(new Message.WorldPolicy("world\nkey", WORLD_ID, true, List.of())));
        assertThrows(IOException.class, () -> Codec.encode(new Message.WorldPolicy(WORLD_KEY, null, true, List.of())));
        for (Message.RegionBounds invalid : List.of(new Message.RegionBounds(5, 0, 2, 2),
                new Message.RegionBounds(0, Double.NaN, 2, 2))) {
            assertThrows(IOException.class, () -> Codec.encode(new Message.WorldPolicy(WORLD_KEY, WORLD_ID, false, List.of(invalid))));
        }
    }

    @Test
    void rejectsTruncationNonBooleanFlagAndForgedRuleCount() throws IOException {
        byte[] valid = Codec.encode(new Message.WorldPolicy(WORLD_KEY, WORLD_ID, false, List.of()));
        for (int length = 0; length < valid.length; length++) {
            byte[] truncated = Arrays.copyOf(valid, length);
            assertThrows(IOException.class, () -> Codec.decode(truncated));
        }
        int flagOffset = 7 + 2 + WORLD_KEY.getBytes(StandardCharsets.UTF_8).length + 16;
        byte[] invalidFlag = valid.clone();
        invalidFlag[flagOffset] = 2;
        assertThrows(IOException.class, () -> Codec.decode(invalidFlag));
        ByteBuffer.wrap(valid).putShort(flagOffset + 1, (short) 65);
        assertThrows(IOException.class, () -> Codec.decode(valid));
    }
}
