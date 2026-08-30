package net.minecraft.network.protocol.login;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.login.custom.CustomQueryPayload;
import net.minecraft.resources.Identifier;

public record ClientboundCustomQueryPacket$PlayerInfoChannelPayload(Identifier id, FriendlyByteBuf buffer) implements CustomQueryPayload {
   public void write(FriendlyByteBuf buf) {
      buf.writeBytes(this.buffer.copy());
   }
}
