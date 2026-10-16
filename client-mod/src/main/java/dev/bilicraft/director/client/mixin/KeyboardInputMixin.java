package dev.bilicraft.director.client.mixin;

import dev.bilicraft.director.client.BiliCraftDirectorClient;
import net.minecraft.client.input.Input;
import net.minecraft.client.input.KeyboardInput;
import net.minecraft.util.PlayerInput;
import net.minecraft.util.math.Vec2f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(KeyboardInput.class)
abstract class KeyboardInputMixin extends Input {
    @Inject(method = "tick", at = @At("TAIL"))
    private void bilicraftAi$clearManualMovement(CallbackInfo callback) {
        if (BiliCraftDirectorClient.getInstance().blocksManualMovement()) {
            playerInput = PlayerInput.DEFAULT;
            movementVector = Vec2f.ZERO;
        }
    }
}
