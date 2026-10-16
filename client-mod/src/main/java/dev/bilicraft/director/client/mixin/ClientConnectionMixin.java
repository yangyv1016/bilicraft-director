package dev.bilicraft.director.client.mixin;

import dev.bilicraft.director.client.ResourcePackTracker;
import io.netty.channel.ChannelFutureListener;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.c2s.common.ResourcePackStatusC2SPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientConnection.class)
abstract class ClientConnectionMixin implements ResourcePackTracker.Access {
    @Unique
    private final ResourcePackTracker bilicraftDirector$resourcePacks = new ResourcePackTracker();

    @Override
    public ResourcePackTracker bilicraftDirector$resourcePackTracker() {
        return bilicraftDirector$resourcePacks;
    }

    @Inject(method = "send(Lnet/minecraft/network/packet/Packet;Lio/netty/channel/ChannelFutureListener;Z)V", at = @At("HEAD"))
    private void bilicraftDirector$observeResourcePackStatus(Packet<?> packet, ChannelFutureListener listener, boolean flush, CallbackInfo callback) {
        if (packet instanceof ResourcePackStatusC2SPacket status) {
            switch (status.status()) {
                case ACCEPTED, DOWNLOADED -> bilicraftDirector$resourcePacks.accepted(status.id());
                case SUCCESSFULLY_LOADED, DECLINED, FAILED_DOWNLOAD, INVALID_URL, FAILED_RELOAD, DISCARDED ->
                        bilicraftDirector$resourcePacks.finished(status.id());
            }
        }
    }
}
