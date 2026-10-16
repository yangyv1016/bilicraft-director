package dev.bilicraft.director.client;

import dev.bilicraft.director.protocol.Codec;
import dev.bilicraft.director.protocol.Message;
import dev.bilicraft.director.protocol.Message.SceneView;
import dev.bilicraft.director.protocol.Protocol;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
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
        // Legacy scene files used the old product signature. Only local cache loading accepts it;
        // the current codec still validates the version, bounded payload and absence of player data.
        if (bytes.length >= Integer.BYTES && ByteBuffer.wrap(bytes).getInt() == 0x42434149) {
            ByteBuffer.wrap(bytes).putInt(Protocol.MAGIC);
        }
        // Wire versions 5 and 6 add other packets; their scene-only snapshot layout is unchanged.
        if (bytes.length >= 7 && ByteBuffer.wrap(bytes).getInt() == Protocol.MAGIC
                && (ByteBuffer.wrap(bytes).getShort(4) == 4 || ByteBuffer.wrap(bytes).getShort(4) == 5) && bytes[6] == 3) {
            ByteBuffer.wrap(bytes).putShort(4, (short) Protocol.VERSION);
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
