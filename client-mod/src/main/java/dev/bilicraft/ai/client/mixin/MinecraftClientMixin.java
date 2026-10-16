package dev.bilicraft.ai.client.mixin;

import dev.bilicraft.ai.client.BiliCraftAiClient;
import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(MinecraftClient.class)
abstract class MinecraftClientMixin {
    @Inject(method = "doAttack", at = @At("HEAD"), cancellable = true)
    private void bilicraftAi$blockSpectatorAttach(CallbackInfoReturnable<Boolean> callback) {
        if (BiliCraftAiClient.getInstance().blocksManualMovement()) {
            callback.setReturnValue(false);
        }
    }

    @Inject(method = "doItemUse", at = @At("HEAD"), cancellable = true)
    private void bilicraftAi$blockWorldInteraction(CallbackInfo callback) {
        if (BiliCraftAiClient.getInstance().blocksManualMovement()) {
            callback.cancel();
        }
    }
}
