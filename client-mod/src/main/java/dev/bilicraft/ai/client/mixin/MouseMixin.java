package dev.bilicraft.ai.client.mixin;

import dev.bilicraft.ai.client.BiliCraftAiClient;
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
        if (BiliCraftAiClient.getInstance().blocksManualMovement()
                && MinecraftClient.getInstance().currentScreen == null) {
            cursorDeltaX = 0;
            cursorDeltaY = 0;
            callback.cancel();
        }
    }
}
