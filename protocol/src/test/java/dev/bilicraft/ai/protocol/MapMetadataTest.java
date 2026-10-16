package dev.bilicraft.ai.protocol;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MapMetadataTest {
    @Test
    void requestAndConfiguredOrMissingSourcesRoundTripWithRequestOwnership() throws IOException {
        for (Message message : List.of(new Message.MapRequest(42),
                new Message.MapSource(42, "minecraft:overworld", false, "", "", List.of(), "未配置"),
                source(List.of(new Message.RegionBounds(-20, -10, 30, 40))))) {
            assertEquals(message, Codec.decode(Codec.encode(message)));
        }
        byte[] request = Codec.encode(new Message.MapRequest(42));
        assertEquals(15, request.length);
        assertEquals(11, request[6]);
        assertEquals(42, ByteBuffer.wrap(request).getLong(7));
    }

    @Test
    void mapRequestAndResponseRequirePositiveCorrelationId() throws IOException {
        assertThrows(IOException.class, () -> Codec.encode(new Message.MapRequest(0)));
        byte[] invalid = Codec.encode(new Message.MapRequest(1));
        ByteBuffer.wrap(invalid).putLong(7, -1);
        assertThrows(IOException.class, () -> Codec.decode(invalid));
        assertThrows(IOException.class, () -> Codec.encode(new Message.MapSource(0, "minecraft:overworld", false, "", "", List.of(), "")));
    }

    @Test
    void allExclusionsFitButNeverSilentlyTruncateInvalidOrOversizedLists() throws IOException {
        Message.RegionBounds region = new Message.RegionBounds(-10, -20, 10, 20);
        Message.MapSource maximum = source(Collections.nCopies(64, region));
        assertEquals(maximum, Codec.decode(Codec.encode(maximum)));
        assertTrue(Codec.encode(maximum).length < Protocol.MAX_PACKET_BYTES);
        assertThrows(IOException.class, () -> Codec.encode(source(Collections.nCopies(65, region))));
        assertThrows(IOException.class, () -> Codec.encode(source(List.of(new Message.RegionBounds(5, 0, 2, 2)))));
        assertThrows(IOException.class, () -> Codec.encode(source(List.of(new Message.RegionBounds(0, Double.NaN, 2, 2)))));
    }

    @Test
    void rejectsCredentialUrlsAndInconsistentMissingSource() {
        for (String url : List.of("file:///secret", "https://name:pass@map.example/", "https://map.example/?token=secret", "https://map.example/#part")) {
            assertThrows(IOException.class, () -> Codec.encode(new Message.MapSource(1, "minecraft:overworld", true, url, "Paralon", List.of(), "")));
        }
        assertThrows(IOException.class, () -> Codec.encode(new Message.MapSource(1, "minecraft:overworld", false, "https://map.example/", "", List.of(), "")));
        assertThrows(IOException.class, () -> Codec.encode(new Message.MapSource(1, "minecraft:overworld", true, "https://map.example/", "Paralon", List.of(), "中".repeat(171))));
    }

    private static Message.MapSource source(List<Message.RegionBounds> regions) {
        return new Message.MapSource(42, "minecraft:overworld", true, "https://map.example/s2/hyperion/", "Paralon", regions, "客户端直连");
    }
}
