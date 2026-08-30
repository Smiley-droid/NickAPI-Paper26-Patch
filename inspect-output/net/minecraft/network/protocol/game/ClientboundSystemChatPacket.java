package net.minecraft.network.protocol.game;

import io.papermc.paper.adventure.PaperAdventure;
import net.md_5.bungee.api.chat.BaseComponent;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketType;
import org.bukkit.craftbukkit.util.CraftChatMessage;

public record ClientboundSystemChatPacket(Component content, boolean overlay) implements Packet<ClientGamePacketListener> {
   public static final StreamCodec<RegistryFriendlyByteBuf, ClientboundSystemChatPacket> STREAM_CODEC = StreamCodec.composite(
      ComponentSerialization.TRUSTED_STREAM_CODEC,
      ClientboundSystemChatPacket::content,
      ByteBufCodecs.BOOL,
      ClientboundSystemChatPacket::overlay,
      ClientboundSystemChatPacket::new
   );

   public ClientboundSystemChatPacket(BaseComponent[] content, boolean overlay) {
      this(CraftChatMessage.bungeeToVanilla(content), overlay);
   }

   public ClientboundSystemChatPacket(net.kyori.adventure.text.Component content, boolean overlay) {
      this(PaperAdventure.asVanilla(content), overlay);
   }

   public PacketType<ClientboundSystemChatPacket> type() {
      return GamePacketTypes.CLIENTBOUND_SYSTEM_CHAT;
   }

   public void handle(ClientGamePacketListener listener) {
      listener.handleSystemChat(this);
   }

   public boolean isSkippable() {
      return true;
   }
}
