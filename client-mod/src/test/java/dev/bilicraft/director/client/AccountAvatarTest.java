package dev.bilicraft.director.client;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AccountAvatarTest {
    @Test
    void preservesFaceOrientationAndArgbChannelsWithoutOverlay() throws Exception {
        int[] face = new int[64];
        Arrays.fill(face, 0xff785634);
        face[0] = 0xff102030;
        face[7] = 0xffa0b0c0;
        face[63] = 0xff345678;
        BufferedImage result = decode(AccountAvatar.encodeHead(face, new int[64]));
        assertEquals(8, result.getWidth());
        assertEquals(8, result.getHeight());
        assertEquals(face[0], result.getRGB(0, 0));
        assertEquals(face[7], result.getRGB(7, 0));
        assertEquals(face[63], result.getRGB(7, 7));
        assertEquals(face[30], result.getRGB(6, 3));
    }

    @Test
    void compositesTransparentAndTranslucentHatPixelsOverFace() throws Exception {
        int[] face = new int[64];
        Arrays.fill(face, 0xff0000ff);
        int[] overlay = new int[64];
        overlay[0] = 0xffff0000;
        overlay[1] = 0x80ff0000;
        BufferedImage result = decode(AccountAvatar.encodeHead(face, overlay));
        assertEquals(0xffff0000, result.getRGB(0, 0));
        assertEquals(0xff80007f, result.getRGB(1, 0));
        assertEquals(0xff0000ff, result.getRGB(2, 0));
    }

    @Test
    void cropsOnlyFrontFaceAndHatFromBothSkinLayouts() throws Exception {
        for (int height : new int[]{32, 64}) {
            BufferedImage skin = new BufferedImage(64, height, BufferedImage.TYPE_INT_ARGB);
            skin.setRGB(8, 8, 0xff123456);
            skin.setRGB(15, 15, 0xff987654);
            skin.setRGB(40, 8, 0xffabcdef);
            skin.setRGB(0, 0, 0xffff0000);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            ImageIO.write(skin, "png", output);
            BufferedImage result = decode(AccountAvatar.encodeSkin(output.toByteArray()));
            assertEquals(0xffabcdef, result.getRGB(0, 0));
            assertEquals(0xff987654, result.getRGB(7, 7));
        }
    }

    @Test
    void rejectsMalformedImagesAndUnsupportedSkinDimensions() throws Exception {
        assertThrows(IOException.class, () -> AccountAvatar.encodeSkin(new byte[]{1, 2, 3}));
        BufferedImage wrong = new BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(wrong, "png", output);
        assertThrows(IOException.class, () -> AccountAvatar.encodeSkin(output.toByteArray()));
    }

    private static BufferedImage decode(String dataUrl) throws IOException {
        assertEquals("data:image/png;base64,", dataUrl.substring(0, 22));
        return ImageIO.read(new ByteArrayInputStream(Base64.getDecoder().decode(dataUrl.substring(22))));
    }
}
