package dev.bilicraft.director.client;

import dev.bilicraft.director.protocol.Codec;
import dev.bilicraft.director.protocol.Message;
import dev.bilicraft.director.protocol.Protocol;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Non-player world policies, isolated by the exact server address and world key. */
public final class LocalWorldPolicyCache {
    private final Path directory;

    public LocalWorldPolicyCache(Path directory) {
        this.directory = directory;
    }

    public Message.WorldPolicy load(String serverAddress, String worldKey) throws IOException {
        byte[] bytes;
        try (InputStream input = Files.newInputStream(pathFor(serverAddress, worldKey))) {
            bytes = input.readNBytes(Protocol.MAX_PACKET_BYTES + 1);
        } catch (NoSuchFileException exception) {
            return null;
        }
        Message message = Codec.decode(bytes);
        if (!(message instanceof Message.WorldPolicy policy) || !policy.worldKey().equals(worldKey)) {
            throw new IOException("World policy cache must contain a policy for the requested world");
        }
        return policy;
    }

    public void save(String serverAddress, Message.WorldPolicy policy) throws IOException {
        byte[] bytes = Codec.encode(policy);
        Path target = pathFor(serverAddress, policy.worldKey());
        Files.createDirectories(directory);
        Path temporary = Files.createTempFile(directory, target.getFileName() + ".", ".tmp");
        try {
            Files.write(temporary, bytes);
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException exception) {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException cleanupFailure) {
                exception.addSuppressed(cleanupFailure);
            }
            throw exception;
        }
    }

    private Path pathFor(String serverAddress, String worldKey) {
        if (serverAddress.isBlank() || worldKey.isBlank() || serverAddress.indexOf('\0') >= 0 || worldKey.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Missing local world policy identity");
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((serverAddress + '\0' + worldKey).getBytes(StandardCharsets.UTF_8));
            return directory.resolve(HexFormat.of().formatHex(digest) + ".bin");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Java runtime is missing mandatory SHA-256 support", exception);
        }
    }
}
