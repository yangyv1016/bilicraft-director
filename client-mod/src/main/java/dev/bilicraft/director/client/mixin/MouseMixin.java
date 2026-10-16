package dev.bilicraft.director.client.mixin;

import dev.bilicraft.director.client.BiliCraftDirectorClient;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.Mouse;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Mouse.class)
abstract class MouseMixin {
    @Shadow private double cursorDeltaX;
    @Shadow private double cursorDeltaY;

    @Inject(method = "updateMouse", at = @At("HEAD"), cancellable = true)
    private void bilicraftAi$blockManualLook(double timeDelta, CallbackInfo callback) {
        if (BiliCraftDirectorClient.getInstance().blocksManualMovement()
                && MinecraftClient.getInstance().currentScreen == null) {
            cursorDeltaX = 0;
            cursorDeltaY = 0;
            callback.cancel();
        }
    }
}
