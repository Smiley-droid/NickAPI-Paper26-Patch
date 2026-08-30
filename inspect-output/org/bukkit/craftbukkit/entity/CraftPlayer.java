package org.bukkit.craftbukkit.entity;

import ca.spottedleaf.moonrise.common.PlatformHooks;
import com.destroystokyo.paper.ClientOption;
import com.destroystokyo.paper.PaperSkinParts;
import com.destroystokyo.paper.Title;
import com.destroystokyo.paper.ClientOption.ChatVisibility;
import com.destroystokyo.paper.ClientOption.ParticleVisibility;
import com.destroystokyo.paper.profile.CraftPlayerProfile;
import com.destroystokyo.paper.profile.PlayerProfile;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSet.Builder;
import com.google.common.io.BaseEncoding;
import com.mojang.authlib.GameProfile;
import com.mojang.datafixers.util.Pair;
import com.mojang.logging.LogUtils;
import io.papermc.paper.FeatureHooks;
import io.papermc.paper.adventure.BossBarImplementationImpl;
import io.papermc.paper.adventure.DisplayNames;
import io.papermc.paper.adventure.PaperAdventure;
import io.papermc.paper.connection.PlayerGameConnection;
import io.papermc.paper.connection.PluginMessageBridgeImpl;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.dialog.PaperDialog;
import io.papermc.paper.entity.LookAnchor;
import io.papermc.paper.entity.PaperPlayerGiveResult;
import io.papermc.paper.entity.PlayerGiveResult;
import io.papermc.paper.entity.TeleportFlag;
import io.papermc.paper.math.Angle;
import io.papermc.paper.math.Position;
import io.papermc.paper.util.MCUtil;
import it.unimi.dsi.fastutil.shorts.Short2ObjectArrayMap;
import it.unimi.dsi.fastutil.shorts.Short2ObjectMap;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.Map.Entry;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.stream.Collectors;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.bossbar.BossBarImplementation;
import net.kyori.adventure.chat.SignedMessage;
import net.kyori.adventure.chat.ChatType.Bound;
import net.kyori.adventure.chat.SignedMessage.Signature;
import net.kyori.adventure.dialog.DialogLike;
import net.kyori.adventure.identity.Identity;
import net.kyori.adventure.inventory.Book;
import net.kyori.adventure.pointer.Pointers;
import net.kyori.adventure.pointer.PointersSupplier;
import net.kyori.adventure.resource.ResourcePackCallback;
import net.kyori.adventure.resource.ResourcePackInfo;
import net.kyori.adventure.resource.ResourcePackRequest;
import net.kyori.adventure.sound.SoundStop;
import net.kyori.adventure.sound.Sound.Emitter;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.title.TitlePart;
import net.kyori.adventure.title.Title.Times;
import net.kyori.adventure.util.MonkeyBars;
import net.kyori.adventure.util.TriState;
import net.md_5.bungee.api.chat.BaseComponent;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.SectionPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.ChatType;
import net.minecraft.network.chat.MessageSignature;
import net.minecraft.network.chat.OutgoingChatMessage;
import net.minecraft.network.chat.PlayerChatMessage;
import net.minecraft.network.chat.ResolutionContext;
import net.minecraft.network.chat.MessageSignature.Packed;
import net.minecraft.network.chat.OutgoingChatMessage.Disguised;
import net.minecraft.network.chat.PlayerChatMessage.AdventureView;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientCommonPacketListener;
import net.minecraft.network.protocol.common.ClientboundClearDialogPacket;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.ClientboundResourcePackPopPacket;
import net.minecraft.network.protocol.common.ClientboundResourcePackPushPacket;
import net.minecraft.network.protocol.common.ClientboundServerLinksPacket;
import net.minecraft.network.protocol.common.custom.DiscardedPayload;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockDestructionPacket;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundClearTitlesPacket;
import net.minecraft.network.protocol.game.ClientboundCustomChatCompletionsPacket;
import net.minecraft.network.protocol.game.ClientboundDeleteChatPacket;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.network.protocol.game.ClientboundGameEventPacket;
import net.minecraft.network.protocol.game.ClientboundHurtAnimationPacket;
import net.minecraft.network.protocol.game.ClientboundLevelEventPacket;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.network.protocol.game.ClientboundMapItemDataPacket;
import net.minecraft.network.protocol.game.ClientboundOpenBookPacket;
import net.minecraft.network.protocol.game.ClientboundOpenSignEditorPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundRemoveMobEffectPacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetBorderCenterPacket;
import net.minecraft.network.protocol.game.ClientboundSetBorderLerpSizePacket;
import net.minecraft.network.protocol.game.ClientboundSetBorderSizePacket;
import net.minecraft.network.protocol.game.ClientboundSetBorderWarningDelayPacket;
import net.minecraft.network.protocol.game.ClientboundSetBorderWarningDistancePacket;
import net.minecraft.network.protocol.game.ClientboundSetDefaultSpawnPositionPacket;
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket;
import net.minecraft.network.protocol.game.ClientboundSetExperiencePacket;
import net.minecraft.network.protocol.game.ClientboundSetHealthPacket;
import net.minecraft.network.protocol.game.ClientboundSetPlayerInventoryPacket;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.network.protocol.game.ClientboundSoundEntityPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.network.protocol.game.ClientboundStopSoundPacket;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.network.protocol.game.ClientboundTabListPacket;
import net.minecraft.network.protocol.game.ClientboundUpdateAttributesPacket;
import net.minecraft.network.protocol.game.ClientboundUpdateMobEffectPacket;
import net.minecraft.network.protocol.game.ClientboundCustomChatCompletionsPacket.Action;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.PlayerAdvancements;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ChunkMap.TrackedEntity;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.server.permissions.PermissionLevel;
import net.minecraft.server.players.PlayerList;
import net.minecraft.server.players.UserWhiteListEntry;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.ProblemReporter;
import net.minecraft.util.Util;
import net.minecraft.util.ProblemReporter.ScopedCollector;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntitySpawnRequest;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.food.FoodData;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.component.WrittenBookContent;
import net.minecraft.world.item.enchantment.EnchantedItemInUse;
import net.minecraft.world.item.enchantment.EnchantmentEffectComponents;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.NoteBlockInstrument;
import net.minecraft.world.level.border.BorderChangeListener;
import net.minecraft.world.level.saveddata.maps.MapDecoration;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData.MapPatch;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.level.storage.LevelData.RespawnData;
import net.minecraft.world.phys.Vec3;
import org.bukkit.BanEntry;
import org.bukkit.ChatColor;
import org.bukkit.Chunk;
import org.bukkit.DyeColor;
import org.bukkit.Effect;
import org.bukkit.EntityEffect;
import org.bukkit.GameMode;
import org.bukkit.Input;
import org.bukkit.Instrument;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Note;
import org.bukkit.OfflinePlayer;
import org.bukkit.Particle;
import org.bukkit.ServerLinks;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.Statistic;
import org.bukkit.WeatherType;
import org.bukkit.WorldBorder;
import org.bukkit.BanList.Type;
import org.bukkit.advancement.Advancement;
import org.bukkit.advancement.AdvancementProgress;
import org.bukkit.ban.IpBanList;
import org.bukkit.ban.ProfileBanList;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.block.TileState;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.sign.Side;
import org.bukkit.configuration.serialization.DelegateDeserialization;
import org.bukkit.conversations.Conversation;
import org.bukkit.conversations.ConversationAbandonedEvent;
import org.bukkit.conversations.ManuallyAbandonedConversationCanceller;
import org.bukkit.craftbukkit.CraftEffect;
import org.bukkit.craftbukkit.CraftEquipmentSlot;
import org.bukkit.craftbukkit.CraftInput;
import org.bukkit.craftbukkit.CraftOfflinePlayer;
import org.bukkit.craftbukkit.CraftParticle;
import org.bukkit.craftbukkit.CraftServer;
import org.bukkit.craftbukkit.CraftServerLinks;
import org.bukkit.craftbukkit.CraftSound;
import org.bukkit.craftbukkit.CraftStatistic;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.craftbukkit.CraftWorldBorder;
import org.bukkit.craftbukkit.advancement.CraftAdvancement;
import org.bukkit.craftbukkit.advancement.CraftAdvancementProgress;
import org.bukkit.craftbukkit.block.CraftBlockEntityState;
import org.bukkit.craftbukkit.block.CraftBlockState;
import org.bukkit.craftbukkit.block.CraftSign;
import org.bukkit.craftbukkit.block.data.CraftBlockData;
import org.bukkit.craftbukkit.conversations.ConversationTracker;
import org.bukkit.craftbukkit.entity.CraftPlayer.1;
import org.bukkit.craftbukkit.entity.CraftPlayer.2;
import org.bukkit.craftbukkit.entity.CraftPlayer.ChunkSectionChanges;
import org.bukkit.craftbukkit.event.CraftEventFactory;
import org.bukkit.craftbukkit.inventory.CraftItemStack;
import org.bukkit.craftbukkit.map.CraftMapView;
import org.bukkit.craftbukkit.map.RenderData;
import org.bukkit.craftbukkit.map.CraftMapCursor.CraftType;
import org.bukkit.craftbukkit.potion.CraftPotionEffectType;
import org.bukkit.craftbukkit.potion.CraftPotionUtil;
import org.bukkit.craftbukkit.scoreboard.CraftScoreboard;
import org.bukkit.craftbukkit.util.CraftChatMessage;
import org.bukkit.craftbukkit.util.CraftLocation;
import org.bukkit.craftbukkit.util.CraftMagicNumbers;
import org.bukkit.craftbukkit.util.CraftNamespacedKey;
import org.bukkit.entity.EnderPearl;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.ExperienceOrb.SpawnReason;
import org.bukkit.entity.Player.Spigot;
import org.bukkit.event.player.PlayerHideEntityEvent;
import org.bukkit.event.player.PlayerItemMendEvent;
import org.bukkit.event.player.PlayerShowEntityEvent;
import org.bukkit.event.player.PlayerExpCooldownChangeEvent.ChangeReason;
import org.bukkit.event.player.PlayerKickEvent.Cause;
import org.bukkit.event.player.PlayerResourcePackStatusEvent.Status;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ItemType;
import org.bukkit.inventory.InventoryView.Property;
import org.bukkit.map.MapCursor;
import org.bukkit.map.MapView;
import org.bukkit.metadata.MetadataValue;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.messaging.StandardMessenger;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scoreboard.Scoreboard;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.spigotmc.AsyncCatcher;

@DelegateDeserialization(CraftOfflinePlayer.class)
public class CraftPlayer extends CraftHumanEntity implements Player, PluginMessageBridgeImpl {
   private static final Logger LOGGER = LogUtils.getClassLogger();
   private static final PointersSupplier<Player> POINTERS_SUPPLIER = (PointersSupplier<Player>)PointersSupplier.builder()
      .parent(CraftEntity.POINTERS_SUPPLIER)
      .resolving(Identity.NAME, Player::getName)
      .resolving(Identity.DISPLAY_NAME, Player::displayName)
      .resolving(Identity.LOCALE, Player::locale)
      .build();
   private long firstPlayed = 0L;
   private long lastPlayed = 0L;
   private boolean hasPlayedBefore = false;
   private final ConversationTracker conversationTracker = new ConversationTracker();
   private final Map<UUID, Set<WeakReference<Plugin>>> invertedVisibilityEntities = new HashMap<>();
   private final Set<UUID> unlistedEntities = new HashSet<>();
   private static final WeakHashMap<Plugin, WeakReference<Plugin>> pluginWeakReferences = new WeakHashMap<>();
   private int hash = 0;
   private double health = 20.0;
   private boolean scaledHealth = false;
   private double healthScale = 20.0;
   private CraftWorldBorder clientWorldBorder = null;
   private BorderChangeListener clientWorldBorderListener = this.createWorldBorderListener();
   private long lastSaveTime;
   private @Nullable CraftScoreboard scoreboardOverride;
   private Component playerListHeader;
   private Component playerListFooter;
   private @Nullable Set<BossBar> activeBossBars;
   private final Spigot spigot = new 2(this);

   public CraftPlayer(CraftServer server, ServerPlayer entity) {
      super(server, entity);
      this.firstPlayed = System.currentTimeMillis();
   }

   public ServerPlayer getHandle() {
      return (ServerPlayer)this.entity;
   }

   public boolean equals(Object obj) {
      if (obj == this) {
         return true;
      } else {
         return obj instanceof OfflinePlayer other ? this.getUniqueId().equals(other.getUniqueId()) : false;
      }
   }

   public int hashCode() {
      if (this.hash == 0 || this.hash == 485) {
         this.hash = 485 + this.getUniqueId().hashCode();
      }

      return this.hash;
   }

   public GameProfile getProfile() {
      return this.getHandle().getGameProfile();
   }

   public void remove() {
      if (this.getHandle().getClass().equals(ServerPlayer.class)) {
         throw new UnsupportedOperationException(String.format("Cannot remove player %s, use Player#kickPlayer(String) instead.", this.getName()));
      }

      super.remove();
   }

   public boolean isOp() {
      return this.server.getHandle().isOp(this.getHandle().nameAndId());
   }

   public void setOp(boolean value) {
      if (value != this.isOp()) {
         if (value) {
            this.server.getHandle().op(this.getHandle().nameAndId());
         } else {
            this.server.getHandle().deop(this.getHandle().nameAndId());
         }

         this.perm.recalculatePermissions();
      }
   }

   public boolean isOnline() {
      return this.server.getPlayer(this.getUniqueId()) != null;
   }

   public boolean isConnected() {
      return !this.getHandle().hasDisconnected();
   }

   public InetSocketAddress getAddress() {
      if (this.getHandle().connection == null) {
         return null;
      }

      SocketAddress addr = this.getHandle().connection.getRemoteAddress();
      return addr instanceof InetSocketAddress ? (InetSocketAddress)addr : null;
   }

   public @Nullable InetSocketAddress getHAProxyAddress() {
      if (this.getHandle().connection == null) {
         return null;
      } else {
         return this.getHandle().connection.connection.haProxyAddress instanceof InetSocketAddress inetSocketAddress ? inetSocketAddress : null;
      }
   }

   public boolean isTransferred() {
      return this.getHandle().connection.playerGameConnection.isTransferred();
   }

   public CompletableFuture<byte @Nullable []> retrieveCookie(NamespacedKey key) {
      return this.getHandle().connection.playerGameConnection.retrieveCookie(key);
   }

   public void storeCookie(NamespacedKey key, byte[] value) {
      this.getHandle().connection.playerGameConnection.storeCookie(key, value);
   }

   public void transfer(String host, int port) {
      this.getHandle().connection.playerGameConnection.transfer(host, port);
   }

   public int getProtocolVersion() {
      return this.getHandle().connection == null ? -1 : this.getHandle().connection.connection.protocolVersion;
   }

   public InetSocketAddress getVirtualHost() {
      return this.getHandle().connection == null ? null : this.getHandle().connection.connection.virtualHost;
   }

   public double getEyeHeight(boolean ignorePose) {
      return ignorePose ? 1.62 : this.getEyeHeight();
   }

   public void sendRawMessage(String message) {
      this.sendRawMessage(null, message);
   }

   public void sendRawMessage(UUID sender, String message) {
      Preconditions.checkArgument(message != null, "message cannot be null");
      if (this.getHandle().connection != null) {
         for (net.minecraft.network.chat.Component component : CraftChatMessage.fromString(message)) {
            this.getHandle().sendSystemMessage(component);
         }
      }
   }

   public void sendMessage(String message) {
      if (!this.conversationTracker.isConversingModaly()) {
         this.sendRawMessage(message);
      }
   }

   public void sendMessage(String... messages) {
      for (String message : messages) {
         this.sendMessage(message);
      }
   }

   public void sendMessage(UUID sender, String message) {
      if (!this.conversationTracker.isConversingModaly()) {
         this.sendRawMessage(sender, message);
      }
   }

   public void sendMessage(UUID sender, String... messages) {
      for (String message : messages) {
         this.sendMessage(sender, message);
      }
   }

   @Deprecated
   public void sendActionBar(BaseComponent[] message) {
      if (this.getHandle().connection != null && message != null) {
         ClientboundSetActionBarTextPacket packet = new ClientboundSetActionBarTextPacket(CraftChatMessage.bungeeToVanilla(message));
         this.getHandle().connection.send(packet);
      }
   }

   @Deprecated
   public void sendActionBar(String message) {
      if (this.getHandle().connection != null && message != null && !message.isEmpty()) {
         this.getHandle().connection.send(new ClientboundSetActionBarTextPacket(CraftChatMessage.fromStringOrNull(message)));
      }
   }

   @Deprecated
   public void sendActionBar(char alternateChar, String message) {
      if (message != null && !message.isEmpty()) {
         this.sendActionBar(ChatColor.translateAlternateColorCodes(alternateChar, message));
      }
   }

   public void setPlayerListHeaderFooter(BaseComponent @Nullable [] header, BaseComponent @Nullable [] footer) {
      if (header != null) {
         String headerJson = CraftChatMessage.bungeeToJson(header);
         this.playerListHeader = GsonComponentSerializer.gson().deserialize(headerJson);
      } else {
         this.playerListHeader = null;
      }

      if (footer != null) {
         String footerJson = CraftChatMessage.bungeeToJson(footer);
         this.playerListFooter = GsonComponentSerializer.gson().deserialize(footerJson);
      } else {
         this.playerListFooter = null;
      }

      this.updatePlayerListHeaderFooter();
   }

   public void setPlayerListHeaderFooter(@Nullable BaseComponent header, @Nullable BaseComponent footer) {
      this.setPlayerListHeaderFooter(header == null ? null : new BaseComponent[]{header}, footer == null ? null : new BaseComponent[]{footer});
   }

   public void setTitleTimes(int fadeInTicks, int stayTicks, int fadeOutTicks) {
      this.getHandle().connection.send(new ClientboundSetTitlesAnimationPacket(fadeInTicks, stayTicks, fadeOutTicks));
   }

   public void setSubtitle(BaseComponent[] subtitle) {
      ClientboundSetSubtitleTextPacket packet = new ClientboundSetSubtitleTextPacket(CraftChatMessage.bungeeToVanilla(subtitle));
      this.getHandle().connection.send(packet);
   }

   public void setSubtitle(BaseComponent subtitle) {
      this.setSubtitle(new BaseComponent[]{subtitle});
   }

   public void showTitle(BaseComponent[] title) {
      ClientboundSetTitleTextPacket packet = new ClientboundSetTitleTextPacket(CraftChatMessage.bungeeToVanilla(title));
      this.getHandle().connection.send(packet);
   }

   public void showTitle(BaseComponent title) {
      this.showTitle(new BaseComponent[]{title});
   }

   public void showTitle(BaseComponent[] title, BaseComponent[] subtitle, int fadeInTicks, int stayTicks, int fadeOutTicks) {
      this.setTitleTimes(fadeInTicks, stayTicks, fadeOutTicks);
      this.setSubtitle(subtitle);
      this.showTitle(title);
   }

   public void showTitle(BaseComponent title, BaseComponent subtitle, int fadeInTicks, int stayTicks, int fadeOutTicks) {
      this.setTitleTimes(fadeInTicks, stayTicks, fadeOutTicks);
      this.setSubtitle(subtitle);
      this.showTitle(title);
   }

   public void sendTitle(Title title) {
      Preconditions.checkNotNull(title, "Title is null");
      this.setTitleTimes(title.getFadeIn(), title.getStay(), title.getFadeOut());
      this.setSubtitle(title.getSubtitle() == null ? new BaseComponent[0] : title.getSubtitle());
      this.showTitle(title.getTitle());
   }

   public void updateTitle(Title title) {
      Preconditions.checkNotNull(title, "Title is null");
      this.setTitleTimes(title.getFadeIn(), title.getStay(), title.getFadeOut());
      if (title.getSubtitle() != null) {
         this.setSubtitle(title.getSubtitle());
      }

      this.showTitle(title.getTitle());
   }

   public void hideTitle() {
      this.getHandle().connection.send(new ClientboundClearTitlesPacket(false));
   }

   public String getDisplayName() {
      return DisplayNames.getLegacy(this);
   }

   public void setDisplayName(String name) {
      this.getHandle().adventure$displayName = name != null ? LegacyComponentSerializer.legacySection().deserialize(name) : Component.text(this.getName());
      this.getHandle().displayName = name == null ? this.getName() : name;
   }

   public void playerListName(Component name) {
      this.getHandle().listName = name == null ? null : PaperAdventure.asVanilla(name);
      if (this.getHandle().connection != null) {
         for (ServerPlayer player : this.server.getHandle().getPlayers()) {
            if (player.getBukkitEntity().canSee(this)) {
               player.connection.send(new ClientboundPlayerInfoUpdatePacket(ClientboundPlayerInfoUpdatePacket.Action.UPDATE_DISPLAY_NAME, this.getHandle()));
            }
         }
      }
   }

   public Component playerListName() {
      return (Component)(this.getHandle().listName == null ? Component.text(this.getName()) : PaperAdventure.asAdventure(this.getHandle().listName));
   }

   public Component playerListHeader() {
      return this.playerListHeader;
   }

   public Component playerListFooter() {
      return this.playerListFooter;
   }

   public String getPlayerListName() {
      return this.getHandle().listName == null ? this.getName() : CraftChatMessage.fromComponent(this.getHandle().listName);
   }

   public void setPlayerListName(String name) {
      if (name == null) {
         name = this.getName();
      }

      this.getHandle().listName = name.equals(this.getName()) ? null : CraftChatMessage.fromStringOrNull(name);
      if (this.getHandle().connection != null) {
         for (ServerPlayer player : this.server.getHandle().getPlayers()) {
            if (player.getBukkitEntity().canSee(this)) {
               player.connection.send(new ClientboundPlayerInfoUpdatePacket(ClientboundPlayerInfoUpdatePacket.Action.UPDATE_DISPLAY_NAME, this.getHandle()));
            }
         }
      }
   }

   public int getPlayerListOrder() {
      return this.getHandle().listOrder;
   }

   public void setPlayerListOrder(int order) {
      Preconditions.checkArgument(order >= 0, "order cannot be negative");
      this.getHandle().listOrder = order;
      if (this.getHandle().connection != null) {
         for (ServerPlayer player : this.server.getHandle().getPlayers()) {
            if (player.getBukkitEntity().canSee(this)) {
               player.connection.send(new ClientboundPlayerInfoUpdatePacket(ClientboundPlayerInfoUpdatePacket.Action.UPDATE_LIST_ORDER, this.getHandle()));
            }
         }
      }
   }

   public String getPlayerListHeader() {
      return this.playerListHeader == null ? null : LegacyComponentSerializer.legacySection().serialize(this.playerListHeader);
   }

   public String getPlayerListFooter() {
      return this.playerListFooter == null ? null : LegacyComponentSerializer.legacySection().serialize(this.playerListFooter);
   }

   public void setPlayerListHeader(String header) {
      this.playerListHeader = header == null ? null : LegacyComponentSerializer.legacySection().deserialize(header);
      this.updatePlayerListHeaderFooter();
   }

   public void setPlayerListFooter(String footer) {
      this.playerListFooter = footer == null ? null : LegacyComponentSerializer.legacySection().deserialize(footer);
      this.updatePlayerListHeaderFooter();
   }

   public void setPlayerListHeaderFooter(String header, String footer) {
      this.playerListHeader = header == null ? null : LegacyComponentSerializer.legacySection().deserialize(header);
      this.playerListFooter = footer == null ? null : LegacyComponentSerializer.legacySection().deserialize(footer);
      this.updatePlayerListHeaderFooter();
   }

   private void updatePlayerListHeaderFooter() {
      if (this.getHandle().connection != null) {
         ClientboundTabListPacket packet = new ClientboundTabListPacket(
            PaperAdventure.asVanillaNullToEmpty(this.playerListHeader), PaperAdventure.asVanillaNullToEmpty(this.playerListFooter)
         );
         this.getHandle().connection.send(packet);
      }
   }

   public void kickPlayer(String message) {
      AsyncCatcher.catchOp("player kick");
      this.getHandle().connection.disconnect(CraftChatMessage.fromStringOrEmpty(message, true), Cause.PLUGIN);
   }

   public void kick(Component message, Cause cause) {
      AsyncCatcher.catchOp("player kick");
      ServerGamePacketListenerImpl connection = this.getHandle().connection;
      if (connection != null) {
         connection.disconnect((Component)(message == null ? Component.empty() : message), cause);
      }
   }

   public <T> T getClientOption(ClientOption<T> type) {
      if (ClientOption.SKIN_PARTS == type) {
         return (T)type.getType()
            .cast(new PaperSkinParts((Byte)this.getHandle().getEntityData().get(net.minecraft.world.entity.player.Player.DATA_PLAYER_MODE_CUSTOMISATION)));
      } else if (ClientOption.CHAT_COLORS_ENABLED == type) {
         return (T)type.getType().cast(this.getHandle().canChatInColor());
      } else if (ClientOption.CHAT_VISIBILITY == type) {
         return (T)type.getType().cast(ChatVisibility.valueOf(this.getHandle().getChatVisibility().name()));
      } else if (ClientOption.LOCALE == type) {
         return (T)type.getType().cast(this.getLocale());
      } else if (ClientOption.MAIN_HAND == type) {
         return (T)type.getType().cast(this.getMainHand());
      } else if (ClientOption.VIEW_DISTANCE == type) {
         return (T)type.getType().cast(this.getClientViewDistance());
      } else if (ClientOption.TEXT_FILTERING_ENABLED == type) {
         return (T)type.getType().cast(this.getHandle().isTextFilteringEnabled());
      } else if (ClientOption.ALLOW_SERVER_LISTINGS == type) {
         return (T)type.getType().cast(this.getHandle().allowsListing());
      } else if (ClientOption.PARTICLE_VISIBILITY == type) {
         return (T)type.getType().cast(ParticleVisibility.valueOf(this.getHandle().particleStatus.name()));
      } else {
         throw new RuntimeException("Unknown settings type");
      }
   }

   public void sendOpLevel(byte level) {
      Preconditions.checkArgument(
         level >= PermissionLevel.ALL.id() && level <= PermissionLevel.OWNERS.id(),
         "Level must be within [%s, %s]",
         PermissionLevel.ALL.id(),
         PermissionLevel.OWNERS.id()
      );
      this.server.getServer().getPlayerList().sendPlayerPermissionLevel(this.getHandle(), LevelBasedPermissionSet.forLevel(PermissionLevel.byId(level)), false);
   }

   public void addAdditionalChatCompletions(@NonNull Collection<String> completions) {
      this.getHandle().connection.send(new ClientboundCustomChatCompletionsPacket(Action.ADD, new ArrayList<>(completions)));
   }

   public void removeAdditionalChatCompletions(@NonNull Collection<String> completions) {
      this.getHandle().connection.send(new ClientboundCustomChatCompletionsPacket(Action.REMOVE, new ArrayList<>(completions)));
   }

   public void setCompassTarget(Location loc) {
      Preconditions.checkArgument(loc != null, "Location cannot be null");
      if (this.getHandle().connection != null) {
         this.getHandle()
            .connection
            .send(
               new ClientboundSetDefaultSpawnPositionPacket(
                  RespawnData.of(((CraftWorld)loc.getWorld()).getHandle().dimension(), CraftLocation.toBlockPos(loc), loc.getYaw(), loc.getPitch())
               )
            );
      }
   }

   public Location getCompassTarget() {
      return this.getHandle().compassTarget;
   }

   public void chat(String msg) {
      Preconditions.checkArgument(msg != null, "msg cannot be null");
      if (this.getHandle().connection != null) {
         if (ServerGamePacketListenerImpl.isChatMessageIllegal(msg)) {
            this.getHandle()
               .connection
               .disconnect(net.minecraft.network.chat.Component.translatable("multiplayer.disconnect.illegal_characters"), Cause.ILLEGAL_CHARACTERS);
         } else if (msg.startsWith("/")) {
            this.getHandle().connection.handleCommand(msg);
         } else {
            PlayerChatMessage playerChatMessage = PlayerChatMessage.system(msg).withUnsignedContent(net.minecraft.network.chat.Component.literal(msg));
            this.getHandle().connection.chat(msg, playerChatMessage, false);
         }
      }
   }

   public boolean performCommand(String command) {
      Preconditions.checkArgument(command != null, "command cannot be null");
      return this.server.dispatchCommand(this, command);
   }

   public void playNote(Location loc, Instrument instrument, Note note) {
      Preconditions.checkArgument(loc != null, "Location cannot be null");
      Preconditions.checkArgument(instrument != null, "Instrument cannot be null");
      Preconditions.checkArgument(note != null, "Note cannot be null");
      if (this.getHandle().connection != null) {
         Sound instrumentSound = instrument.getSound();
         if (instrumentSound != null) {
            NoteBlockInstrument noteBlockInstrument = (NoteBlockInstrument)CraftBlockData.toVanilla(instrument, NoteBlockInstrument.class);
            float pitch = noteBlockInstrument.isTunable() ? note.getPitch() : 1.0F;
            this.getHandle()
               .connection
               .send(
                  new ClientboundSoundPacket(
                     CraftSound.bukkitToMinecraftHolder(instrumentSound),
                     SoundSource.RECORDS,
                     loc.getBlockX(),
                     loc.getBlockY(),
                     loc.getBlockZ(),
                     3.0F,
                     pitch,
                     this.getHandle().getRandom().nextLong()
                  )
               );
         }
      }
   }

   public void playSound(Location loc, Sound sound, SoundCategory category, float volume, float pitch) {
      this.playSound(loc, sound, category, volume, pitch, this.getHandle().getRandom().nextLong());
   }

   public void playSound(Location loc, String sound, SoundCategory category, float volume, float pitch) {
      this.playSound(loc, sound, category, volume, pitch, this.getHandle().getRandom().nextLong());
   }

   public void playSound(Location loc, Sound sound, SoundCategory category, float volume, float pitch, long seed) {
      if (loc != null && sound != null && category != null && this.getHandle().connection != null) {
         this.playSound0(loc, CraftSound.bukkitToMinecraftHolder(sound), SoundSource.valueOf(category.name()), volume, pitch, seed);
      }
   }

   public void playSound(Location loc, String sound, SoundCategory category, float volume, float pitch, long seed) {
      if (loc != null && sound != null && category != null && this.getHandle().connection != null) {
         this.playSound0(
            loc, Holder.direct(SoundEvent.createVariableRangeEvent(Identifier.parse(sound))), SoundSource.valueOf(category.name()), volume, pitch, seed
         );
      }
   }

   private void playSound0(Location loc, Holder<SoundEvent> soundEffectHolder, SoundSource categoryNMS, float volume, float pitch, long seed) {
      Preconditions.checkArgument(loc != null, "Location cannot be null");
      if (this.getHandle().connection != null) {
         ClientboundSoundPacket packet = new ClientboundSoundPacket(soundEffectHolder, categoryNMS, loc.getX(), loc.getY(), loc.getZ(), volume, pitch, seed);
         this.getHandle().connection.send(packet);
      }
   }

   public void playSound(Entity entity, Sound sound, SoundCategory category, float volume, float pitch) {
      this.playSound(entity, sound, category, volume, pitch, this.getHandle().getRandom().nextLong());
   }

   public void playSound(Entity entity, String sound, SoundCategory category, float volume, float pitch) {
      this.playSound(entity, sound, category, volume, pitch, this.getHandle().getRandom().nextLong());
   }

   public void playSound(Entity entity, Sound sound, SoundCategory category, float volume, float pitch, long seed) {
      if (entity instanceof CraftEntity && sound != null && category != null && this.getHandle().connection != null) {
         this.playSound0(entity, CraftSound.bukkitToMinecraftHolder(sound), SoundSource.valueOf(category.name()), volume, pitch, seed);
      }
   }

   public void playSound(Entity entity, String sound, SoundCategory category, float volume, float pitch, long seed) {
      if (entity instanceof CraftEntity && sound != null && category != null && this.getHandle().connection != null) {
         this.playSound0(
            entity, Holder.direct(SoundEvent.createVariableRangeEvent(Identifier.parse(sound))), SoundSource.valueOf(category.name()), volume, pitch, seed
         );
      }
   }

   private void playSound0(Entity entity, Holder<SoundEvent> soundEffectHolder, SoundSource categoryNMS, float volume, float pitch, long seed) {
      Preconditions.checkArgument(entity != null, "Entity cannot be null");
      Preconditions.checkArgument(soundEffectHolder != null, "Holder of SoundEffect cannot be null");
      Preconditions.checkArgument(categoryNMS != null, "SoundCategory cannot be null");
      if (this.getHandle().connection != null) {
         if (entity instanceof CraftEntity craftEntity) {
            ClientboundSoundEntityPacket packet = new ClientboundSoundEntityPacket(soundEffectHolder, categoryNMS, craftEntity.getHandle(), volume, pitch, seed);
            this.getHandle().connection.send(packet);
         }
      }
   }

   public void stopSound(String sound, SoundCategory category) {
      if (this.getHandle().connection != null) {
         this.getHandle()
            .connection
            .send(new ClientboundStopSoundPacket(Identifier.parse(sound), category == null ? SoundSource.MASTER : SoundSource.valueOf(category.name())));
      }
   }

   public void stopSound(SoundCategory category) {
      if (this.getHandle().connection != null) {
         this.getHandle().connection.send(new ClientboundStopSoundPacket(null, SoundSource.valueOf(category.name())));
      }
   }

   public void stopAllSounds() {
      if (this.getHandle().connection != null) {
         this.getHandle().connection.send(new ClientboundStopSoundPacket(null, null));
      }
   }

   public void playEffect(Location loc, Effect effect, int data) {
      Preconditions.checkArgument(effect != null, "Effect cannot be null");
      Preconditions.checkArgument(loc != null, "Location cannot be null");
      if (this.getHandle().connection != null) {
         int packetData = effect.getId();
         ClientboundLevelEventPacket packet = new ClientboundLevelEventPacket(packetData, CraftLocation.toBlockPos(loc), data, false);
         this.getHandle().connection.send(packet);
      }
   }

   public <T> void playEffect(Location loc, Effect effect, T data) {
      Preconditions.checkArgument(effect != null, "Effect cannot be null");
      if (data != null) {
         Preconditions.checkArgument(effect.getData() != null, "Effect.%s does not have a valid data", effect.name());
         Preconditions.checkArgument(effect.isApplicable(data), "%s data cannot be used for the %s effect", data.getClass().getName(), effect);
      } else {
         Preconditions.checkArgument(effect.getData() == null || effect == Effect.ELECTRIC_SPARK, "Wrong kind of data for the %s effect", effect);
      }

      int dataValue = CraftEffect.getDataValue(effect, data, this.getHandle().registryAccess());
      this.playEffect(loc, effect, dataValue);
   }

   public boolean breakBlock(Block block) {
      Preconditions.checkArgument(block != null, "Block cannot be null");
      Preconditions.checkArgument(block.getWorld().equals(this.getWorld()), "Cannot break blocks across worlds");
      return this.getHandle().gameMode.destroyBlock(new BlockPos(block.getX(), block.getY(), block.getZ()));
   }

   public void sendBlockChange(Location loc, Material material, byte data) {
      if (this.getHandle().connection != null) {
         ClientboundBlockUpdatePacket packet = new ClientboundBlockUpdatePacket(CraftLocation.toBlockPos(loc), CraftMagicNumbers.getBlock(material, data));
         this.getHandle().connection.send(packet);
      }
   }

   public void sendBlockChange(Location loc, BlockData block) {
      if (this.getHandle().connection != null) {
         ClientboundBlockUpdatePacket packet = new ClientboundBlockUpdatePacket(CraftLocation.toBlockPos(loc), ((CraftBlockData)block).getState());
         this.getHandle().connection.send(packet);
      }
   }

   public void sendMultiBlockChange(Map<? extends Position, BlockData> blockChanges) {
      if (this.getHandle().connection != null) {
         Map<SectionPos, Short2ObjectMap<BlockState>> sectionMap = new HashMap<>();

         for (Entry<? extends Position, BlockData> entry : blockChanges.entrySet()) {
            BlockData blockData = entry.getValue();
            BlockPos blockPos = MCUtil.toBlockPos(entry.getKey());
            SectionPos sectionPos = SectionPos.of(blockPos);
            Short2ObjectMap<BlockState> sectionData = sectionMap.computeIfAbsent(sectionPos, key -> new Short2ObjectArrayMap());
            sectionData.put(SectionPos.sectionRelativePos(blockPos), ((CraftBlockData)blockData).getState());
         }

         for (Entry<SectionPos, Short2ObjectMap<BlockState>> entry : sectionMap.entrySet()) {
            SectionPos sectionPos = entry.getKey();
            Short2ObjectMap<BlockState> blockData = entry.getValue();
            ClientboundSectionBlocksUpdatePacket packet = new ClientboundSectionBlocksUpdatePacket(sectionPos, blockData);
            this.getHandle().connection.send(packet);
         }
      }
   }

   public void sendBlockChanges(Collection<org.bukkit.block.BlockState> blocks) {
      Preconditions.checkArgument(blocks != null, "blocks must not be null");
      if (this.getHandle().connection != null && !blocks.isEmpty()) {
         Map<SectionPos, ChunkSectionChanges> changes = new HashMap<>();

         for (org.bukkit.block.BlockState state : blocks) {
            CraftBlockState cstate = (CraftBlockState)state;
            BlockPos pos = cstate.getPosition();
            SectionPos sectionPosition = SectionPos.of(pos);
            ChunkSectionChanges sectionChanges = changes.computeIfAbsent(sectionPosition, ignore -> new ChunkSectionChanges());
            sectionChanges.positions().add(SectionPos.sectionRelativePos(pos));
            sectionChanges.blockData().add(cstate.getHandle());
         }

         for (Entry<SectionPos, ChunkSectionChanges> entry : changes.entrySet()) {
            ChunkSectionChanges chunkChanges = entry.getValue();
            ClientboundSectionBlocksUpdatePacket packet = new ClientboundSectionBlocksUpdatePacket(
               entry.getKey(), chunkChanges.positions(), chunkChanges.blockData().toArray(BlockState[]::new)
            );
            this.getHandle().connection.send(packet);
         }
      }
   }

   public void sendBlockDamage(Location loc, float progress, Entity source) {
      Preconditions.checkArgument(source != null, "source must not be null");
      this.sendBlockDamage(loc, progress, source.getEntityId());
   }

   public void sendBlockDamage(Location loc, float progress, int sourceId) {
      Preconditions.checkArgument(loc != null, "loc must not be null");
      Preconditions.checkArgument(progress >= 0.0 && progress <= 1.0, "progress must be between 0.0 and 1.0 (inclusive)");
      if (this.getHandle().connection != null) {
         int stage = (int)(9.0F * progress);
         if (progress == 0.0F) {
            stage = -1;
         }

         ClientboundBlockDestructionPacket packet = new ClientboundBlockDestructionPacket(sourceId, CraftLocation.toBlockPos(loc), stage);
         this.getHandle().connection.send(packet);
      }
   }

   public void sendSignChange(Location loc, @Nullable List<? extends Component> lines, DyeColor dyeColor, boolean hasGlowingText) {
      if (this.getHandle().connection != null) {
         if (lines == null) {
            lines = new ArrayList<>(4);
         }

         Preconditions.checkArgument(loc != null, "Location cannot be null");
         Preconditions.checkArgument(dyeColor != null, "DyeColor cannot be null");
         if (lines.size() < 4) {
            throw new IllegalArgumentException("Must have at least 4 lines");
         }

         net.minecraft.network.chat.Component[] components = CraftSign.sanitizeLines(lines);
         this.sendSignChange0(components, loc, dyeColor, hasGlowingText);
      }
   }

   public void sendSignChange(Location loc, @Nullable String @Nullable [] lines) {
      this.sendSignChange(loc, lines, DyeColor.BLACK);
   }

   public void sendSignChange(Location loc, @Nullable String @Nullable [] lines, DyeColor dyeColor) {
      this.sendSignChange(loc, lines, dyeColor, false);
   }

   public void sendSignChange(Location loc, @Nullable String @Nullable [] lines, DyeColor dyeColor, boolean hasGlowingText) {
      Preconditions.checkArgument(loc != null, "Location cannot be null");
      Preconditions.checkArgument(dyeColor != null, "DyeColor cannot be null");
      if (lines == null) {
         lines = new String[4];
      }

      Preconditions.checkArgument(lines.length >= 4, "Must have at least 4 lines (%s)", lines.length);
      if (this.getHandle().connection != null) {
         net.minecraft.network.chat.Component[] components = CraftSign.sanitizeLines(lines);
         this.sendSignChange0(components, loc, dyeColor, hasGlowingText);
      }
   }

   private void sendSignChange0(net.minecraft.network.chat.Component[] components, Location loc, DyeColor dyeColor, boolean hasGlowingText) {
      SignBlockEntity sign = new SignBlockEntity(CraftLocation.toBlockPos(loc), Blocks.OAK_SIGN.defaultBlockState());
      SignText text = sign.getFrontText();
      text = text.setColor(net.minecraft.world.item.DyeColor.byId(dyeColor.getWoolData()));
      text = text.setHasGlowingText(hasGlowingText);

      for (int i = 0; i < components.length; i++) {
         text = text.setMessage(i, components[i]);
      }

      sign.setText(text, true);
      this.getHandle()
         .connection
         .send(new ClientboundBlockEntityDataPacket(sign.getBlockPos(), sign.getType(), sign.getUpdateTag(this.getHandle().registryAccess())));
   }

   public void sendBlockUpdate(@NonNull Location location, @NonNull TileState tileState) throws IllegalArgumentException {
      Preconditions.checkArgument(location != null, "Location can not be null");
      Preconditions.checkArgument(tileState != null, "TileState can not be null");
      if (this.getHandle().connection != null) {
         CraftBlockEntityState<?> craftState = (CraftBlockEntityState<?>)tileState;
         this.getHandle().connection.send(craftState.getUpdatePacket(location));
      }
   }

   public void sendEquipmentChange(LivingEntity entity, EquipmentSlot slot, @Nullable ItemStack item) {
      this.sendEquipmentChange(entity, Collections.singletonMap(slot, item));
   }

   public void sendEquipmentChange(LivingEntity entity, Map<EquipmentSlot, @Nullable ItemStack> items) {
      Preconditions.checkArgument(entity != null, "Entity cannot be null");
      Preconditions.checkArgument(items != null, "items cannot be null");
      Preconditions.checkArgument(!items.isEmpty(), "items cannot be empty");
      if (this.getHandle().connection != null) {
         List<Pair<net.minecraft.world.entity.EquipmentSlot, net.minecraft.world.item.ItemStack>> equipment = new ArrayList<>(items.size());

         for (Entry<EquipmentSlot, ItemStack> entry : items.entrySet()) {
            EquipmentSlot slot = entry.getKey();
            Preconditions.checkArgument(slot != null, "Cannot set null EquipmentSlot");
            equipment.add(new Pair(CraftEquipmentSlot.getNMS(slot), CraftItemStack.asNMSCopy(entry.getValue())));
         }

         this.getHandle().connection.send(new ClientboundSetEquipmentPacket(entity.getEntityId(), equipment));
      }
   }

   public void sendPotionEffectChange(LivingEntity entity, PotionEffect effect) {
      Preconditions.checkArgument(entity != null, "Entity cannot be null");
      Preconditions.checkArgument(effect != null, "Effect cannot be null");
      if (this.getHandle().connection != null) {
         this.getHandle().connection.send(new ClientboundUpdateMobEffectPacket(entity.getEntityId(), CraftPotionUtil.fromBukkit(effect), true));
      }
   }

   public void sendPotionEffectChangeRemove(LivingEntity entity, PotionEffectType type) {
      Preconditions.checkArgument(entity != null, "Entity cannot be null");
      Preconditions.checkArgument(type != null, "Type cannot be null");
      if (this.getHandle().connection != null) {
         this.getHandle().connection.send(new ClientboundRemoveMobEffectPacket(entity.getEntityId(), CraftPotionEffectType.bukkitToMinecraftHolder(type)));
      }
   }

   public WorldBorder getWorldBorder() {
      return this.clientWorldBorder;
   }

   public void setWorldBorder(WorldBorder border) {
      CraftWorldBorder craftBorder = (CraftWorldBorder)border;
      if (border != null && !craftBorder.isVirtual() && !craftBorder.getWorld().equals(this.getWorld())) {
         throw new UnsupportedOperationException("Cannot set player world border to that of another world");
      }

      if (this.clientWorldBorder != null) {
         this.clientWorldBorder.getHandle().removeListener(this.clientWorldBorderListener);
      }

      net.minecraft.world.level.border.WorldBorder newWorldBorder;
      if (craftBorder != null && craftBorder.isVirtual()) {
         this.clientWorldBorder = craftBorder;
         this.clientWorldBorder.getHandle().addListener(this.clientWorldBorderListener);
         newWorldBorder = this.clientWorldBorder.getHandle();
      } else {
         this.clientWorldBorder = null;
         newWorldBorder = ((CraftWorldBorder)this.getWorld().getWorldBorder()).getHandle();
      }

      ServerGamePacketListenerImpl connection = this.getHandle().connection;
      connection.send(new ClientboundSetBorderSizePacket(newWorldBorder));
      connection.send(new ClientboundSetBorderLerpSizePacket(newWorldBorder));
      connection.send(new ClientboundSetBorderCenterPacket(newWorldBorder));
      connection.send(new ClientboundSetBorderWarningDelayPacket(newWorldBorder));
      connection.send(new ClientboundSetBorderWarningDistancePacket(newWorldBorder));
   }

   private BorderChangeListener createWorldBorderListener() {
      return new 1(this);
   }

   public boolean hasClientWorldBorder() {
      return this.clientWorldBorder != null;
   }

   public void sendMap(MapView map) {
      if (this.getHandle().connection != null) {
         RenderData data = ((CraftMapView)map).render(this);
         Collection<MapDecoration> icons = new ArrayList<>();

         for (MapCursor cursor : data.cursors) {
            if (cursor.isVisible()) {
               icons.add(
                  new MapDecoration(
                     CraftType.bukkitToMinecraftHolder(cursor.getType()),
                     cursor.getX(),
                     cursor.getY(),
                     cursor.getDirection(),
                     CraftChatMessage.fromStringOrOptional(cursor.getCaption())
                  )
               );
            }
         }

         ClientboundMapItemDataPacket packet = new ClientboundMapItemDataPacket(
            new MapId(map.getId()), map.getScale().getValue(), map.isLocked(), icons, new MapPatch(0, 0, 128, 128, data.buffer)
         );
         this.getHandle().connection.send(packet);
      }
   }

   public void sendHurtAnimation(float yaw) {
      this.sendHurtAnimation(yaw, this);
   }

   public void sendHurtAnimation(float yaw, Entity target) {
      if (this.getHandle().connection != null) {
         float actualYaw = yaw + 90.0F;
         this.getHandle().connection.send(new ClientboundHurtAnimationPacket(target.getEntityId(), actualYaw));
      }
   }

   public void sendLinks(ServerLinks links) {
      if (this.getHandle().connection != null) {
         Preconditions.checkArgument(links != null, "links cannot be null");
         net.minecraft.server.ServerLinks nms = ((CraftServerLinks)links).getServerLinks();
         this.getHandle().connection.send(new ClientboundServerLinksPacket(nms.untrust()));
      }
   }

   public void addCustomChatCompletions(Collection<String> completions) {
      this.sendCustomChatCompletionPacket(completions, Action.ADD);
   }

   public void removeCustomChatCompletions(Collection<String> completions) {
      this.sendCustomChatCompletionPacket(completions, Action.REMOVE);
   }

   public void setCustomChatCompletions(Collection<String> completions) {
      this.sendCustomChatCompletionPacket(completions, Action.SET);
   }

   private void sendCustomChatCompletionPacket(Collection<String> completions, Action action) {
      if (this.getHandle().connection != null) {
         ClientboundCustomChatCompletionsPacket packet = new ClientboundCustomChatCompletionsPacket(action, new ArrayList<>(completions));
         this.getHandle().connection.send(packet);
      }
   }

   public void showWinScreen() {
      if (this.getHandle().connection != null) {
         ClientboundGameEventPacket packet = new ClientboundGameEventPacket(ClientboundGameEventPacket.WIN_GAME, 1.0F);
         this.getHandle().connection.send(packet);
      }
   }

   public boolean hasSeenWinScreen() {
      return this.getHandle().seenCredits;
   }

   public void setHasSeenWinScreen(boolean hasSeenWinScreen) {
      this.getHandle().seenCredits = hasSeenWinScreen;
   }

   public void setRotation(float yaw, float pitch) {
      if (this.getHandle().connection != null) {
         super.setRotation(yaw, pitch);
      }
   }

   public void setRotation(@NonNull Angle yaw, @NonNull Angle pitch) {
      if (this.getHandle().connection != null) {
         super.setRotation(yaw, pitch);
      }
   }

   public void lookAt(@NonNull Entity entity, @NonNull LookAnchor playerAnchor, @NonNull LookAnchor entityAnchor) {
      this.getHandle().lookAt(toNmsAnchor(playerAnchor), ((CraftEntity)entity).getHandle(), toNmsAnchor(entityAnchor));
   }

   protected boolean teleport0(Location location, TeleportCause cause, TeleportFlag... flags) {
      if (this.getHandle().connection == null) {
         return false;
      } else {
         return location.getWorld() != this.getWorld() && this.getHandle().isVehicle() ? false : super.teleport0(location, cause, flags);
      }
   }

   public void setSneaking(boolean sneak) {
      this.getHandle().setShiftKeyDown(sneak);
   }

   public boolean isSneaking() {
      return this.getHandle().isShiftKeyDown();
   }

   public boolean isSprinting() {
      return this.getHandle().isSprinting();
   }

   public void setSprinting(boolean sprinting) {
      this.getHandle().setSprinting(sprinting);
   }

   public void loadData() {
      this.server
         .getHandle()
         .playerIo
         .load(this.getHandle().nameAndId())
         .map(tag -> TagValueInput.create(ProblemReporter.DISCARDING, this.server.getServer().registryAccess(), tag))
         .ifPresent(this.getHandle()::load);
   }

   public void saveData() {
      this.server.getHandle().playerIo.save(this.getHandle());
   }

   @Deprecated
   public void updateInventory() {
      this.getHandle().containerMenu.sendAllDataToRemote();
   }

   public void setSleepingIgnored(boolean isSleeping) {
      this.getHandle().fauxSleeping = isSleeping;
      ((CraftWorld)this.getWorld()).getHandle().updateSleepingPlayerList();
   }

   public boolean isSleepingIgnored() {
      return this.getHandle().fauxSleeping;
   }

   public Location getRespawnLocation(boolean loadLocationAndValidate) {
      ServerPlayer.RespawnConfig respawnConfig = this.getHandle().getRespawnConfig();
      if (respawnConfig == null) {
         return null;
      } else {
         RespawnData respawnData = respawnConfig.respawnData();
         ServerLevel world = this.server.getServer().getLevel(respawnData.dimension());
         if (world == null) {
            return null;
         } else {
            return !loadLocationAndValidate
               ? CraftLocation.toBukkit(respawnData.pos(), world, respawnData.yaw(), respawnData.pitch())
               : ServerPlayer.findRespawnAndUseSpawnBlock(world, respawnConfig, false)
                  .map(pos -> CraftLocation.toBukkit(pos.position(), world, pos.yaw(), pos.pitch()))
                  .orElse(null);
         }
      }
   }

   public void setRespawnLocation(Location location, boolean override) {
      if (location == null) {
         this.getHandle().setRespawnPosition(null, false, com.destroystokyo.paper.event.player.PlayerSetSpawnEvent.Cause.PLUGIN);
      } else {
         this.getHandle()
            .setRespawnPosition(
               new ServerPlayer.RespawnConfig(
                  RespawnData.of(
                     ((CraftWorld)location.getWorld()).getHandle().dimension(), CraftLocation.toBlockPos(location), location.getYaw(), location.getPitch()
                  ),
                  override
               ),
               false,
               com.destroystokyo.paper.event.player.PlayerSetSpawnEvent.Cause.PLUGIN
            );
      }
   }

   public Collection<EnderPearl> getEnderPearls() {
      return this.getHandle().getEnderPearls().stream().map(e -> (EnderPearl)e.getBukkitEntity()).collect(Collectors.toList());
   }

   public Input getCurrentInput() {
      return new CraftInput(this.getHandle().getLastClientInput());
   }

   public Location getBedLocation() {
      Preconditions.checkState(this.isSleeping(), "Not sleeping");
      BlockPos bed = this.getHandle().getRespawnConfig().respawnData().pos();
      return CraftLocation.toBukkit(bed, this.getWorld());
   }

   public boolean hasDiscoveredRecipe(NamespacedKey recipe) {
      Preconditions.checkArgument(recipe != null, "recipe cannot be null");
      return this.getHandle().getRecipeBook().contains(CraftNamespacedKey.toResourceKey(Registries.RECIPE, recipe));
   }

   public Set<NamespacedKey> getDiscoveredRecipes() {
      Builder<NamespacedKey> bukkitRecipeKeys = ImmutableSet.builder();
      this.getHandle().getRecipeBook().known.forEach(key -> bukkitRecipeKeys.add(CraftNamespacedKey.fromMinecraft(key.identifier())));
      return bukkitRecipeKeys.build();
   }

   public void incrementStatistic(Statistic statistic) {
      CraftStatistic.incrementStatistic(this.getHandle().getStats(), statistic, this.getHandle());
   }

   public void decrementStatistic(Statistic statistic) {
      CraftStatistic.decrementStatistic(this.getHandle().getStats(), statistic, this.getHandle());
   }

   public int getStatistic(Statistic statistic) {
      return CraftStatistic.getStatistic(this.getHandle().getStats(), statistic);
   }

   public void incrementStatistic(Statistic statistic, int amount) {
      CraftStatistic.incrementStatistic(this.getHandle().getStats(), statistic, amount, this.getHandle());
   }

   public void decrementStatistic(Statistic statistic, int amount) {
      CraftStatistic.decrementStatistic(this.getHandle().getStats(), statistic, amount, this.getHandle());
   }

   public void setStatistic(Statistic statistic, int newValue) {
      CraftStatistic.setStatistic(this.getHandle().getStats(), statistic, newValue, this.getHandle());
   }

   public void incrementStatistic(Statistic statistic, Material material) {
      CraftStatistic.incrementStatistic(this.getHandle().getStats(), statistic, material, this.getHandle());
   }

   public void decrementStatistic(Statistic statistic, Material material) {
      CraftStatistic.decrementStatistic(this.getHandle().getStats(), statistic, material, this.getHandle());
   }

   public int getStatistic(Statistic statistic, Material material) {
      return CraftStatistic.getStatistic(this.getHandle().getStats(), statistic, material);
   }

   public void incrementStatistic(Statistic statistic, Material material, int amount) {
      CraftStatistic.incrementStatistic(this.getHandle().getStats(), statistic, material, amount, this.getHandle());
   }

   public void decrementStatistic(Statistic statistic, Material material, int amount) {
      CraftStatistic.decrementStatistic(this.getHandle().getStats(), statistic, material, amount, this.getHandle());
   }

   public void setStatistic(Statistic statistic, Material material, int newValue) {
      CraftStatistic.setStatistic(this.getHandle().getStats(), statistic, material, newValue, this.getHandle());
   }

   public void incrementStatistic(Statistic statistic, EntityType entityType) {
      CraftStatistic.incrementStatistic(this.getHandle().getStats(), statistic, entityType, this.getHandle());
   }

   public void decrementStatistic(Statistic statistic, EntityType entityType) {
      CraftStatistic.decrementStatistic(this.getHandle().getStats(), statistic, entityType, this.getHandle());
   }

   public int getStatistic(Statistic statistic, EntityType entityType) {
      return CraftStatistic.getStatistic(this.getHandle().getStats(), statistic, entityType);
   }

   public void incrementStatistic(Statistic statistic, EntityType entityType, int amount) {
      CraftStatistic.incrementStatistic(this.getHandle().getStats(), statistic, entityType, amount, this.getHandle());
   }

   public void decrementStatistic(Statistic statistic, EntityType entityType, int amount) {
      CraftStatistic.decrementStatistic(this.getHandle().getStats(), statistic, entityType, amount, this.getHandle());
   }

   public void setStatistic(Statistic statistic, EntityType entityType, int newValue) {
      CraftStatistic.setStatistic(this.getHandle().getStats(), statistic, entityType, newValue, this.getHandle());
   }

   public void setPlayerTime(long time, boolean tickTime) {
      this.getHandle().timeOffset = time;
      this.getHandle().relativeTime = tickTime;
      ServerLevel level = this.getHandle().level();
      if (this.getHandle().connection != null && !level.dimensionType().defaultClock().isEmpty()) {
         this.getHandle().connection.send(level.clockManager().createFullSyncPacket(this.getHandle()));
      }
   }

   public long getPlayerTimeOffset() {
      return this.getHandle().timeOffset;
   }

   public long getPlayerTime() {
      return this.getHandle().getPlayerTime();
   }

   public boolean isPlayerTimeRelative() {
      return this.getHandle().relativeTime;
   }

   public void resetPlayerTime() {
      this.setPlayerTime(0L, true);
   }

   public void setPlayerWeather(WeatherType type) {
      this.getHandle().setPlayerWeather(type, true);
   }

   public WeatherType getPlayerWeather() {
      return this.getHandle().weatherType;
   }

   public int getExpCooldown() {
      return this.getHandle().takeXpDelay;
   }

   public void setExpCooldown(int ticks) {
      this.getHandle().takeXpDelay = CraftEventFactory.callPlayerXpCooldownEvent(this.getHandle(), ticks, ChangeReason.PLUGIN).getNewCooldown();
   }

   public void resetPlayerWeather() {
      this.getHandle().resetPlayerWeather();
   }

   public boolean isBanned() {
      return ((ProfileBanList)this.server.getBanList(Type.PROFILE)).isBanned(this.getPlayerProfile());
   }

   public BanEntry<PlayerProfile> ban(String reason, Date expires, String source) {
      return this.ban(reason, expires, source, true);
   }

   public BanEntry<PlayerProfile> ban(String reason, Instant expires, String source) {
      return this.ban(reason, expires != null ? Date.from(expires) : null, source);
   }

   public BanEntry<PlayerProfile> ban(String reason, Duration duration, String source) {
      return this.ban(reason, duration != null ? Instant.now().plus(duration) : null, source);
   }

   public BanEntry<PlayerProfile> ban(String reason, Date expires, String source, boolean kickPlayer) {
      BanEntry<PlayerProfile> banEntry = ((ProfileBanList)this.server.getBanList(Type.PROFILE)).addBan(this.getPlayerProfile(), reason, expires, source);
      if (kickPlayer) {
         this.kickPlayer(reason);
      }

      return banEntry;
   }

   public BanEntry<PlayerProfile> ban(String reason, Instant instant, String source, boolean kickPlayer) {
      return this.ban(reason, instant != null ? Date.from(instant) : null, source, kickPlayer);
   }

   public BanEntry<PlayerProfile> ban(String reason, Duration duration, String source, boolean kickPlayer) {
      return this.ban(reason, duration != null ? Instant.now().plus(duration) : null, source, kickPlayer);
   }

   public BanEntry<InetAddress> banIp(String reason, Date expires, String source, boolean kickPlayer) {
      Preconditions.checkArgument(this.getAddress() != null, "The Address of this Player is null");
      BanEntry<InetAddress> banEntry = ((IpBanList)this.server.getBanList(Type.IP)).addBan(this.getAddress().getAddress(), reason, expires, source);
      if (kickPlayer) {
         this.kickPlayer(reason);
      }

      return banEntry;
   }

   public BanEntry<InetAddress> banIp(String reason, Instant instant, String source, boolean kickPlayer) {
      return this.banIp(reason, instant != null ? Date.from(instant) : null, source, kickPlayer);
   }

   public BanEntry<InetAddress> banIp(String reason, Duration duration, String source, boolean kickPlayer) {
      return this.banIp(reason, duration != null ? Instant.now().plus(duration) : null, source, kickPlayer);
   }

   public boolean isWhitelisted() {
      return this.server.getHandle().getWhiteList().isWhiteListed(this.getHandle().nameAndId());
   }

   public void setWhitelisted(boolean value) {
      if (value) {
         this.server.getHandle().getWhiteList().add(new UserWhiteListEntry(this.getHandle().nameAndId()));
      } else {
         this.server.getHandle().getWhiteList().remove(this.getHandle().nameAndId());
      }
   }

   public void setGameMode(GameMode mode) {
      Preconditions.checkArgument(mode != null, "GameMode cannot be null");
      if (this.getHandle().connection != null) {
         this.getHandle().setGameMode(GameType.byId(mode.getValue()), org.bukkit.event.player.PlayerGameModeChangeEvent.Cause.PLUGIN, null);
      }
   }

   public GameMode getGameMode() {
      return GameMode.getByValue(this.getHandle().gameMode.getGameModeForPlayer().getId());
   }

   public GameMode getPreviousGameMode() {
      GameType previousGameMode = this.getHandle().gameMode.getPreviousGameModeForPlayer();
      return previousGameMode == null ? null : GameMode.getByValue(previousGameMode.getId());
   }

   public int applyMending(int amount) {
      ServerPlayer handle = this.getHandle();
      Optional<EnchantedItemInUse> selected = EnchantmentHelper.getRandomItemWith(
         EnchantmentEffectComponents.REPAIR_WITH_XP, handle, net.minecraft.world.item.ItemStack::isDamaged
      );
      net.minecraft.world.item.ItemStack itemstack = selected.<net.minecraft.world.item.ItemStack>map(EnchantedItemInUse::itemStack)
         .orElse(net.minecraft.world.item.ItemStack.EMPTY);
      if (!itemstack.isEmpty() && itemstack.getItem().components().has(DataComponents.MAX_DAMAGE)) {
         ExperienceOrb orb = (ExperienceOrb)EntityTypes.EXPERIENCE_ORB.create(handle.level(), EntitySpawnReason.COMMAND);
         orb.setValue(amount);
         orb.spawnReason = SpawnReason.CUSTOM;
         orb.setPosRaw(handle.getX(), handle.getY(), handle.getZ());
         int toRepairFromXpAmount = EnchantmentHelper.modifyDurabilityToRepairFromXp(handle.level(), itemstack, amount);
         int repair = Math.min(toRepairFromXpAmount, itemstack.getDamageValue());
         int consumedExperience = repair > 0 ? repair * amount / toRepairFromXpAmount : toRepairFromXpAmount;
         PlayerItemMendEvent event = CraftEventFactory.callPlayerItemMendEvent(handle, orb, itemstack, selected.get().inSlot(), repair, consumedExperience);
         repair = event.getRepairAmount();
         orb.discard(org.bukkit.event.entity.EntityRemoveEvent.Cause.DESPAWN);
         if (!event.isCancelled()) {
            amount -= consumedExperience;
            itemstack.setDamageValue(itemstack.getDamageValue() - repair);
         }
      }

      return amount;
   }

   public void giveExp(int exp, boolean applyMending) {
      if (applyMending) {
         exp = this.applyMending(exp);
      }

      this.getHandle().giveExperiencePoints(exp);
   }

   public void giveExpLevels(int levels) {
      this.getHandle().giveExperienceLevels(levels);
   }

   public float getExp() {
      return this.getHandle().experienceProgress;
   }

   public void setExp(float exp) {
      Preconditions.checkArgument(exp >= 0.0 && exp <= 1.0, "Experience progress must be between 0.0 and 1.0 (%s)", exp);
      this.getHandle().experienceProgress = exp;
      this.getHandle().lastSentExp = -1;
   }

   public int getLevel() {
      return this.getHandle().experienceLevel;
   }

   public void setLevel(int level) {
      Preconditions.checkArgument(level >= 0, "Experience level must not be negative (%s)", level);
      this.getHandle().setExperienceLevels(level);
   }

   public int getTotalExperience() {
      return this.getHandle().totalExperience;
   }

   public void setTotalExperience(int exp) {
      Preconditions.checkArgument(exp >= 0, "Total experience points must not be negative (%s)", exp);
      this.getHandle().totalExperience = exp;
   }

   public int calculateTotalExperiencePoints() {
      return this.calculateTotalExperiencePoints(this.getLevel()) + Math.round(this.getExperiencePointsNeededForNextLevel() * this.getExp());
   }

   public void setExperienceLevelAndProgress(int totalExperience) {
      Preconditions.checkArgument(totalExperience >= 0, "Total experience points must not be negative (%s)", totalExperience);
      int level = this.calculateLevelsForExperiencePoints(totalExperience);
      int remainingPoints = totalExperience - this.calculateTotalExperiencePoints(level);
      this.getHandle().experienceLevel = level;
      this.getHandle().experienceProgress = (float)remainingPoints / this.getExperiencePointsNeededForNextLevel();
      this.getHandle().lastSentExp = -1;
   }

   public int getExperiencePointsNeededForNextLevel() {
      return this.getHandle().getXpNeededForNextLevel();
   }

   private int calculateTotalExperiencePoints(int level) {
      if (level <= 16) {
         return (int)(Math.pow(level, 2.0) + 6 * level);
      } else {
         return level <= 31 ? (int)(2.5 * Math.pow(level, 2.0) - 40.5 * level + 360.0) : (int)(4.5 * Math.pow(level, 2.0) - 162.5 * level + 2220.0);
      }
   }

   private int calculateLevelsForExperiencePoints(int points) {
      if (points <= 352) {
         return (int)Math.floor(Math.sqrt(points + 9) - 3.0);
      } else {
         return points <= 1507
            ? (int)Math.floor(8.1 + Math.sqrt(0.4 * (points - 195.975)))
            : (int)Math.floor(18.055555555555557 + Math.sqrt(0.2222222222222222 * (points - 752.9861111111111)));
      }
   }

   public void sendExperienceChange(float progress) {
      this.sendExperienceChange(progress, this.getLevel());
   }

   public void sendExperienceChange(float progress, int level) {
      Preconditions.checkArgument(progress >= 0.0 && progress <= 1.0, "Experience progress must be between 0.0 and 1.0 (%s)", progress);
      Preconditions.checkArgument(level >= 0, "Experience level must not be negative (%s)", level);
      if (this.getHandle().connection != null) {
         ClientboundSetExperiencePacket packet = new ClientboundSetExperiencePacket(progress, this.getTotalExperience(), level);
         this.getHandle().connection.send(packet);
      }
   }

   private static @Nullable WeakReference<Plugin> getPluginWeakReference(@Nullable Plugin plugin) {
      return plugin == null ? null : pluginWeakReferences.computeIfAbsent(plugin, WeakReference::new);
   }

   @Deprecated
   public void hidePlayer(Player player) {
      this.hideEntity0(null, player);
   }

   public void hideEntity(Plugin plugin, Entity entity) {
      Preconditions.checkArgument(plugin != null, "Plugin cannot be null");
      Preconditions.checkArgument(plugin.isEnabled(), "Plugin (%s) cannot be disabled", plugin.getName());
      this.hideEntity0(plugin, entity);
   }

   private void hideEntity0(@Nullable Plugin plugin, Entity entity) {
      Preconditions.checkArgument(entity != null, "Entity hidden cannot be null");
      if (this.getHandle().connection != null) {
         if (!this.equals(entity)) {
            boolean shouldHide;
            if (entity.isVisibleByDefault()) {
               shouldHide = this.addInvertedVisibility(plugin, entity);
            } else {
               shouldHide = this.removeInvertedVisibility(plugin, entity);
            }

            if (shouldHide) {
               this.untrackAndHideEntity(entity);
            }
         }
      }
   }

   private boolean addInvertedVisibility(@Nullable Plugin plugin, Entity entity) {
      Set<WeakReference<Plugin>> invertedPlugins = this.invertedVisibilityEntities.get(entity.getUniqueId());
      if (invertedPlugins != null) {
         invertedPlugins.add(getPluginWeakReference(plugin));
         return false;
      } else {
         invertedPlugins = new HashSet<>();
         invertedPlugins.add(getPluginWeakReference(plugin));
         this.invertedVisibilityEntities.put(entity.getUniqueId(), invertedPlugins);
         return true;
      }
   }

   private void untrackAndHideEntity(Entity entity) {
      net.minecraft.world.entity.Entity other = ((CraftEntity)entity).getHandle();
      this.unregisterEntity(other);
      this.server.getPluginManager().callEvent(new PlayerHideEntityEvent(this, entity));
   }

   private void unregisterEntity(net.minecraft.world.entity.Entity other) {
      ChunkMap tracker = this.getHandle().level().getChunkSource().chunkMap;
      TrackedEntity entry = (TrackedEntity)tracker.entityMap.get(other.getId());
      if (entry != null) {
         entry.removePlayer(this.getHandle());
      }

      if (other instanceof ServerPlayer otherPlayer && this.getHandle().sentListPacket && otherPlayer.sentListPacket) {
         this.getHandle().connection.send(new ClientboundPlayerInfoRemovePacket(List.of(otherPlayer.getUUID())));
      }
   }

   void resetAndHideEntity(Entity entity) {
      if (!this.equals(entity)) {
         if (this.invertedVisibilityEntities.remove(entity.getUniqueId()) == null) {
            this.untrackAndHideEntity(entity);
         }
      }
   }

   @Deprecated
   public void showPlayer(Player player) {
      this.showEntity0(null, player);
   }

   public void showEntity(Plugin plugin, Entity entity) {
      Preconditions.checkArgument(plugin != null, "Plugin cannot be null");
      this.showEntity0(plugin, entity);
   }

   private void showEntity0(@Nullable Plugin plugin, Entity entity) {
      Preconditions.checkArgument(entity != null, "Entity show cannot be null");
      if (this.getHandle().connection != null) {
         if (!this.equals(entity)) {
            boolean shouldShow;
            if (entity.isVisibleByDefault()) {
               shouldShow = this.removeInvertedVisibility(plugin, entity);
            } else {
               shouldShow = this.addInvertedVisibility(plugin, entity);
            }

            if (shouldShow) {
               this.trackAndShowEntity(entity);
            }
         }
      }
   }

   private boolean removeInvertedVisibility(@Nullable Plugin plugin, Entity entity) {
      Set<WeakReference<Plugin>> invertedPlugins = this.invertedVisibilityEntities.get(entity.getUniqueId());
      if (invertedPlugins == null) {
         return false;
      }

      invertedPlugins.remove(getPluginWeakReference(plugin));
      if (!invertedPlugins.isEmpty()) {
         return false;
      }

      this.invertedVisibilityEntities.remove(entity.getUniqueId());
      return true;
   }

   private void trackAndShowEntity(Entity entity) {
      this.trackAndShowEntity(entity, null);
   }

   private void trackAndShowEntity(Entity entity, @Nullable UUID uuidOverride) {
      ChunkMap tracker = this.getHandle().level().getChunkSource().chunkMap;
      net.minecraft.world.entity.Entity other = ((CraftEntity)entity).getHandle();
      if (other instanceof ServerPlayer otherPlayer) {
         UUID original = null;
         if (uuidOverride != null) {
            original = otherPlayer.getUUID();
            otherPlayer.setUUID(uuidOverride);
         }

         this.getHandle().connection.send(ClientboundPlayerInfoUpdatePacket.createPlayerInitializing(List.of(otherPlayer), this.getHandle()));
         if (original != null) {
            otherPlayer.setUUID(original);
         }
      }

      TrackedEntity entry = (TrackedEntity)tracker.entityMap.get(other.getId());
      if (entry != null && !entry.seenBy.contains(this.getHandle().connection)) {
         entry.updatePlayer(this.getHandle());
      }

      this.server.getPluginManager().callEvent(new PlayerShowEntityEvent(this, entity));
   }

   public void setPlayerProfile(PlayerProfile profile) {
      ServerPlayer self = this.getHandle();
      GameProfile gameProfile = CraftPlayerProfile.asAuthlibCopy(profile);
      if (!self.sentListPacket) {
         self.gameProfile = gameProfile;
      } else {
         List<ServerPlayer> players = this.server.getServer().getPlayerList().getPlayers();

         for (ServerPlayer player : players) {
            CraftPlayer bukkitPlayer = player.getBukkitEntity();
            if (bukkitPlayer.canSee(this)) {
               bukkitPlayer.unregisterEntity(self);
            }
         }

         self.gameProfile = gameProfile;

         for (ServerPlayer player : players) {
            CraftPlayer bukkitPlayer = player.getBukkitEntity();
            if (bukkitPlayer.canSee(this)) {
               bukkitPlayer.trackAndShowEntity(self.getBukkitEntity(), gameProfile.id());
            }
         }

         this.refreshPlayer();
      }
   }

   void resetAndShowEntity(Entity entity) {
      if (!this.equals(entity)) {
         if (this.invertedVisibilityEntities.remove(entity.getUniqueId()) == null) {
            this.trackAndShowEntity(entity);
         }
      }
   }

   public PlayerProfile getPlayerProfile() {
      return new CraftPlayerProfile(this);
   }

   private void refreshPlayer() {
      ServerPlayer handle = this.getHandle();
      ServerGamePacketListenerImpl connection = handle.connection;
      ServerLevel level = handle.level();
      connection.send(new ClientboundRespawnPacket(handle.createCommonSpawnInfo(level), (byte)3));
      handle.onUpdateAbilities();
      connection.internalTeleport(PositionMoveRotation.of(this.getHandle()), Collections.emptySet());
      PlayerList playerList = this.server.getServer().getPlayerList();
      playerList.sendPlayerPermissionLevel(handle, false);
      playerList.sendLevelInfo(handle, level);
      playerList.sendAllPlayerInfo(handle);
      connection.send(new ClientboundSetExperiencePacket(handle.experienceProgress, handle.totalExperience, handle.experienceLevel));

      for (MobEffectInstance mobEffect : handle.getActiveEffects()) {
         connection.send(new ClientboundUpdateMobEffectPacket(handle.getId(), mobEffect, false));
      }

      this.sendHealthUpdate();
   }

   public void onEntityRemove(net.minecraft.world.entity.Entity entity) {
      this.invertedVisibilityEntities.remove(entity.getUUID());
   }

   public boolean canSee(Player player) {
      return this.canSee(player);
   }

   public boolean canSee(Entity entity) {
      return this.equals(entity) || entity.isVisibleByDefault() ^ this.invertedVisibilityEntities.containsKey(entity.getUniqueId());
   }

   public boolean canSeePlayer(UUID uuid) {
      Entity entity = this.getServer().getPlayer(uuid);
      return entity != null ? this.canSee(entity) : false;
   }

   public boolean isListed(Player other) {
      return !this.unlistedEntities.contains(other.getUniqueId());
   }

   public boolean unlistPlayer(@NonNull Player other) {
      Preconditions.checkNotNull(other, "hidden entity cannot be null");
      if (this.getHandle().connection == null) {
         return false;
      } else if (!this.canSee(other)) {
         return false;
      } else if (this.unlistedEntities.add(other.getUniqueId())) {
         this.getHandle().connection.send(ClientboundPlayerInfoUpdatePacket.updateListed(other.getUniqueId(), false));
         return true;
      } else {
         return false;
      }
   }

   public boolean listPlayer(@NonNull Player other) {
      Preconditions.checkNotNull(other, "hidden entity cannot be null");
      if (this.getHandle().connection == null) {
         return false;
      } else if (!this.canSee(other)) {
         throw new IllegalStateException("Player cannot see other player");
      } else if (this.unlistedEntities.remove(other.getUniqueId())) {
         this.getHandle().connection.send(ClientboundPlayerInfoUpdatePacket.updateListed(other.getUniqueId(), true));
         return true;
      } else {
         return false;
      }
   }

   public Map<String, Object> serialize() {
      Map<String, Object> result = new LinkedHashMap<>();
      result.put("name", this.getName());
      return result;
   }

   public Player getPlayer() {
      return this;
   }

   public long getFirstPlayed() {
      return this.firstPlayed;
   }

   public long getLastPlayed() {
      return this.lastPlayed;
   }

   public boolean hasPlayedBefore() {
      return this.hasPlayedBefore;
   }

   public void setFirstPlayed(long firstPlayed) {
      this.firstPlayed = firstPlayed;
   }

   public long getLastLogin() {
      return this.getHandle().loginTime;
   }

   public long getLastSeen() {
      return this.isOnline() ? System.currentTimeMillis() : this.lastSaveTime;
   }

   public void readExtraData(ValueInput input) {
      this.hasPlayedBefore = true;
      input.child("bukkit").ifPresent(data -> {
         this.firstPlayed = data.getLongOr("firstPlayed", 0L);
         this.lastPlayed = data.getLongOr("lastPlayed", 0L);
         ServerPlayer handle = this.getHandle();
         handle.newExp = data.getIntOr("newExp", 0);
         handle.newTotalExp = data.getIntOr("newTotalExp", 0);
         handle.newLevel = data.getIntOr("newLevel", 0);
         handle.expToDrop = data.getIntOr("expToDrop", 0);
         handle.keepLevel = data.getBooleanOr("keepLevel", false);
      });
   }

   public void setExtraData(ValueOutput output) {
      this.lastSaveTime = System.currentTimeMillis();
      ValueOutput data = output.child("bukkit");
      ServerPlayer handle = this.getHandle();
      data.putInt("newExp", handle.newExp);
      data.putInt("newTotalExp", handle.newTotalExp);
      data.putInt("newLevel", handle.newLevel);
      data.putInt("expToDrop", handle.expToDrop);
      data.putBoolean("keepLevel", handle.keepLevel);
      data.putLong("firstPlayed", this.getFirstPlayed());
      data.putLong("lastPlayed", System.currentTimeMillis());
      data.putString("lastKnownName", handle.getGameProfile().name());
      ValueOutput paper = output.child("Paper");
      paper.putLong("LastLogin", handle.loginTime);
      paper.putLong("LastSeen", System.currentTimeMillis());
   }

   public boolean beginConversation(Conversation conversation) {
      return this.conversationTracker.beginConversation(conversation);
   }

   public void abandonConversation(Conversation conversation) {
      this.conversationTracker.abandonConversation(conversation, new ConversationAbandonedEvent(conversation, new ManuallyAbandonedConversationCanceller()));
   }

   public void abandonConversation(Conversation conversation, ConversationAbandonedEvent details) {
      this.conversationTracker.abandonConversation(conversation, details);
   }

   public void acceptConversationInput(String input) {
      this.conversationTracker.acceptConversationInput(input);
   }

   public boolean isConversing() {
      return this.conversationTracker.isConversing();
   }

   public void sendPluginMessage(Plugin source, String channel, byte[] message) {
      StandardMessenger.validatePluginMessage(this.server.getMessenger(), source, channel, message);
      if (this.getHandle().connection != null) {
         if (this.channels().contains(channel)) {
            Identifier id = Identifier.parse(StandardMessenger.validateAndCorrectChannel(channel));
            this.sendCustomPayload(id, message);
         }
      }
   }

   private void sendCustomPayload(Identifier id, byte[] message) {
      ClientboundCustomPayloadPacket packet = new ClientboundCustomPayloadPacket(new DiscardedPayload(id, message));
      this.getHandle().connection.send(packet);
   }

   public void setResourcePack(String url, byte @Nullable [] hash, String prompt, boolean force) {
      Preconditions.checkArgument(url != null, "Resource pack URL cannot be null");
      this.setResourcePack(UUID.nameUUIDFromBytes(url.getBytes(StandardCharsets.UTF_8)), url, hash, prompt, force);
   }

   public void setResourcePack(UUID id, String url, byte @Nullable [] hash, String prompt, boolean force) {
      Preconditions.checkArgument(id != null, "Resource pack ID cannot be null");
      Preconditions.checkArgument(url != null, "Resource pack URL cannot be null");
      String hashStr = "";
      if (hash != null) {
         Preconditions.checkArgument(hash.length == 20, "Resource pack hash should be 20 bytes long but was %s", hash.length);
         hashStr = BaseEncoding.base16().lowerCase().encode(hash);
      }

      this.handlePushResourcePack(new ClientboundResourcePackPushPacket(id, url, hashStr, force, CraftChatMessage.fromStringOrOptional(prompt, true)), true);
   }

   public void addResourcePack(UUID id, String url, byte @Nullable [] hash, String prompt, boolean force) {
      Preconditions.checkArgument(url != null, "Resource pack URL cannot be null");
      String hashStr = "";
      if (hash != null) {
         Preconditions.checkArgument(hash.length == 20, "Resource pack hash should be 20 bytes long but was %s", hash.length);
         hashStr = BaseEncoding.base16().lowerCase().encode(hash);
      }

      this.handlePushResourcePack(new ClientboundResourcePackPushPacket(id, url, hashStr, force, CraftChatMessage.fromStringOrOptional(prompt, true)), false);
   }

   public void setResourcePack(UUID uuid, String url, byte @Nullable [] hashBytes, Component prompt, boolean force) {
      Preconditions.checkArgument(uuid != null, "Resource pack UUID cannot be null");
      Preconditions.checkArgument(url != null, "Resource pack URL cannot be null");
      String hash;
      if (hashBytes != null) {
         Preconditions.checkArgument(hashBytes.length == 20, "Resource pack hash should be 20 bytes long but was " + hashBytes.length);
         hash = BaseEncoding.base16().lowerCase().encode(hashBytes);
      } else {
         hash = "";
      }

      this.getHandle().connection.send(new ClientboundResourcePackPopPacket(Optional.empty()));
      this.getHandle()
         .connection
         .send(new ClientboundResourcePackPushPacket(uuid, url, hash, force, Optional.ofNullable(prompt).map(PaperAdventure::asVanilla)));
   }

   void sendBundle(List<? extends Packet<? extends ClientCommonPacketListener>> packet) {
      this.getHandle().connection.send(new ClientboundBundlePacket((Iterable<Packet<? super ClientGamePacketListener>>)packet));
   }

   public void sendResourcePacks(ResourcePackRequest request) {
      if (this.getHandle().connection != null) {
         List<ClientboundResourcePackPushPacket> packs = new ArrayList<>(request.packs().size());
         if (request.replace()) {
            this.clearResourcePacks();
         }

         net.minecraft.network.chat.Component prompt = PaperAdventure.asVanilla(request.prompt());
         Iterator<ResourcePackInfo> iter = request.packs().iterator();

         while (iter.hasNext()) {
            ResourcePackInfo pack = iter.next();
            packs.add(
               new ClientboundResourcePackPushPacket(
                  pack.id(), pack.uri().toASCIIString(), pack.hash(), request.required(), iter.hasNext() ? Optional.empty() : Optional.ofNullable(prompt)
               )
            );
            if (request.callback() != ResourcePackCallback.noOp()) {
               this.getHandle().connection.packCallbacks.put(pack.id(), request.callback());
            }
         }

         this.sendBundle(packs);
         super.sendResourcePacks(request);
      }
   }

   public void removeResourcePacks(UUID id, UUID... others) {
      if (this.getHandle().connection != null) {
         this.sendBundle(MonkeyBars.nonEmptyArrayToList(pack -> new ClientboundResourcePackPopPacket(Optional.of(pack)), id, others));
      }
   }

   public void clearResourcePacks() {
      if (this.getHandle().connection != null) {
         this.getHandle().connection.send(new ClientboundResourcePackPopPacket(Optional.empty()));
      }
   }

   public void showDialog(DialogLike dialog) {
      if (this.getHandle().connection != null) {
         this.getHandle().openDialog(PaperDialog.bukkitToMinecraftHolder((Dialog)dialog));
      }
   }

   public void closeDialog() {
      if (this.getHandle().connection != null) {
         this.getHandle().connection.send(ClientboundClearDialogPacket.INSTANCE);
      }
   }

   public Status getResourcePackStatus() {
      if (this.getHandle().connection == null) {
         throw new UnsupportedOperationException("Too early to call this method at this stage");
      } else {
         return this.getHandle().connection.connection.resourcePackStatus;
      }
   }

   public void removeResourcePack(UUID id) {
      Preconditions.checkArgument(id != null, "Resource pack id cannot be null");
      if (this.getHandle().connection != null) {
         this.getHandle().connection.send(new ClientboundResourcePackPopPacket(Optional.of(id)));
      }
   }

   public void removeResourcePacks() {
      if (this.getHandle().connection != null) {
         this.getHandle().connection.send(new ClientboundResourcePackPopPacket(Optional.empty()));
      }
   }

   private void handlePushResourcePack(ClientboundResourcePackPushPacket resourcePackPushPacket, boolean resetBeforePush) {
      if (this.getHandle().connection != null) {
         if (resetBeforePush) {
            this.removeResourcePacks();
         }

         this.getHandle().connection.send(resourcePackPushPacket);
      }
   }

   public Set<String> channels() {
      return this.getHandle().connection == null ? new HashSet<>() : this.getHandle().connection.pluginMessagerChannels;
   }

   public Set<String> getListeningPluginChannels() {
      return ImmutableSet.copyOf(this.channels());
   }

   public void sendSupportedChannels() {
      if (this.getHandle().connection != null) {
         Set<String> listening = this.server.getMessenger().getIncomingChannels();
         if (!listening.isEmpty()) {
            ByteArrayOutputStream stream = new ByteArrayOutputStream();

            for (String channel : listening) {
               try {
                  stream.write(channel.getBytes(StandardCharsets.UTF_8));
                  stream.write(0);
               } catch (IOException ex) {
                  java.util.logging.Logger.getLogger(CraftPlayer.class.getName())
                     .log(Level.SEVERE, "Could not send Plugin Channel REGISTER to " + this.getName(), ex);
               }
            }

            this.sendCustomPayload(ServerGamePacketListenerImpl.CUSTOM_REGISTER, stream.toByteArray());
         }
      }
   }

   public void setMetadata(String metadataKey, MetadataValue newMetadataValue) {
      this.server.getPlayerMetadata().setMetadata(this, metadataKey, newMetadataValue);
   }

   public List<MetadataValue> getMetadata(String metadataKey) {
      return this.server.getPlayerMetadata().getMetadata(this, metadataKey);
   }

   public boolean hasMetadata(String metadataKey) {
      return this.server.getPlayerMetadata().hasMetadata(this, metadataKey);
   }

   public void removeMetadata(String metadataKey, Plugin owningPlugin) {
      this.server.getPlayerMetadata().removeMetadata(this, metadataKey, owningPlugin);
   }

   public boolean setWindowProperty(Property prop, int value) {
      AbstractContainerMenu container = this.getHandle().containerMenu;
      if (container.getBukkitView().getType() != prop.getType()) {
         return false;
      }

      container.setData(prop.getId(), value);
      return true;
   }

   public void disconnect() {
      this.conversationTracker.abandonAllConversations();
      this.perm.clearPermissions();
   }

   public boolean isFlying() {
      return this.getHandle().getAbilities().flying;
   }

   public void setFlying(boolean value) {
      boolean needsUpdate = this.getHandle().getAbilities().flying != value;
      if (!this.getAllowFlight()) {
         Preconditions.checkArgument(!value, "Player is not allowed to fly (check #getAllowFlight())");
      }

      this.getHandle().getAbilities().flying = value;
      if (needsUpdate) {
         this.getHandle().onUpdateAbilities();
      }
   }

   public boolean getAllowFlight() {
      return this.getHandle().getAbilities().mayfly;
   }

   public void setAllowFlight(boolean value) {
      if (this.isFlying() && !value) {
         this.getHandle().getAbilities().flying = false;
      }

      this.getHandle().getAbilities().mayfly = value;
      this.getHandle().onUpdateAbilities();
   }

   public void setFlyingFallDamage(@NonNull TriState flyingFallDamage) {
      this.getHandle().flyingFallDamage = flyingFallDamage;
   }

   public @NonNull TriState hasFlyingFallDamage() {
      return this.getHandle().flyingFallDamage;
   }

   public void resetFlyingTicks() {
      if (this.getHandle().connection != null) {
         this.getHandle().connection.resetFlyingTicks();
      }
   }

   public void setFlySpeed(float value) {
      this.validateSpeed(value);
      ServerPlayer player = this.getHandle();
      player.getAbilities().setFlyingSpeed(value / 2.0F);
      player.onUpdateAbilities();
   }

   public void setWalkSpeed(float value) {
      this.validateSpeed(value);
      ServerPlayer player = this.getHandle();
      player.getAbilities().setWalkingSpeed(value / 2.0F);
      player.onUpdateAbilities();
      this.getHandle().getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(player.getAbilities().getWalkingSpeed());
   }

   public float getFlySpeed() {
      return this.getHandle().getAbilities().getFlyingSpeed() * 2.0F;
   }

   public float getWalkSpeed() {
      return this.getHandle().getAbilities().getWalkingSpeed() * 2.0F;
   }

   private void validateSpeed(float value) {
      Preconditions.checkArgument(value <= 1.0F && value >= -1.0F, "Speed value (%s) need to be between -1 and 1", value);
   }

   public void setMaxHealth(double amount) {
      super.setMaxHealth(amount);
      this.health = Math.min(this.health, amount);
      this.getHandle().resetSentInfo();
   }

   public void resetMaxHealth() {
      super.resetMaxHealth();
      this.getHandle().resetSentInfo();
   }

   public CraftScoreboard getScoreboard() {
      return this.server.getScoreboardManager().getPlayerBoard(this);
   }

   public @Nullable CraftScoreboard getScoreboardOverride() {
      return this.scoreboardOverride;
   }

   public void setScoreboardOverride(@Nullable CraftScoreboard scoreboardOverride) {
      this.scoreboardOverride = scoreboardOverride;
   }

   public void setScoreboard(Scoreboard scoreboard) {
      Preconditions.checkArgument(scoreboard != null, "Scoreboard cannot be null");
      Preconditions.checkState(this.getHandle().connection != null, "Cannot set scoreboard yet (invalid player connection)");
      if (scoreboard instanceof CraftScoreboard craftScoreboard) {
         this.server.getScoreboardManager().setPlayerBoard(this, craftScoreboard);
      } else {
         throw new IllegalArgumentException("Cannot set player scoreboard to an unregistered Scoreboard");
      }
   }

   public void setHealthScale(double value) {
      Preconditions.checkArgument(value > 0.0, "Health value (%s) must be greater than 0", value);
      this.healthScale = value;
      this.scaledHealth = true;
      this.updateScaledHealth();
   }

   public double getHealthScale() {
      return this.healthScale;
   }

   public void setHealthScaled(boolean scale) {
      if (this.scaledHealth != (this.scaledHealth = scale)) {
         this.updateScaledHealth();
      }
   }

   public boolean isHealthScaled() {
      return this.scaledHealth;
   }

   public float getScaledHealth() {
      return (float)(this.isHealthScaled() ? this.getHealth() * this.getHealthScale() / this.getMaxHealth() : this.getHealth());
   }

   public double getHealth() {
      return this.health;
   }

   public void setRealHealth(double health) {
      if (!Double.isNaN(health)) {
         this.health = health;
      }
   }

   public void updateScaledHealth() {
      this.updateScaledHealth(true);
   }

   public void updateScaledHealth(boolean sendHealth) {
      if (this.getHandle().connection != null) {
         this.getHandle().connection.send(new ClientboundUpdateAttributesPacket(this.getHandle().getId(), Set.of(this.getScaledMaxHealth())));
         if (sendHealth) {
            this.sendHealthUpdate();
         }
      }

      this.getHandle().getEntityData().set(net.minecraft.world.entity.LivingEntity.DATA_HEALTH_ID, this.getScaledHealth());
      this.getHandle().maxHealthCache = this.getMaxHealth();
   }

   public void sendHealthUpdate(double health, int foodLevel, float saturation) {
      this.getHandle().connection.send(new ClientboundSetHealthPacket((float)health, foodLevel, saturation));
   }

   public void sendHealthUpdate() {
      FoodData foodData = this.getHandle().getFoodData();
      ClientboundSetHealthPacket packet = new ClientboundSetHealthPacket(this.getScaledHealth(), foodData.getFoodLevel(), foodData.getSaturationLevel());
      if (this.getHandle().queueHealthUpdatePacket) {
         this.getHandle().queuedHealthUpdatePacket = packet;
      } else {
         this.getHandle().connection.send(packet);
      }
   }

   public void injectScaledMaxHealth(Collection<AttributeInstance> collection, boolean force) {
      if (this.scaledHealth || force) {
         Iterator<AttributeInstance> iterator = collection.iterator();

         while (iterator.hasNext()) {
            AttributeInstance genericInstance = iterator.next();
            if (genericInstance.getAttribute() == Attributes.MAX_HEALTH) {
               iterator.remove();
               break;
            }
         }

         collection.add(this.getScaledMaxHealth());
      }
   }

   public AttributeInstance getScaledMaxHealth() {
      AttributeInstance dummy = new AttributeInstance(Attributes.MAX_HEALTH, attribute -> {});
      double healthMod = this.scaledHealth ? this.healthScale : this.getMaxHealth();
      if (healthMod >= Float.MAX_VALUE || healthMod <= 0.0) {
         healthMod = 20.0;
         this.getServer().getLogger().warning(this.getName() + " tried to crash the server with a large health attribute");
      }

      dummy.setBaseValue(healthMod);
      return dummy;
   }

   public Entity getSpectatorTarget() {
      net.minecraft.world.entity.Entity followed = this.getHandle().getCamera();
      return followed == this.getHandle() ? null : followed.getBukkitEntity();
   }

   public void setSpectatorTarget(Entity entity) {
      Preconditions.checkArgument(this.getGameMode() == GameMode.SPECTATOR, "Player must be in spectator mode");
      this.getHandle().setCamera(entity == null ? null : ((CraftEntity)entity).getHandle());
   }

   public void sendTitle(String title, String subtitle) {
      this.sendTitle(title, subtitle, 10, 70, 20);
   }

   public void sendTitle(String title, String subtitle, int fadeIn, int stay, int fadeOut) {
      ClientboundSetTitlesAnimationPacket times = new ClientboundSetTitlesAnimationPacket(fadeIn, stay, fadeOut);
      this.getHandle().connection.send(times);
      if (title != null) {
         ClientboundSetTitleTextPacket packetTitle = new ClientboundSetTitleTextPacket(CraftChatMessage.fromString(title)[0]);
         this.getHandle().connection.send(packetTitle);
      }

      if (subtitle != null) {
         ClientboundSetSubtitleTextPacket packetSubtitle = new ClientboundSetSubtitleTextPacket(CraftChatMessage.fromString(subtitle)[0]);
         this.getHandle().connection.send(packetSubtitle);
      }
   }

   public void resetTitle() {
      ClientboundClearTitlesPacket packet = new ClientboundClearTitlesPacket(true);
      this.getHandle().connection.send(packet);
   }

   public <T> void spawnParticle(
      Particle particle, double x, double y, double z, int count, double offsetX, double offsetY, double offsetZ, double extra, T data, boolean force
   ) {
      ClientboundLevelParticlesPacket packet = new ClientboundLevelParticlesPacket(
         CraftParticle.createParticleParam(particle, data), force, false, x, y, z, (float)offsetX, (float)offsetY, (float)offsetZ, (float)extra, count
      );
      this.getHandle().connection.send(packet);
   }

   public AdvancementProgress getAdvancementProgress(Advancement advancement) {
      Preconditions.checkArgument(advancement != null, "advancement");
      CraftAdvancement craft = (CraftAdvancement)advancement;
      PlayerAdvancements data = this.getHandle().getAdvancements();
      net.minecraft.advancements.AdvancementProgress progress = data.getOrStartProgress(craft.getHandle());
      return new CraftAdvancementProgress(craft, data, progress);
   }

   public int getClientViewDistance() {
      return this.getHandle().requestedViewDistance();
   }

   public Locale locale() {
      return (R)this.getHandle().adventure$locale;
   }

   public int getPing() {
      if (this.getHandle().connection == null) {
         throw new UnsupportedOperationException("Too early to call this method at this stage");
      } else {
         return this.getHandle().connection.latency();
      }
   }

   public String getLocale() {
      String locale = this.getHandle().language;
      return locale != null ? locale : "en_us";
   }

   public void setAffectsSpawning(boolean affects) {
      this.getHandle().affectsSpawning = affects;
   }

   public boolean getAffectsSpawning() {
      return this.getHandle().affectsSpawning;
   }

   public void updateCommands() {
      if (this.getHandle().connection != null) {
         this.server.getServer().getCommands().sendCommands(this.getHandle());
      }
   }

   public void openSign(@NonNull Sign sign, @NonNull Side side) {
      CraftSign.openSign(sign, this, side);
   }

   public void openVirtualSign(Position block, Side side) {
      if (this.getHandle().connection != null) {
         this.getHandle().connection.send(new ClientboundOpenSignEditorPacket(MCUtil.toBlockPos(block), side == Side.FRONT));
      }
   }

   public void showDemoScreen() {
      if (this.getHandle().connection != null) {
         this.getHandle().connection.send(new ClientboundGameEventPacket(ClientboundGameEventPacket.DEMO_EVENT, 0.0F));
      }
   }

   public boolean isAllowingServerListings() {
      return this.getHandle().allowsListing();
   }

   public Component displayName() {
      return (R)this.getHandle().adventure$displayName;
   }

   public void displayName(Component displayName) {
      this.getHandle().adventure$displayName = (Component)(displayName != null ? displayName : Component.text(this.getName()));
      this.getHandle().displayName = null;
   }

   public void deleteMessage(Signature signature) {
      if (this.getHandle().connection != null) {
         MessageSignature sig = new MessageSignature(signature.bytes());
         this.getHandle().connection.send(new ClientboundDeleteChatPacket(new Packed(sig)));
      }
   }

   private ChatType.Bound toHandle(Bound boundChatType) {
      Registry<ChatType> chatTypeRegistry = this.getHandle().level().registryAccess().lookupOrThrow(Registries.CHAT_TYPE);
      return new ChatType.Bound(
         chatTypeRegistry.getOrThrow(ResourceKey.create(Registries.CHAT_TYPE, PaperAdventure.asVanilla(boundChatType.type().key()))),
         PaperAdventure.asVanilla(boundChatType.name()),
         Optional.ofNullable(PaperAdventure.asVanilla(boundChatType.target()))
      );
   }

   public void sendMessage(Component message, Bound boundChatType) {
      if (this.getHandle().connection != null) {
         net.minecraft.network.chat.Component component = PaperAdventure.asVanilla(message);
         this.getHandle().sendChatMessage(new Disguised(component), this.getHandle().isTextFilteringEnabled(), this.toHandle(boundChatType));
      }
   }

   public void sendMessage(SignedMessage signedMessage, Bound boundChatType) {
      if (this.getHandle().connection != null) {
         if (signedMessage instanceof AdventureView view) {
            this.getHandle()
               .sendChatMessage(OutgoingChatMessage.create(view.playerChatMessage()), this.getHandle().isTextFilteringEnabled(), this.toHandle(boundChatType));
         } else {
            Component message = (Component)(signedMessage.unsignedContent() == null ? Component.text(signedMessage.message()) : signedMessage.unsignedContent());
            if (signedMessage.isSystem()) {
               this.sendMessage(message, boundChatType);
            } else {
               super.sendMessage(signedMessage, boundChatType);
            }
         }
      }
   }

   public void sendMessage(Component message) {
      if (this.getHandle().connection != null) {
         this.getHandle().connection.send(new ClientboundSystemChatPacket(message, false));
      }
   }

   public void sendActionBar(Component message) {
      ClientboundSetActionBarTextPacket packet = new ClientboundSetActionBarTextPacket(PaperAdventure.asVanillaNullToEmpty(message));
      this.getHandle().connection.send(packet);
   }

   public void sendPlayerListHeader(Component header) {
      this.playerListHeader = header;
      this.adventure$sendPlayerListHeaderAndFooter();
   }

   public void sendPlayerListFooter(Component footer) {
      this.playerListFooter = footer;
      this.adventure$sendPlayerListHeaderAndFooter();
   }

   public void sendPlayerListHeaderAndFooter(Component header, Component footer) {
      this.playerListHeader = header;
      this.playerListFooter = footer;
      this.adventure$sendPlayerListHeaderAndFooter();
   }

   private void adventure$sendPlayerListHeaderAndFooter() {
      ServerGamePacketListenerImpl connection = this.getHandle().connection;
      if (connection != null) {
         ClientboundTabListPacket packet = new ClientboundTabListPacket(
            PaperAdventure.asVanillaNullToEmpty(this.playerListHeader), PaperAdventure.asVanillaNullToEmpty(this.playerListFooter)
         );
         connection.send(packet);
      }
   }

   public void showTitle(net.kyori.adventure.title.Title title) {
      ServerGamePacketListenerImpl connection = this.getHandle().connection;
      Times times = title.times();
      if (times != null) {
         connection.send(new ClientboundSetTitlesAnimationPacket(ticks(times.fadeIn()), ticks(times.stay()), ticks(times.fadeOut())));
      }

      ClientboundSetSubtitleTextPacket sp = new ClientboundSetSubtitleTextPacket(PaperAdventure.asVanilla(title.subtitle()));
      connection.send(sp);
      ClientboundSetTitleTextPacket tp = new ClientboundSetTitleTextPacket(PaperAdventure.asVanilla(title.title()));
      connection.send(tp);
   }

   public <T> void sendTitlePart(TitlePart<T> part, T value) {
      Preconditions.checkArgument(part != null, "part cannot be null");
      Preconditions.checkArgument(value != null, "value cannot be null");
      if (part == TitlePart.TITLE) {
         ClientboundSetTitleTextPacket tp = new ClientboundSetTitleTextPacket(PaperAdventure.asVanilla((Component)value));
         this.getHandle().connection.send(tp);
      } else if (part == TitlePart.SUBTITLE) {
         ClientboundSetSubtitleTextPacket sp = new ClientboundSetSubtitleTextPacket(PaperAdventure.asVanilla((Component)value));
         this.getHandle().connection.send(sp);
      } else {
         if (part != TitlePart.TIMES) {
            throw new IllegalArgumentException("Unknown TitlePart");
         }

         Times times = (Times)value;
         this.getHandle().connection.send(new ClientboundSetTitlesAnimationPacket(ticks(times.fadeIn()), ticks(times.stay()), ticks(times.fadeOut())));
      }
   }

   private static int ticks(Duration duration) {
      return duration == null ? -1 : (int)(duration.toMillis() / 50L);
   }

   public void clearTitle() {
      this.getHandle().connection.send(new ClientboundClearTitlesPacket(false));
   }

   public @NonNull Iterable<? extends BossBar> activeBossBars() {
      return this.activeBossBars != null ? Collections.unmodifiableSet(this.activeBossBars) : Set.of();
   }

   public void showBossBar(BossBar bar) {
      ((BossBarImplementationImpl)BossBarImplementation.get(bar, BossBarImplementationImpl.class)).playerShow(this);
      if (this.activeBossBars == null) {
         this.activeBossBars = new HashSet<>();
      }

      this.activeBossBars.add(bar);
   }

   public void hideBossBar(BossBar bar) {
      ((BossBarImplementationImpl)BossBarImplementation.get(bar, BossBarImplementationImpl.class)).playerHide(this);
      if (this.activeBossBars != null) {
         this.activeBossBars.remove(bar);
         if (this.activeBossBars.isEmpty()) {
            this.activeBossBars = null;
         }
      }
   }

   public void playSound(net.kyori.adventure.sound.Sound sound) {
      Vec3 pos = this.getHandle().position();
      this.playSound(sound, pos.x, pos.y, pos.z);
   }

   public void playSound(net.kyori.adventure.sound.Sound sound, double x, double y, double z) {
      this.getHandle().connection.send(PaperAdventure.asSoundPacket(sound, x, y, z, sound.seed().orElseGet(this.getHandle().getRandom()::nextLong), null));
   }

   public void playSound(net.kyori.adventure.sound.Sound sound, Emitter emitter) {
      net.minecraft.world.entity.Entity entity;
      if (emitter == Emitter.self()) {
         entity = this.getHandle();
      } else {
         if (!(emitter instanceof CraftEntity craftEntity)) {
            throw new IllegalArgumentException("Sound emitter must be an Entity or self(), but was: " + emitter);
         }

         entity = craftEntity.getHandle();
      }

      this.getHandle().connection.send(PaperAdventure.asSoundPacket(sound, entity, sound.seed().orElseGet(this.getHandle().getRandom()::nextLong), null));
   }

   public void stopSound(SoundStop stop) {
      this.getHandle()
         .connection
         .send(new ClientboundStopSoundPacket(PaperAdventure.asVanillaNullable(stop.sound()), PaperAdventure.asVanillaNullable(stop.source())));
   }

   public void openBook(ItemStack book) {
      Preconditions.checkArgument(book != null, "ItemStack cannot be null");
      Preconditions.checkArgument(book.hasData(DataComponentTypes.WRITTEN_BOOK_CONTENT), "ItemStack must have a 'written_book_content' component");
      net.minecraft.world.item.ItemStack bookItem = CraftItemStack.asNMSCopy(book);
      ServerPlayer serverPlayer = this.getHandle();
      WrittenBookContent.resolveForItem(bookItem, ResolutionContext.create(serverPlayer.createCommandSourceStack()), serverPlayer.registryAccess());
      this.sendBookOpen(bookItem);
   }

   public void openBook(Book book) {
      ItemStack mutatedItem = ItemType.WRITTEN_BOOK.createItemStack();
      mutatedItem.setData(
         DataComponentTypes.WRITTEN_BOOK_CONTENT, io.papermc.paper.datacomponent.item.WrittenBookContent.writtenBookContent("", "").addPages(book.pages())
      );
      this.sendBookOpen(CraftItemStack.unwrap(mutatedItem));
   }

   private void sendBookOpen(net.minecraft.world.item.ItemStack bookItem) {
      net.minecraft.world.item.ItemStack selectedItem = this.getHandle().getInventory().getSelectedItem();
      int slot = this.getHandle().getInventory().getSelectedSlot();
      this.getHandle()
         .connection
         .send(
            new ClientboundBundlePacket(
               List.of(
                  new ClientboundSetPlayerInventoryPacket(slot, bookItem),
                  new ClientboundOpenBookPacket(InteractionHand.MAIN_HAND),
                  new ClientboundSetPlayerInventoryPacket(slot, selectedItem)
               )
            )
         );
   }

   public Pointers pointers() {
      return POINTERS_SUPPLIER.view(this);
   }

   public float getCooldownPeriod() {
      return this.getHandle().getCurrentItemAttackStrengthDelay();
   }

   public float getCooledAttackStrength(float adjustTicks) {
      return this.getHandle().getAttackStrengthScale(adjustTicks);
   }

   public void resetCooldown() {
      this.getHandle().resetAttackStrengthTicker();
   }

   public String getClientBrandName() {
      return this.getHandle().connection == null ? null : this.getHandle().connection.clientBrand;
   }

   public void showElderGuardian(boolean silent) {
      if (this.getHandle().connection != null) {
         this.getHandle().connection.send(new ClientboundGameEventPacket(ClientboundGameEventPacket.GUARDIAN_ELDER_EFFECT, silent ? 0.0F : 1.0F));
      }
   }

   public int getWardenWarningCooldown() {
      return this.getHandle().wardenSpawnTracker.cooldownTicks;
   }

   public void setWardenWarningCooldown(int cooldown) {
      this.getHandle().wardenSpawnTracker.cooldownTicks = Math.max(cooldown, 0);
   }

   public int getWardenTimeSinceLastWarning() {
      return this.getHandle().wardenSpawnTracker.ticksSinceLastWarning;
   }

   public void setWardenTimeSinceLastWarning(int time) {
      this.getHandle().wardenSpawnTracker.ticksSinceLastWarning = time;
   }

   public int getWardenWarningLevel() {
      return this.getHandle().wardenSpawnTracker.getWarningLevel();
   }

   public void setWardenWarningLevel(int warningLevel) {
      this.getHandle().wardenSpawnTracker.setWarningLevel(warningLevel);
   }

   public void increaseWardenWarningLevel() {
      this.getHandle().wardenSpawnTracker.increaseWarningLevel();
   }

   public Duration getIdleDuration() {
      return Duration.ofMillis(Util.getMillis() - this.getHandle().getLastActionTime());
   }

   public void resetIdleDuration() {
      this.getHandle().resetLastActionTime();
   }

   public Set<Long> getSentChunkKeys() {
      AsyncCatcher.catchOp("accessing sent chunks");
      return FeatureHooks.getSentChunkKeys(this.getHandle());
   }

   public Set<Chunk> getSentChunks() {
      AsyncCatcher.catchOp("accessing sent chunks");
      return FeatureHooks.getSentChunks(this.getHandle());
   }

   public boolean isChunkSent(long chunkKey) {
      AsyncCatcher.catchOp("accessing sent chunks");
      return FeatureHooks.isChunkSent(this.getHandle(), chunkKey);
   }

   public Spigot spigot() {
      return this.spigot;
   }

   public int getViewDistance() {
      return PlatformHooks.get().getViewDistance(this.getHandle());
   }

   public void setViewDistance(int viewDistance) {
      FeatureHooks.setViewDistance(this.getHandle(), viewDistance);
   }

   public int getSimulationDistance() {
      return PlatformHooks.get().getTickViewDistance(this.getHandle());
   }

   public void setSimulationDistance(int simulationDistance) {
      FeatureHooks.setSimulationDistance(this.getHandle(), simulationDistance);
   }

   public int getSendViewDistance() {
      return PlatformHooks.get().getSendViewDistance(this.getHandle());
   }

   public void setSendViewDistance(int viewDistance) {
      FeatureHooks.setSendViewDistance(this.getHandle(), viewDistance);
   }

   public void sendEntityEffect(EntityEffect effect, Entity target) {
      if (this.getHandle().connection != null) {
         Preconditions.checkArgument(effect.isApplicableTo(target), "Entity effect cannot apply to the target");
         this.getHandle().connection.send(new ClientboundEntityEventPacket(((CraftEntity)target).getHandle(), effect.getData()));
      }
   }

   public @NonNull PlayerGiveResult give(@NonNull Collection<@NonNull ItemStack> items, boolean dropIfFull) {
      Preconditions.checkArgument(items != null, "items cannot be null");
      if (items.isEmpty()) {
         return PaperPlayerGiveResult.EMPTY;
      }

      for (ItemStack item : items) {
         Preconditions.checkArgument(item != null, "ItemStack cannot be null");
         Preconditions.checkArgument(!item.isEmpty(), "ItemStack cannot be empty");
         Preconditions.checkArgument(item.getAmount() <= item.getMaxStackSize(), "ItemStack amount cannot be greater than its max stack size");
      }

      ServerPlayer handle = this.getHandle();
      com.google.common.collect.ImmutableList.Builder<Item> drops = ImmutableList.builder();
      com.google.common.collect.ImmutableList.Builder<ItemStack> leftovers = ImmutableList.builder();

      for (ItemStack item : items) {
         net.minecraft.world.item.ItemStack nmsStack = CraftItemStack.asNMSCopy(item);
         boolean added = handle.getInventory().add(nmsStack);
         if (!added || !nmsStack.isEmpty()) {
            leftovers.add(CraftItemStack.asBukkitCopy(nmsStack));
            if (dropIfFull) {
               ItemEntity entity = handle.drop(nmsStack, false, true);
               if (entity != null) {
                  drops.add((Item)entity.getBukkitEntity());
               }
            }
         }
      }

      handle.containerMenu.broadcastChanges();
      return new PaperPlayerGiveResult(leftovers.build(), drops.build());
   }

   public float getSidewaysMovement() {
      boolean leftMovement = this.getHandle().getLastClientInput().left();
      boolean rightMovement = this.getHandle().getLastClientInput().right();
      return leftMovement == rightMovement ? 0.0F : (leftMovement ? 1.0F : -1.0F);
   }

   public float getForwardsMovement() {
      boolean forwardMovement = this.getHandle().getLastClientInput().forward();
      boolean backwardMovement = this.getHandle().getLastClientInput().backward();
      return forwardMovement == backwardMovement ? 0.0F : (forwardMovement ? 1.0F : -1.0F);
   }

   public int getDeathScreenScore() {
      return this.getHandle().getScore();
   }

   public void setDeathScreenScore(int score) {
      this.getHandle().setScore(score);
   }

   public PlayerGameConnection getConnection() {
      return this.getHandle().connection.playerGameConnection;
   }

   public Entity releaseLeftShoulderEntity() {
      if (!this.getHandle().getShoulderEntityLeft().isEmpty()) {
         net.minecraft.world.entity.Entity entity = this.getHandle().releaseLeftShoulderEntity();
         if (entity != null) {
            return entity.getBukkitEntity();
         }
      }

      return null;
   }

   public Entity releaseRightShoulderEntity() {
      if (!this.getHandle().getShoulderEntityRight().isEmpty()) {
         net.minecraft.world.entity.Entity entity = this.getHandle().releaseRightShoulderEntity();
         if (entity != null) {
            return entity.getBukkitEntity();
         }
      }

      return null;
   }

   public Entity getShoulderEntityLeft() {
      if (!this.getHandle().getShoulderEntityLeft().isEmpty()) {
         ScopedCollector scopedCollector = new ScopedCollector(this.getHandle().problemPath(), LOGGER);

         Entity var2;
         try {
            var2 = net.minecraft.world.entity.EntityType.create(
                  TagValueInput.create(scopedCollector.forChild(() -> ".shoulder"), this.getHandle().registryAccess(), this.getHandle().getShoulderEntityLeft()),
                  this.getHandle().level(),
                  new EntitySpawnRequest(EntitySpawnReason.LOAD, false)
               )
               .<Entity>map(net.minecraft.world.entity.Entity::getBukkitEntity)
               .orElse(null);
         } catch (Throwable var5) {
            try {
               scopedCollector.close();
            } catch (Throwable var4) {
               var5.addSuppressed(var4);
            }

            throw var5;
         }

         scopedCollector.close();
         return var2;
      } else {
         return null;
      }
   }

   public void setShoulderEntityLeft(Entity entity) {
      if (entity != null) {
         Preconditions.checkArgument(
            ((CraftEntity)entity).getHandle().getType().canSerialize(), "Cannot set entity of type %s as a shoulder entity", entity.getType().getKey()
         );
      }

      this.getHandle().setShoulderEntityLeft(entity == null ? new CompoundTag() : ((CraftEntity)entity).save());
      if (entity != null) {
         entity.remove();
      }
   }

   public Entity getShoulderEntityRight() {
      if (!this.getHandle().getShoulderEntityRight().isEmpty()) {
         ScopedCollector scopedCollector = new ScopedCollector(this.getHandle().problemPath(), LOGGER);

         Entity var2;
         try {
            var2 = net.minecraft.world.entity.EntityType.create(
                  TagValueInput.create(
                     scopedCollector.forChild(() -> ".shoulder"), this.getHandle().registryAccess(), this.getHandle().getShoulderEntityRight()
                  ),
                  this.getHandle().level(),
                  new EntitySpawnRequest(EntitySpawnReason.LOAD, false)
               )
               .<Entity>map(net.minecraft.world.entity.Entity::getBukkitEntity)
               .orElse(null);
         } catch (Throwable var5) {
            try {
               scopedCollector.close();
            } catch (Throwable var4) {
               var5.addSuppressed(var4);
            }

            throw var5;
         }

         scopedCollector.close();
         return var2;
      } else {
         return null;
      }
   }

   public void setShoulderEntityRight(Entity entity) {
      if (entity != null) {
         Preconditions.checkArgument(
            ((CraftEntity)entity).getHandle().getType().canSerialize(), "Cannot set entity of type %s as a shoulder entity", entity.getType().getKey()
         );
      }

      this.getHandle().setShoulderEntityRight(entity == null ? new CompoundTag() : ((CraftEntity)entity).save());
      if (entity != null) {
         entity.remove();
      }
   }

   public void knockback(double strength, double directionX, double directionZ) {
      super.knockback(strength, directionX, directionZ);
      this.entity.hurtMarked = true;
   }

   public void unsetFixedPose() {
      this.getHandle().fixedPose = false;
      this.getHandle().updatePlayerPose();
   }
}
