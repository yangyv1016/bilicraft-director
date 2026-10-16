package dev.bilicraft.ai.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Captures only the already-loaded skin head on the game thread; PNG work uses owned plain data. */
final class AccountAvatar implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger("bilicraft-ai");
    private final ExecutorService encoder = Executors.newSingleThreadExecutor(
            Thread.ofPlatform().daemon().name("bilicraft-account-avatar").factory());
    private Identifier skin;
    private CompletableFuture<String> pending;
    private String cached;
    private boolean failed;

    String dataUrl(MinecraftClient client) {
        if (client.player == null) {
            reset();
            return null;
        }
        Identifier currentSkin = client.player.getSkin().body().texturePath();
        if (!currentSkin.equals(skin)) {
            reset();
            skin = currentSkin;
        }
        if (cached != null || failed) {
            return cached;
        }
        if (pending == null) {
            var texture = client.getTextureManager().getTexture(currentSkin);
            if (texture instanceof NativeImageBackedTexture backed) {
                NativeImage image = backed.getImage();
                if (image == null) {
                    return null;
                }
                if (image.getWidth() != 64 || (image.getHeight() != 32 && image.getHeight() != 64)) {
                    failed = true;
                    LOGGER.warn("Cannot prepare account avatar: unsupported loaded skin dimensions");
                    return null;
                }
                int[] face = new int[64];
                int[] overlay = new int[64];
                for (int y = 0; y < 8; y++) {
                    for (int x = 0; x < 8; x++) {
                        face[y * 8 + x] = image.getColorArgb(x + 8, y + 8);
                        overlay[y * 8 + x] = image.getColorArgb(x + 40, y + 8);
                    }
                }
                pending = CompletableFuture.supplyAsync(() -> {
                    try {
                        return encodeHead(face, overlay);
                    } catch (IOException exception) {
                        throw new CompletionException(exception);
                    }
                }, encoder);
            } else {
                // Bundled default skins are resources, while downloaded skins retain a NativeImage above.
                var resource = client.getResourceManager().getResource(currentSkin);
                if (resource.isEmpty()) {
                    return null;
                }
                byte[] bytes;
                try (InputStream input = resource.get().getInputStream()) {
                    bytes = input.readNBytes(262_145);
                    if (bytes.length > 262_144) {
                        throw new IOException("Default skin resource exceeds avatar limit");
                    }
                } catch (IOException exception) {
                    failed = true;
                    LOGGER.warn("Cannot read local default skin for account avatar", exception);
                    return null;
                }
                pending = CompletableFuture.supplyAsync(() -> {
                    try {
                        return encodeSkin(bytes);
                    } catch (IOException exception) {
                        throw new CompletionException(exception);
                    }
                }, encoder);
            }
        }
        if (pending.isDone()) {
            try {
                cached = pending.join();
            } catch (CompletionException exception) {
                failed = true;
                LOGGER.warn("Cannot encode local account avatar", exception.getCause());
            }
        }
        return cached;
    }

    static String encodeSkin(byte[] png) throws IOException {
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));
        if (image == null || image.getWidth() != 64 || (image.getHeight() != 32 && image.getHeight() != 64)) {
            throw new IOException("Unsupported skin image");
        }
        return encodeHead(image.getRGB(8, 8, 8, 8, null, 0, 8),
                image.getRGB(40, 8, 8, 8, null, 0, 8));
    }

    static String encodeHead(int[] face, int[] overlay) throws IOException {
        BufferedImage head = new BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB);
        BufferedImage hat = new BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB);
        head.setRGB(0, 0, 8, 8, face, 0, 8);
        hat.setRGB(0, 0, 8, 8, overlay, 0, 8);
        Graphics2D graphics = head.createGraphics();
        try {
            graphics.drawImage(hat, 0, 0, null);
        } finally {
            graphics.dispose();
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        if (!ImageIO.write(head, "png", output)) {
            throw new IOException("PNG encoder unavailable");
        }
        return "data:image/png;base64," + Base64.getEncoder().encodeToString(output.toByteArray());
    }

    private void reset() {
        if (pending != null) {
            pending.cancel(false);
        }
        skin = null;
        pending = null;
        cached = null;
        failed = false;
    }

    @Override
    public void close() {
        reset();
        encoder.shutdownNow();
    }
}
