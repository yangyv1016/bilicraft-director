package dev.bilicraft.director.client.mixin;

import dev.bilicraft.director.client.BiliCraftDirectorClient;
import net.minecraft.client.option.GameOptions;
import net.minecraft.client.render.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(GameRenderer.class)
abstract class GameRendererMixin {
    @Redirect(method = "render(Lnet/minecraft/client/render/RenderTickCounter;Z)V",
            at = @At(value = "FIELD", target = "Lnet/minecraft/client/option/GameOptions;pauseOnLostFocus:Z"))
    private boolean bilicraftAi$keepDirectorRunningInBackground(GameOptions options) {
        return options.pauseOnLostFocus && !BiliCraftDirectorClient.getInstance().blocksManualMovement();
    }
}
