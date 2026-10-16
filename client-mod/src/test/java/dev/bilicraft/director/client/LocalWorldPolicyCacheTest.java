package dev.bilicraft.director.client;

import dev.bilicraft.director.protocol.Codec;
import dev.bilicraft.director.protocol.Message;
import dev.bilicraft.director.protocol.Protocol;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalWorldPolicyCacheTest {
    private static final String SERVER = "server.example:25565";
    private static final String WORLD = "minecraft:overworld";
    private static final Message.WorldPolicy POLICY = new Message.WorldPolicy(WORLD, new UUID(1, 2), false,
            List.of(new Message.RegionBounds(-50, -30, 20, 10), new Message.RegionBounds(100, 200, 150, 250)));

    @TempDir
    Path directory;

    @Test
    void policySurvivesReloadWithWorldIdentityPatrolFlagAndAllExclusions() throws IOException {
        new LocalWorldPolicyCache(directory).save(SERVER, POLICY);
        Message.WorldPolicy loaded = new LocalWorldPolicyCache(directory).load(SERVER, WORLD);

        assertEquals(POLICY, loaded);
        assertThrows(UnsupportedOperationException.class, () -> loaded.exclusions().clear());
        try (var paths = Files.list(directory)) {
            List<Path> files = paths.toList();
            assertEquals(1, files.size());
            assertTrue(files.getFirst().getFileName().toString().matches("[0-9a-f]{64}\\.bin"));
            assertEquals(POLICY, assertInstanceOf(Message.WorldPolicy.class,
                    Codec.decode(Files.readAllBytes(files.getFirst()))));
        }
    }

    @Test
    void localVersionFivePolicyRetainsPatrolAndExclusionsWhileNetworkRejectsIt() throws IOException {
        LocalWorldPolicyCache cache = new LocalWorldPolicyCache(directory);
        cache.save(SERVER, POLICY);
        Path file;
        try (var paths = Files.list(directory)) { file = paths.findFirst().orElseThrow(); }
        byte[] old = Codec.encode(POLICY);
        ByteBuffer.wrap(old).putShort(4, (short) 5);
        Files.write(file, old);
        assertEquals(POLICY, cache.load(SERVER, WORLD));
        assertArrayEquals(old, Files.readAllBytes(file));
        assertThrows(IOException.class, () -> Codec.decode(old));
    }

    @Test
    void exactServerAddressPortCaseAndWorldAreIsolated() throws IOException {
        Message.WorldPolicy otherWorld = new Message.WorldPolicy("minecraft:the_nether", new UUID(3, 4), true, List.of());
        Message.WorldPolicy otherServer = new Message.WorldPolicy(WORLD, new UUID(5, 6), true, List.of());
        LocalWorldPolicyCache cache = new LocalWorldPolicyCache(directory);
        cache.save(SERVER, POLICY);
        cache.save(SERVER, otherWorld);
        cache.save("server.example:25566", otherServer);

        LocalWorldPolicyCache reloaded = new LocalWorldPolicyCache(directory);
        assertEquals(POLICY, reloaded.load(SERVER, WORLD));
        assertEquals(otherWorld, reloaded.load(SERVER, otherWorld.worldKey()));
        assertEquals(otherServer, reloaded.load("server.example:25566", WORLD));
        assertNull(reloaded.load("SERVER.example:25565", WORLD));
        assertNull(reloaded.load("other.example:25565", WORLD));
        assertNull(reloaded.load(SERVER, "minecraft:the_end"));
    }

    @Test
    void missingCacheReturnsUnknownWithoutCreatingFiles() throws IOException {
        Path missing = directory.resolve("missing");
        assertNull(new LocalWorldPolicyCache(missing).load(SERVER, WORLD));
        assertFalse(Files.exists(missing));
    }

    @Test
    void corruptOversizedUnknownVersionAndWrongWorldRemainUnmodifiedAndAreReported() throws IOException {
        LocalWorldPolicyCache cache = new LocalWorldPolicyCache(directory);
        cache.save(SERVER, POLICY);
        Path file;
        try (var paths = Files.list(directory)) { file = paths.findFirst().orElseThrow(); }
        byte[] unknownVersion = Codec.encode(POLICY);
        ByteBuffer.wrap(unknownVersion).putShort(4, (short) (Protocol.VERSION + 1));
        byte[] wrongWorld = Codec.encode(new Message.WorldPolicy("minecraft:the_nether", POLICY.worldId(), true,
                POLICY.exclusions()));

        for (byte[] invalid : List.of(new byte[]{1, 2, 3}, new byte[Protocol.MAX_PACKET_BYTES + 1],
                unknownVersion, wrongWorld, Codec.encode(new Message.Heartbeat()),
                Codec.encode(new Message.Snapshot(List.of(), List.of())))) {
            Files.write(file, invalid);
            assertThrows(IOException.class, () -> cache.load(SERVER, WORLD));
            assertArrayEquals(invalid, Files.readAllBytes(file));
        }
    }

    @Test
    void replacementUpdatesTheFullPolicyAndLeavesNoTemporaryFiles() throws IOException {
        LocalWorldPolicyCache cache = new LocalWorldPolicyCache(directory);
        cache.save(SERVER, POLICY);
        Message.WorldPolicy replacement = new Message.WorldPolicy(WORLD, new UUID(7, 8), true, List.of());
        cache.save(SERVER, replacement);

        assertEquals(replacement, cache.load(SERVER, WORLD));
        try (var paths = Files.list(directory)) { assertEquals(1, paths.count()); }
    }

    @Test
    void invalidPolicyCannotReplaceGoodCacheAndFailedMoveCleansItsTemporaryFile() throws IOException {
        LocalWorldPolicyCache cache = new LocalWorldPolicyCache(directory);
        cache.save(SERVER, POLICY);
        Path file;
        try (var paths = Files.list(directory)) { file = paths.findFirst().orElseThrow(); }
        byte[] original = Files.readAllBytes(file);
        Message.WorldPolicy invalid = new Message.WorldPolicy(WORLD, POLICY.worldId(), true,
                List.of(new Message.RegionBounds(10, 20, -10, -20)));
        assertThrows(IOException.class, () -> cache.save(SERVER, invalid));
        assertArrayEquals(original, Files.readAllBytes(file));

        Files.delete(file);
        Files.createDirectory(file);
        Files.writeString(file.resolve("occupied"), "keep");
        assertThrows(IOException.class, () -> cache.save(SERVER, POLICY));
        assertEquals("keep", Files.readString(file.resolve("occupied")));
        try (var paths = Files.list(directory)) { assertEquals(List.of(file), paths.toList()); }
    }

    @Test
    void identityValidationPreventsSeparatorCollisionsAndPathsStayInsideTheDirectory() throws IOException {
        LocalWorldPolicyCache cache = new LocalWorldPolicyCache(directory);
        assertThrows(IllegalArgumentException.class, () -> cache.load("a\0b", WORLD));
        assertThrows(IllegalArgumentException.class, () -> cache.load(SERVER, ""));
        assertThrows(IllegalArgumentException.class, () -> cache.load(" ", WORLD));
        cache.save("../../outside", POLICY);
        try (var paths = Files.list(directory)) {
            List<Path> files = paths.toList();
            assertEquals(1, files.size());
            assertEquals(directory, files.getFirst().getParent());
            assertTrue(files.getFirst().getFileName().toString().matches("[0-9a-f]{64}\\.bin"));
        }
    }
}
