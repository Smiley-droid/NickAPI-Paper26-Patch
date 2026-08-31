package xyz.haoshoku.nick.version.v26_2;

import com.google.common.collect.LinkedListMultimap;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.properties.PropertyMap;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelPromise;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import net.minecraft.Optionull;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.RemoteChatSession;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerChatPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket.Action;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket.Entry;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.server.dedicated.DedicatedPlayerList;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity.RemovalReason;
import net.minecraft.world.entity.player.PlayerModelPart;
import org.bukkit.Bukkit;
import org.bukkit.craftbukkit.CraftServer;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerRespawnEvent.RespawnReason;
import org.bukkit.plugin.Plugin;
import xyz.haoshoku.nick.events.NickFinishEvent;
import xyz.haoshoku.nick.user.NickUser;
import xyz.haoshoku.nick.user.UserHandler;
import xyz.haoshoku.nick.utils.ReflectionUtils;
import xyz.haoshoku.nick.version.VersionHandler;

/**
 * Handler NMS pour Paper 26.2.
 *
 * Ecrit a partir du bytecode reel du jar officiel Paper 26.2 build 112
 * (decompile avec Vineflower), PAS par extrapolation depuis Handler_v1_21_R7.
 * Les noms de classes/champs/methodes ci-dessous sont les vrais noms Mojang
 * (Paper ne re-obfusque plus son jar depuis un moment ; fini les lettres
 * uniques type ".a()", ".b" de l'ancien mapping Spigot).
 *
 * Changements structurels reperes par rapport a 1.21.11 (Handler_v1_21_R7) :
 * - ClientboundPlayerInfoUpdatePacket.Action a un nouveau membre UPDATE_HAT
 *   (le systeme reader/writer par action existait deja en interne, juste
 *   invisible derriere l'ancien mapping obfusque).
 * - PlayerList#respawn a perdu son parametre Location (4 params au lieu de 5).
 * - Beaucoup de champs/methodes qui necessitaient de la reflexion avant
 *   (connection, send(), getUUID() sur les paquets...) sont maintenant PUBLICS,
 *   donc plus besoin de ReflectionUtils pour ces acces-la.
 */
public class Handler_v26_2 implements VersionHandler {
   private Plugin plugin;
   private boolean isPaper;

   public void pluginOnEnable(Plugin plugin) {
      this.plugin = plugin;

      try {
         Class.forName("io.papermc.paper.connection.PlayerConnection");
         this.isPaper = true;
      } catch (Exception var3) {
         this.isPaper = false;
      }

      Bukkit.getScheduler().runTask(plugin, () -> {
         for (Player player : Bukkit.getOnlinePlayers()) {
            UserHandler.createUser(player.getUniqueId());
            NickUser user = UserHandler.getUser(player.getUniqueId());
            this.setPlayerData(player);
            user.setInitialized(true);
            this.sendPacket(player);
         }
      });
   }

   public void pluginOnDisable(Plugin plugin) {
      for (Player player : Bukkit.getOnlinePlayers()) {
         NickUser user = UserHandler.getUser(player.getUniqueId());
         if (user != null) {
            for (Player online : Bukkit.getOnlinePlayers()) {
               if (player != online) {
                  this.removeCurrentUniqueId(player, online);
               }
            }
            UserHandler.deleteUser(player.getUniqueId());
         }
      }
   }

   public void inject(final Player player) {
      ChannelDuplexHandler duplexHandler = new ChannelDuplexHandler() {
         public void write(ChannelHandlerContext ctx, Object packet, ChannelPromise promise) throws Exception {
            if (packet instanceof ClientboundPlayerChatPacket chatPacket) {
               Component decorated = chatPacket.chatType().decorate(
                  chatPacket.unsignedContent() == null
                     ? Component.literal(chatPacket.body().content())
                     : chatPacket.unsignedContent()
               );
               ((CraftPlayer) player).getHandle().connection.send(new ClientboundSystemChatPacket(decorated, false));
            } else {
               if (packet instanceof ClientboundBundlePacket bundlePacket) {
                  for (Packet<? super ClientGamePacketListener> subPacket : bundlePacket.subPackets()) {
                     if (subPacket instanceof ClientboundAddEntityPacket addEntityPacket) {
                        UUID uuid = (UUID) ReflectionUtils.getField(addEntityPacket, "uuid");
                        NickUser receivedUser = UserHandler.getUser(uuid);
                        NickUser playerUser = UserHandler.getUser(player.getUniqueId());
                        if (playerUser != null && receivedUser != null && receivedUser.getNickedUniqueId() != null && !playerUser.getBypassNickSet().contains(uuid)) {
                           ReflectionUtils.setField(addEntityPacket, "uuid", receivedUser.getNickedUniqueId());
                        }
                     }
                  }
               }

               if (packet instanceof ClientboundPlayerInfoUpdatePacket infoUpdatePacket) {
                  List<Entry> entries = infoUpdatePacket.entries();
                  ArrayList<Entry> entriesListCopied = new ArrayList<>(entries);

                  for (int i = 0; i < entriesListCopied.size(); ++i) {
                     Entry entry = entriesListCopied.get(i);
                     if (!entry.profileId().equals(player.getUniqueId())) {
                        NickUser receivedUser = UserHandler.getUser(entry.profileId());
                        if (receivedUser != null && receivedUser.getNickedUniqueId() != null && receivedUser.getNickedName() != null && receivedUser.getNickedValue() != null && receivedUser.getNickedSignature() != null && UserHandler.getUser(player.getUniqueId()) != null) {
                           NickUser playerUser = UserHandler.getUser(player.getUniqueId());
                           String packetName;
                           String packetValue;
                           String packetSignature;
                           UUID packetUniqueId;
                           if (playerUser.getBypassNickSet().contains(receivedUser.getOriginalUniqueId())) {
                              packetUniqueId = receivedUser.getOriginalUniqueId();
                              packetName = receivedUser.getOriginalName();
                              packetValue = receivedUser.getOriginalValue();
                              packetSignature = receivedUser.getOriginalSignature();
                           } else {
                              packetUniqueId = receivedUser.getNickedUniqueId();
                              packetName = receivedUser.getNickedName();
                              packetValue = receivedUser.getNickedValue();
                              packetSignature = receivedUser.getNickedSignature();
                           }

                           LinkedListMultimap<String, Property> properties = LinkedListMultimap.create();
                           properties.put("textures", new Property("textures", packetValue, packetSignature));
                           GameProfile newGameProfile = new GameProfile(packetUniqueId, packetName, new PropertyMap(properties));
                           Entry newEntry = new Entry(
                              packetUniqueId, newGameProfile, entry.listed(), entry.latency(), entry.gameMode(),
                              entry.displayName(), entry.showHat(), entry.listOrder(), entry.chatSession()
                           );
                           entriesListCopied.set(i, newEntry);
                        }
                     }
                  }

                  ReflectionUtils.setField(infoUpdatePacket, "entries", entriesListCopied);
               }

               super.write(ctx, packet, promise);
            }
         }
      };
      ChannelPipeline pipeline = this.pipeline(player);
      pipeline.addBefore("packet_handler", "nickapi", duplexHandler);
   }

   public void sendPacket(Player player) {
      CraftPlayer craftPlayer = (CraftPlayer) player;
      ServerPlayer serverPlayer = craftPlayer.getHandle();
      NickUser user = UserHandler.getUser(player.getUniqueId());
      if (player.isOnline() && user != null) {
         ClientboundPlayerInfoRemovePacket removeInfoPacket = new ClientboundPlayerInfoRemovePacket(Collections.singletonList(player.getUniqueId()));
         String name;
         String value;
         String signature;
         if (user.getBypassNickSet().contains(player.getUniqueId())) {
            name = user.getOriginalName();
            value = user.getOriginalValue();
            signature = user.getOriginalSignature();
         } else {
            name = user.getNickedName();
            value = user.getNickedValue();
            signature = user.getNickedSignature();
         }

         LinkedListMultimap<String, Property> properties = LinkedListMultimap.create();
         properties.put("textures", new Property("textures", value, signature));
         GameProfile newProfile = new GameProfile(user.getNickedUniqueId(), name, new PropertyMap(properties));

         EnumSet<Action> enumActions = EnumSet.of(
            Action.ADD_PLAYER, Action.INITIALIZE_CHAT, Action.UPDATE_GAME_MODE,
            Action.UPDATE_LISTED, Action.UPDATE_LATENCY, Action.UPDATE_DISPLAY_NAME
         );
         Entry newEntry = new Entry(
            player.getUniqueId(),
            newProfile,
            true,
            craftPlayer.getPing(),
            serverPlayer.gameMode(),
            serverPlayer.getTabListDisplayName(),
            serverPlayer.isModelPartShown(PlayerModelPart.HAT),
            serverPlayer.getTabListOrder(),
            Optionull.map(serverPlayer.getChatSession(), RemoteChatSession::asData)
         );
         ClientboundPlayerInfoUpdatePacket updateInfoPacket = new ClientboundPlayerInfoUpdatePacket(enumActions, List.of(newEntry));

         DedicatedPlayerList playerList = ((CraftServer) Bukkit.getServer()).getHandle();
         serverPlayer.connection.send(removeInfoPacket);
         serverPlayer.connection.send(updateInfoPacket);
         // On force systematiquement un respawn NMS complet : c'est ce qui force
         // TOUS les autres clients a re-dessiner le modele du joueur (entite
         // retiree puis recreee avec le nouveau GameProfile). L'ancienne version
         // (Handler_v1_21_R7) utilisait plutot un appel reflexif a la methode
         // privee CraftPlayer#refreshPlayer() sur Paper, mais cet appel echoue
         // silencieusement sur 26.2 (exception avalee par un catch generique),
         // ce qui laissait l'entite jamais rafraichie pour les joueurs non
         // deguises - exactement le symptome observe (visible en tab-list/chat
         // mais invisible physiquement pour les autres joueurs).
         playerList.respawn(serverPlayer, true, RemovalReason.CHANGED_DIMENSION, RespawnReason.PLUGIN);

         for (Player online : Bukkit.getOnlinePlayers()) {
            if (online != player && online.canSee(player)) {
               online.hidePlayer(this.plugin, player);
               online.showPlayer(this.plugin, player);
            }
         }

         user.setCurrentNicking(false);
         Bukkit.getPluginManager().callEvent(new NickFinishEvent(player, user.getOriginalUniqueId(), user.getOriginalName(), user.getOriginalValue(), user.getOriginalSignature(), user.getNickedUniqueId(), user.getNickedName(), user.getNickedValue(), user.getNickedSignature(), user.getBypassNickSet()));
      }
   }

   private ChannelPipeline pipeline(Player player) {
      ServerPlayer serverPlayer = ((CraftPlayer) player).getHandle();
      return serverPlayer.connection.connection.channel.pipeline();
   }

   public void removeCurrentUniqueId(Player player, Player toPlayer) {
      NickUser playerUser = UserHandler.getUser(player.getUniqueId());
      if (playerUser != null) {
         ((CraftPlayer) toPlayer).getHandle().connection.send(new ClientboundPlayerInfoRemovePacket(Collections.singletonList(playerUser.getNickedUniqueId())));
      }
   }

   public void setPlayerData(Player player) {
      NickUser user = UserHandler.getUser(player.getUniqueId());
      GameProfile profile = ReflectionUtils.getProfile(player);
      user.setOriginalName(player.getName());
      user.setOriginalUniqueId(player.getUniqueId());
      user.setNickedName(player.getName());
      user.setNickedUniqueId(player.getUniqueId());

      for (Property property : profile.properties().get("textures")) {
         user.setOriginalValue(property.value());
         user.setOriginalSignature(property.signature());
         user.setNickedValue(property.value());
         user.setNickedSignature(property.signature());
      }

      if (user.getOriginalValue() == null) {
         user.setOriginalValue("");
         user.setOriginalSignature("");
         user.setNickedValue("");
         user.setNickedSignature("");
      }
   }
}
