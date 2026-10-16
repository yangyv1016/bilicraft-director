package dev.bilicraft.director.client.mixin;

import dev.bilicraft.director.client.BiliCraftDirectorClient;
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
        if (BiliCraftDirectorClient.getInstance().blocksManualMovement()) {
            callback.setReturnValue(false);
        }
    }

    @Inject(method = "doItemUse", at = @At("HEAD"), cancellable = true)
    private void bilicraftAi$blockWorldInteraction(CallbackInfo callback) {
        if (BiliCraftDirectorClient.getInstance().blocksManualMovement()) {
            callback.cancel();
        }
    }
}
