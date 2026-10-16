package dev.bilicraft.director.client;

import dev.bilicraft.director.protocol.Protocol;
import io.netty.handler.codec.DecoderException;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/** The payload body is exactly the shared protocol bytes, without a second length prefix. */
public record DirectorPayload(byte[] bytes) implements CustomPayload {
    public static final Id<DirectorPayload> ID = new Id<>(Identifier.of(Protocol.CHANNEL));
    public static final PacketCodec<RegistryByteBuf, DirectorPayload> CODEC = PacketCodec.ofStatic(
            (buffer, payload) -> buffer.writeBytes(payload.bytes()),
            buffer -> {
                if (buffer.readableBytes() > Protocol.MAX_PACKET_BYTES) {
                    throw new DecoderException("Director payload exceeds protocol limit");
                }
                byte[] bytes = new byte[buffer.readableBytes()];
                buffer.readBytes(bytes);
                return new DirectorPayload(bytes);
            });

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
