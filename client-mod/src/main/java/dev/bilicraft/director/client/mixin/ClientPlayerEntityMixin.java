package dev.bilicraft.director.client.mixin;

import dev.bilicraft.director.client.BiliCraftDirectorClient;
import net.minecraft.client.network.ClientPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ClientPlayerEntity.class)
abstract class ClientPlayerEntityMixin {
    @Inject(method = "tickMovement", at = @At("HEAD"))
    private void bilicraftAi$driveCamera(CallbackInfo callback) {
        BiliCraftDirectorClient.getInstance().driveCamera((ClientPlayerEntity) (Object) this);
    }

    // Vanilla's local-player overrides ignore tick progress for responsive mouse input.
    // Automated angles change at 20 Hz, so restore the entity's render-frame interpolation.
    @Inject(method = "getYaw(F)F", at = @At("HEAD"), cancellable = true)
    private void bilicraftAi$interpolateYaw(float tickProgress, CallbackInfoReturnable<Float> callback) {
        if (BiliCraftDirectorClient.getInstance().blocksManualMovement()) {
            callback.setReturnValue(((ClientPlayerEntity) (Object) this).getLerpedYaw(tickProgress));
        }
    }

    @Inject(method = "getPitch(F)F", at = @At("HEAD"), cancellable = true)
    private void bilicraftAi$interpolatePitch(float tickProgress, CallbackInfoReturnable<Float> callback) {
        if (BiliCraftDirectorClient.getInstance().blocksManualMovement()) {
            callback.setReturnValue(((ClientPlayerEntity) (Object) this).getLerpedPitch(tickProgress));
        }
    }
}
