package dev.bilicraft.director.client.mixin;

import dev.bilicraft.director.client.BiliCraftDirectorClient;
import net.minecraft.client.gui.hud.ChatHud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Both the normal HUD and the open chat screen share this drawing backend. */
@Mixin(ChatHud.class)
abstract class ChatHudMixin {
    @Inject(method = "render(Lnet/minecraft/client/gui/hud/ChatHud$Backend;IIZ)V",
            at = @At("HEAD"), cancellable = true)
    private void bilicraftAi$hideChatMessages(CallbackInfo callback) {
        if (BiliCraftDirectorClient.getInstance().isRunning()) {
            callback.cancel();
        }
    }
}
