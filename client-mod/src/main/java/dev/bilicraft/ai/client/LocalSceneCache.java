package dev.bilicraft.ai.client;

import dev.bilicraft.ai.protocol.Codec;
import dev.bilicraft.ai.protocol.Message;
import dev.bilicraft.ai.protocol.Message.SceneView;
import dev.bilicraft.ai.protocol.Protocol;

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
import java.util.List;

/** Server-address-isolated non-player scenes; callers save only authorized server snapshots. */
public final class LocalSceneCache {
    private final Path directory;

    public LocalSceneCache(Path directory) {
        this.directory = directory;
    }

    public List<SceneView> load(String serverAddress) throws IOException {
        byte[] bytes;
        try (InputStream input = Files.newInputStream(pathFor(serverAddress))) {
            bytes = input.readNBytes(Protocol.MAX_PACKET_BYTES + 1);
        } catch (NoSuchFileException exception) {
            return List.of();
        }
        Message message = Codec.decode(bytes);
        if (!(message instanceof Message.Snapshot snapshot) || !snapshot.players().isEmpty()) {
            throw new IOException("Scene cache must contain a snapshot without player data");
        }
        return snapshot.scenes();
    }

    public void save(String serverAddress, List<SceneView> scenes) throws IOException {
        byte[] bytes = Codec.encode(new Message.Snapshot(List.of(), scenes));
        Path target = pathFor(serverAddress);
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

    private Path pathFor(String serverAddress) {
        byte[] digest;
        try {
            digest = MessageDigest.getInstance("SHA-256").digest(serverAddress.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Java runtime is missing mandatory SHA-256 support", exception);
        }
        return directory.resolve(HexFormat.of().formatHex(digest) + ".bin");
    }
}
