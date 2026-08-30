package net.minecraft.server.players;

import com.destroystokyo.paper.console.TerminalConsoleCommandSender;
import com.destroystokyo.paper.event.player.PlayerPostRespawnEvent;
import com.destroystokyo.paper.event.profile.ProfileWhitelistVerifyEvent;
import com.destroystokyo.paper.profile.CraftPlayerProfile;
import com.google.common.collect.Collections2;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.mojang.authlib.GameProfile;
import com.mojang.logging.LogUtils;
import io.papermc.paper.FeatureHooks;
import io.papermc.paper.adventure.PaperAdventure;
import io.papermc.paper.annotation.DoNotUse;
import io.papermc.paper.configuration.GlobalConfiguration;
import io.papermc.paper.connection.DisconnectionReason;
import io.papermc.paper.event.player.PlayerServerFullCheckEvent;
import io.papermc.paper.util.MCUtil;
import java.io.File;
import java.io.IOException;
import java.net.SocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.util.BitSet;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.ComponentLike;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.LayeredRegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.Connection;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.OutgoingChatMessage;
import net.minecraft.network.chat.PlayerChatMessage;
import net.minecraft.network.chat.ChatType.Bound;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundUpdateTagsPacket;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundChangeDifficultyPacket;
import net.minecraft.network.protocol.game.ClientboundDisguisedChatPacket;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.network.protocol.game.ClientboundGameEventPacket;
import net.minecraft.network.protocol.game.ClientboundInitializeBorderPacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerAbilitiesPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.network.protocol.game.ClientboundSetDefaultSpawnPositionPacket;
import net.minecraft.network.protocol.game.ClientboundSetExperiencePacket;
import net.minecraft.network.protocol.game.ClientboundSetHeldSlotPacket;
import net.minecraft.network.protocol.game.ClientboundSetPlayerTeamPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.network.protocol.game.ClientboundUpdateAttributesPacket;
import net.minecraft.network.protocol.game.ClientboundUpdateMobEffectPacket;
import net.minecraft.network.protocol.game.ClientboundUpdateRecipesPacket;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.network.protocol.status.ServerStatus;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.PlayerAdvancements;
import net.minecraft.server.RegistryLayer;
import net.minecraft.server.ServerScoreboard;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.notifications.NotificationService;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.server.players.PlayerList.1;
import net.minecraft.server.players.PlayerList.2;
import net.minecraft.server.players.PlayerList.LoginResult;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.stats.ServerStatsCounter;
import net.minecraft.stats.Stats;
import net.minecraft.tags.TagNetworkSerialization;
import net.minecraft.util.FileUtil;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Entity.RemovalReason;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.npc.villager.AbstractVillager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.chunk.EmptyLevelChunk;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.PlayerDataStorage;
import net.minecraft.world.level.storage.LevelData.RespawnData;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.Team;
import org.bukkit.WeatherType;
import org.bukkit.craftbukkit.CraftServer;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.craftbukkit.util.CraftLocation;
import org.bukkit.event.entity.EntityRemoveEvent.Cause;
import org.bukkit.event.inventory.InventoryCloseEvent.Reason;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerLoginEvent.Result;
import org.bukkit.event.player.PlayerRespawnEvent.RespawnReason;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.spigotmc.SpigotConfig;

public abstract class PlayerList {
   public static final File USERBANLIST_FILE = new File("banned-players.json");
   public static final File IPBANLIST_FILE = new File("banned-ips.json");
   public static final File OPLIST_FILE = new File("ops.json");
   public static final File WHITELIST_FILE = new File("whitelist.json");
   public static final Component CHAT_FILTERED_FULL = Component.translatable("chat.filtered_full");
   public static final Component DUPLICATE_LOGIN_DISCONNECT_MESSAGE = Component.translatable("multiplayer.disconnect.duplicate_login");
   private static final Logger LOGGER = LogUtils.getLogger();
   private static final int SEND_PLAYER_INFO_INTERVAL = 600;
   private static final SimpleDateFormat BAN_DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd 'at' HH:mm:ss z", Locale.ROOT);
   private final MinecraftServer server;
   private final List<ServerPlayer> players = new CopyOnWriteArrayList<>();
   private final Map<UUID, ServerPlayer> playersByUUID = Maps.newHashMap();
   private final UserBanList bans;
   private final IpBanList ipBans;
   private final ServerOpList ops;
   private final UserWhiteList whitelist;
   public final PlayerDataStorage playerIo;
   private final LayeredRegistryAccess<RegistryLayer> registries;
   private int viewDistance;
   private int simulationDistance;
   private boolean allowCommandsForAllPlayers;
   private int sendAllPlayerInfoIn;
   private CraftServer cserver;
   private final Map<String, ServerPlayer> playersByName = new HashMap<>();
   public @Nullable String collideRuleTeamName;

   public PlayerList(
      MinecraftServer server, LayeredRegistryAccess<RegistryLayer> registries, PlayerDataStorage playerIo, NotificationService notificationService
   ) {
      this.cserver = server.server = new CraftServer((DedicatedServer)server, this);
      server.console = new TerminalConsoleCommandSender();
      this.server = server;
      this.registries = registries;
      this.playerIo = playerIo;
      this.whitelist = new UserWhiteList(WHITELIST_FILE, notificationService);
      this.ops = new ServerOpList(OPLIST_FILE, notificationService);
      this.bans = new UserBanList(USERBANLIST_FILE, notificationService);
      this.ipBans = new IpBanList(IPBANLIST_FILE, notificationService);
   }

   public abstract void loadAndSaveFiles();

   public void placeNewPlayer(Connection connection, ServerPlayer player, CommonListenerCookie cookie) {
      player.isRealPlayer = true;
      player.loginTime = System.currentTimeMillis();
      NameAndId gameProfile = player.nameAndId();
      UserNameToIdResolver profileCache = this.server.services().nameToIdCache();
      Optional<NameAndId> oldProfile = profileCache.get(gameProfile.id());
      String oldName = oldProfile.<String>map(NameAndId::name).orElse(gameProfile.name());
      if (player.lastKnownName != null) {
         oldName = player.lastKnownName;
         player.lastKnownName = null;
      }

      profileCache.add(gameProfile);
      ServerLevel level = player.level();
      String address = connection.getLoggableAddress(this.server.logIPs());
      LevelData levelData = level.getLevelData();
      ServerGamePacketListenerImpl playerConnection = new ServerGamePacketListenerImpl(this.server, connection, player, cookie);
      connection.setupInboundProtocol(
         GameProtocols.SERVERBOUND_TEMPLATE.bind(RegistryFriendlyByteBuf.decorator(this.server.registryAccess()), playerConnection), playerConnection
      );
      playerConnection.suspendFlushing();
      GameRules gameRules = level.getGameRules();
      boolean immediateRespawn = (Boolean)gameRules.get(GameRules.IMMEDIATE_RESPAWN);
      boolean reducedDebugInfo = (Boolean)gameRules.get(GameRules.REDUCED_DEBUG_INFO);
      boolean doLimitedCrafting = (Boolean)gameRules.get(GameRules.LIMITED_CRAFTING);
      playerConnection.send(
         new ClientboundLoginPacket(
            player.getId(),
            levelData.isHardcore(),
            this.server.levelKeys(),
            this.getMaxPlayers(),
            FeatureHooks.getViewDistance(level),
            FeatureHooks.getSimulationDistance(level),
            reducedDebugInfo,
            !immediateRespawn,
            doLimitedCrafting,
            player.createCommonSpawnInfo(level),
            this.server.usesAuthentication(),
            this.server.enforceSecureProfile()
         )
      );
      player.getBukkitEntity().sendSupportedChannels();
      playerConnection.send(new ClientboundChangeDifficultyPacket(levelData.getDifficulty(), levelData.isDifficultyLocked()));
      playerConnection.send(new ClientboundPlayerAbilitiesPacket(player.getAbilities()));
      playerConnection.send(new ClientboundSetHeldSlotPacket(player.getInventory().getSelectedSlot()));
      RecipeManager recipeManager = this.server.getRecipeManager();
      playerConnection.send(
         new ClientboundUpdateRecipesPacket(recipeManager.getSynchronizedItemProperties(), recipeManager.getSynchronizedStonecutterRecipes())
      );
      this.sendPlayerPermissionLevel(player);
      player.getStats().markAllDirty();
      player.getRecipeBook().sendInitialRecipeBook(player);
      this.updateEntireScoreboard(level.getScoreboard(), player);
      this.server.invalidateStatus();
      MutableComponent component;
      if (player.getGameProfile().name().equalsIgnoreCase(oldName)) {
         component = Component.translatable("multiplayer.player.joined", player.getDisplayName());
      } else {
         component = Component.translatable("multiplayer.player.joined.renamed", player.getDisplayName(), oldName);
      }

      component.withStyle(ChatFormatting.YELLOW);
      playerConnection.teleport(player.getX(), player.getY(), player.getZ(), player.getYRot(), player.getXRot());
      ServerStatus status = this.server.getStatus();
      if (status != null && !cookie.transferred()) {
         player.sendServerStatus(status);
      }

      this.players.add(player);
      this.playersByName.put(player.getScoreboardName().toLowerCase(Locale.ROOT), player);
      this.playersByUUID.put(player.getUUID(), player);
      player.suppressTrackerForLogin = true;
      this.sendLevelInfo(player, level);
      level.addNewPlayer(player);
      this.server.getCustomBossEvents().onPlayerConnect(player);
      player.initInventoryMenu();
      CraftPlayer bukkitPlayer = player.getBukkitEntity();
      player.containerMenu.transferTo(player.containerMenu, bukkitPlayer);
      PlayerJoinEvent playerJoinEvent = new PlayerJoinEvent(bukkitPlayer, PaperAdventure.asAdventure(component));
      this.cserver.getPluginManager().callEvent(playerJoinEvent);
      if (player.connection.isAcceptingMessages()) {
         net.kyori.adventure.text.Component jm = playerJoinEvent.joinMessage();
         if (jm != null && !jm.equals(net.kyori.adventure.text.Component.empty())) {
            Component joinMessage = PaperAdventure.asVanilla(jm);
            this.server.getPlayerList().broadcastSystemMessage(joinMessage, false);
         }

         ClientboundPlayerInfoUpdatePacket packet = ClientboundPlayerInfoUpdatePacket.createPlayerInitializing(List.of(player));
         List<ServerPlayer> onlinePlayers = Lists.newArrayListWithExpectedSize(this.players.size() - 1);

         for (int i = 0; i < this.players.size(); i++) {
            ServerPlayer entityplayer1 = this.players.get(i);
            if (entityplayer1.getBukkitEntity().canSee(bukkitPlayer)) {
               if (entityplayer1.getBukkitEntity().isListed(bukkitPlayer)) {
                  entityplayer1.connection.send(packet);
               } else {
                  entityplayer1.connection.send(ClientboundPlayerInfoUpdatePacket.createSinglePlayerInitializing(player, false));
               }
            }

            if (entityplayer1 != player && bukkitPlayer.canSee(entityplayer1.getBukkitEntity())) {
               onlinePlayers.add(entityplayer1);
            }
         }

         if (!onlinePlayers.isEmpty()) {
            player.connection.send(ClientboundPlayerInfoUpdatePacket.createPlayerInitializing(onlinePlayers, player));
         }

         player.sentListPacket = true;
         player.suppressTrackerForLogin = false;
         player.level().getChunkSource().addEntity(player);
         this.sendLevelInfo(player, level);
         if (player.level() == level && !level.players().contains(player)) {
            level.addNewPlayer(player);
            this.server.getCustomBossEvents().onPlayerConnect(player);
         }

         level = player.level();
         this.sendActivePlayerEffects(player);
         player.initInventoryMenu();
         this.server.notificationManager().playerJoined(player);
         playerConnection.resumeFlushing();
         Scoreboard scoreboard = this.getServer().getLevel(Level.OVERWORLD).getScoreboard();
         PlayerTeam collideRuleTeam = scoreboard.getPlayerTeam(this.collideRuleTeamName);
         if (this.collideRuleTeamName != null && collideRuleTeam != null && player.getTeam() == null) {
            scoreboard.addPlayerToTeam(player.getScoreboardName(), collideRuleTeam);
         }

         LOGGER.info(
            "{}[{}] logged in with entity id {} at ([{}]{}, {}, {})",
            new Object[]{player.getPlainTextName(), address, player.getId(), level.dimension().identifier(), player.getX(), player.getY(), player.getZ()}
         );
         if (player.isDeadOrDying()) {
            Holder<Biome> plains = level.registryAccess().lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.PLAINS);
            player.connection
               .send(
                  new ClientboundLevelChunkWithLightPacket(
                     new EmptyLevelChunk(level, player.chunkPosition(), plains), level.getLightEngine(), (BitSet)null, (BitSet)null, true
                  )
               );
         }
      }
   }

   public void updateEntireScoreboard(ServerScoreboard scoreboard, ServerPlayer player) {
      Set<Objective> objectives = Sets.newHashSet();

      for (PlayerTeam team : scoreboard.getPlayerTeams()) {
         player.connection.send(ClientboundSetPlayerTeamPacket.createAddOrModifyPacket(team, true));
      }

      for (DisplaySlot slot : DisplaySlot.values()) {
         Objective objective = scoreboard.getDisplayObjective(slot);
         if (objective != null && !objectives.contains(objective)) {
            for (Packet<?> packet : scoreboard.getStartTrackingPackets(objective)) {
               player.connection.send(packet);
            }

            objectives.add(objective);
         }
      }
   }

   private void broadcastWorldborder(Packet<?> packet, ResourceKey<Level> dimension) {
      for (ServerPlayer serverPlayer : this.players) {
         if (serverPlayer.level().dimension() == dimension && serverPlayer.getBukkitEntity().getWorldBorder() == null) {
            serverPlayer.connection.send(packet);
         }
      }
   }

   public void addWorldborderListener(ServerLevel level) {
      level.getWorldBorder().addListener(new 1(this, level));
   }

   public Optional<CompoundTag> loadPlayerData(NameAndId nameAndId) {
      UUID lastSingleplayerOwnerUUID = this.server.getWorldData().getSinglePlayerUUID();
      if (this.server.isSingleplayerOwner(nameAndId) && lastSingleplayerOwnerUUID != null) {
         LOGGER.debug("loading single player");
         return this.playerIo.load(new NameAndId(lastSingleplayerOwnerUUID, "<singleplayer owner>"));
      } else {
         return this.playerIo.load(nameAndId);
      }
   }

   protected void save(ServerPlayer player) {
      if (player.getBukkitEntity().isPersistent()) {
         player.lastSave = MinecraftServer.currentTick;
         this.playerIo.save(player);
         ServerStatsCounter stats = player.getStats();
         if (stats != null) {
            stats.save();
         }

         PlayerAdvancements advancements = player.getAdvancements();
         if (advancements != null) {
            advancements.save();
         }
      }
   }

   public @Nullable Component remove(ServerPlayer player) {
      return this.remove(
         player,
         net.kyori.adventure.text.Component.translatable(
            "multiplayer.player.left",
            NamedTextColor.YELLOW,
            new ComponentLike[]{
               GlobalConfiguration.get().messages.useDisplayNameInQuitMessage
                  ? player.getBukkitEntity().displayName()
                  : PaperAdventure.asAdventure(player.getDisplayName())
            }
         )
      );
   }

   public @Nullable Component remove(ServerPlayer player, net.kyori.adventure.text.Component leaveMessage) {
      ServerLevel level = player.level();
      player.awardStat(Stats.LEAVE_GAME);
      if (player.containerMenu != player.inventoryMenu) {
         player.closeContainer(Reason.DISCONNECT);
      }

      PlayerQuitEvent playerQuitEvent = new PlayerQuitEvent(player.getBukkitEntity(), leaveMessage, player.quitReason);
      this.cserver.getPluginManager().callEvent(playerQuitEvent);
      player.getBukkitEntity().disconnect();
      if (this.collideRuleTeamName != null) {
         Scoreboard scoreBoard = this.server.getLevel(Level.OVERWORLD).getScoreboard();
         PlayerTeam team = scoreBoard.getPlayersTeam(this.collideRuleTeamName);
         if (player.getTeam() == team && team != null) {
            scoreBoard.removePlayerFromTeam(player.getScoreboardName(), team);
         }
      }

      if (!player.containerMenu.getCarried().isEmpty()) {
         ItemStack carried = player.containerMenu.getCarried();
         player.containerMenu.setCarried(ItemStack.EMPTY);
         player.drop(carried, false);
      }

      this.save(player);
      if (player.isPassenger()) {
         Entity vehicle = player.getRootVehicle();
         if (vehicle.hasExactlyOnePlayerPassenger()) {
            LOGGER.debug("Removing player mount");
            player.stopRiding();
            vehicle.getPassengersAndSelf().forEach(e -> {
               if (e instanceof AbstractVillager villager) {
                  Player human = villager.getTradingPlayer();
                  if (human != null) {
                     villager.setTradingPlayer(null);
                  }
               }

               e.setRemoved(RemovalReason.UNLOADED_WITH_PLAYER, Cause.PLAYER_QUIT);
            });
         }
      }

      player.unRide();

      for (ThrownEnderpearl enderpearl : player.getEnderPearls()) {
         if (!enderpearl.level().paperConfig().misc.legacyEnderPearlBehavior) {
            enderpearl.setRemoved(RemovalReason.UNLOADED_WITH_PLAYER, Cause.PLAYER_QUIT);
         }
      }

      level.removePlayerImmediately(player, RemovalReason.UNLOADED_WITH_PLAYER);
      player.retireScheduler();
      player.getAdvancements().clearTriggers();
      this.players.remove(player);
      this.playersByName.remove(player.getScoreboardName().toLowerCase(Locale.ROOT));
      this.server.getCustomBossEvents().onPlayerDisconnect(player);
      UUID uuid = player.getUUID();
      ServerPlayer serverPlayer = this.playersByUUID.get(uuid);
      if (serverPlayer == player) {
         this.playersByUUID.remove(uuid);
         this.server.notificationManager().playerLeft(player);
      }

      ClientboundPlayerInfoRemovePacket packet = new ClientboundPlayerInfoRemovePacket(List.of(player.getUUID()));

      for (int i = 0; i < this.players.size(); i++) {
         ServerPlayer otherPlayer = this.players.get(i);
         if (otherPlayer.getBukkitEntity().canSee(player.getBukkitEntity())) {
            otherPlayer.connection.send(packet);
         } else {
            otherPlayer.getBukkitEntity().onEntityRemove(player);
         }
      }

      this.cserver.getScoreboardManager().removePlayer(player.getBukkitEntity());
      return playerQuitEvent.quitMessage();
   }

   public LoginResult canPlayerLogin(SocketAddress address, NameAndId nameAndId) {
      UserBanListEntry ban1;
      if (this.bans.isBanned(nameAndId) && (ban1 = (UserBanListEntry)this.bans.get(nameAndId)) != null) {
         UserBanListEntry ban = ban1;
         MutableComponent reason = Component.translatable("multiplayer.disconnect.banned.reason", ban.getReasonMessage());
         if (ban.getExpires() != null) {
            reason.append(Component.translatable("multiplayer.disconnect.banned.expiration", BAN_DATE_FORMAT.format(ban.getExpires())));
         }

         return new LoginResult(reason, Result.KICK_BANNED);
      } else {
         LoginResult whitelistEventResult;
         if ((whitelistEventResult = this.isWhiteListedLogin(nameAndId)).result == Result.KICK_WHITELIST) {
            return whitelistEventResult;
         }

         if (this.ipBans.isBanned(address)) {
            IpBanListEntry ban = this.ipBans.get(address);
            MutableComponent reason = Component.translatable("multiplayer.disconnect.banned_ip.reason", ban.getReasonMessage());
            if (ban.getExpires() != null) {
               reason.append(Component.translatable("multiplayer.disconnect.banned_ip.expiration", BAN_DATE_FORMAT.format(ban.getExpires())));
            }

            return new LoginResult(reason, Result.KICK_BANNED);
         } else {
            return this.canBypassFullServerLogin(nameAndId, new LoginResult(Component.translatable("multiplayer.disconnect.server_full"), Result.KICK_FULL));
         }
      }
   }

   public boolean disconnectAllPlayersWithProfile(GameProfile profile) {
      UUID playerId = profile.id();
      Set<ServerPlayer> dupes = Sets.newIdentityHashSet();

      for (ServerPlayer player : this.players) {
         if (player.getUUID().equals(playerId)
            || GlobalConfiguration.get().proxies.isProxyOnlineMode() && player.getGameProfile().name().equalsIgnoreCase(profile.name())) {
            dupes.add(player);
         }
      }

      ServerPlayer serverPlayer = this.playersByUUID.get(playerId);
      if (serverPlayer != null) {
         dupes.add(serverPlayer);
      }

      for (ServerPlayer player : dupes) {
         player.connection.disconnect(DUPLICATE_LOGIN_DISCONNECT_MESSAGE, DisconnectionReason.DUPLICATE_LOGIN_MESSAGE);
      }

      return !dupes.isEmpty();
   }

   public ServerPlayer respawn(ServerPlayer serverPlayer, boolean keepAllPlayerData, RemovalReason removalReason, RespawnReason respawnReason) {
      ServerPlayer.RespawnResult result = serverPlayer.findRespawnPositionAndUseSpawnBlock0(!keepAllPlayerData, TeleportTransition.DO_NOTHING, respawnReason);
      if (result == null) {
         return serverPlayer;
      }

      TeleportTransition respawnInfo = result.transition();
      Level fromLevel = serverPlayer.level();
      this.players.remove(serverPlayer);
      this.playersByName.remove(serverPlayer.getScoreboardName().toLowerCase(Locale.ROOT));
      serverPlayer.level().removePlayerImmediately(serverPlayer, removalReason);
      ServerLevel level = respawnInfo.newLevel();
      ServerPlayer player = serverPlayer;
      player.connection = serverPlayer.connection;
      player.restoreFrom(serverPlayer, keepAllPlayerData);
      player.setId(serverPlayer.getId());
      player.setMainArm(serverPlayer.getMainArm());

      for (String tag : serverPlayer.entityTags()) {
         player.addTag(tag);
      }

      if (!keepAllPlayerData) {
         serverPlayer.reset();
      }

      player.setServerLevel(level);
      player.unsetRemoved();
      player.setShiftKeyDown(false);
      Vec3 pos = respawnInfo.position();
      player.snapTo(pos.x, pos.y, pos.z, respawnInfo.yRot(), respawnInfo.xRot());
      player.connection.resetPosition();
      level.getChunkSource().addTicketWithRadius(TicketType.POST_TELEPORT, new ChunkPos(Mth.floor(pos.x()) >> 4, Mth.floor(pos.z()) >> 4), 1);
      if (respawnInfo.missingRespawnBlock()) {
         player.connection.send(new ClientboundGameEventPacket(ClientboundGameEventPacket.NO_RESPAWN_BLOCK_AVAILABLE, 0.0F));
         player.setRespawnPosition(null, false, com.destroystokyo.paper.event.player.PlayerSetSpawnEvent.Cause.PLAYER_RESPAWN);
      }

      byte dataToKeep = (byte)(keepAllPlayerData ? 1 : 0);
      ServerLevel playerLevel = player.level();
      LevelData levelData = playerLevel.getLevelData();
      player.connection.send(new ClientboundRespawnPacket(player.createCommonSpawnInfo(playerLevel), dataToKeep));
      player.connection.internalTeleport(player.getX(), player.getY(), player.getZ(), player.getYRot(), player.getXRot());
      player.connection.send(new ClientboundSetDefaultSpawnPositionPacket(level.getRespawnData()));
      player.connection.send(new ClientboundChangeDifficultyPacket(levelData.getDifficulty(), levelData.isDifficultyLocked()));
      player.connection.send(new ClientboundSetExperiencePacket(player.experienceProgress, player.totalExperience, player.experienceLevel));
      this.sendActivePlayerEffects(player);
      this.sendLevelInfo(player, level);
      this.sendPlayerPermissionLevel(player);
      level.addRespawnedPlayer(player);
      this.players.add(player);
      this.playersByName.put(player.getScoreboardName().toLowerCase(Locale.ROOT), player);
      this.playersByUUID.put(player.getUUID(), player);
      player.initInventoryMenu();
      player.setHealth(player.getHealth());
      this.sendAllPlayerInfo(serverPlayer);
      serverPlayer.onUpdateAbilities();
      ServerPlayer.RespawnConfig respawnConfig = player.getRespawnConfig();
      if (!keepAllPlayerData && respawnConfig != null) {
         RespawnData respawnData = respawnConfig.respawnData();
         ServerLevel respawnLevel = this.server.getLevel(respawnData.dimension());
         if (respawnLevel != null) {
            BlockPos respawnPosition = respawnData.pos();
            BlockState blockState = respawnLevel.getBlockState(respawnPosition);
            if (blockState.is(Blocks.RESPAWN_ANCHOR)) {
               player.connection
                  .send(
                     new ClientboundSoundPacket(
                        SoundEvents.RESPAWN_ANCHOR_DEPLETE,
                        SoundSource.BLOCKS,
                        respawnPosition.getX(),
                        respawnPosition.getY(),
                        respawnPosition.getZ(),
                        1.0F,
                        1.0F,
                        level.getRandom().nextLong()
                     )
                  );
            }
         }
      }

      if (player.connection.isDisconnected()) {
         this.save(player);
      }

      if (fromLevel != level) {
         new PlayerChangedWorldEvent(player.getBukkitEntity(), fromLevel.getWorld()).callEvent();
         player.triggerDimensionChangeTriggers(level);
      }

      new PlayerPostRespawnEvent(
            player.getBukkitEntity(),
            CraftLocation.toBukkit(respawnInfo.position(), level, respawnInfo.yRot(), respawnInfo.xRot()),
            result.isBedSpawn(),
            result.isAnchorSpawn(),
            respawnInfo.missingRespawnBlock(),
            respawnReason
         )
         .callEvent();
      return player;
   }

   public void sendActivePlayerEffects(ServerPlayer player) {
      this.sendActiveEffects(player, player.connection);
   }

   public void sendActiveEffects(LivingEntity livingEntity, ServerGamePacketListenerImpl connection) {
      this.sendActiveEffects(livingEntity, connection::send);
   }

   public void sendActiveEffects(LivingEntity livingEntity, Consumer<Packet<? super ClientGamePacketListener>> packetConsumer) {
      for (MobEffectInstance effect : livingEntity.getActiveEffects()) {
         packetConsumer.accept(new ClientboundUpdateMobEffectPacket(livingEntity.getId(), effect, false));
      }
   }

   public void sendPlayerPermissionLevel(ServerPlayer player) {
      this.sendPlayerPermissionLevel(player, true);
   }

   public void sendPlayerPermissionLevel(ServerPlayer player, boolean recalculatePermissions) {
      LevelBasedPermissionSet permissions = this.server.getProfilePermissions(player.nameAndId());
      this.sendPlayerPermissionLevel(player, permissions, recalculatePermissions);
   }

   public void tick() {
      if (++this.sendAllPlayerInfoIn > 600) {
         for (int i = 0; i < this.players.size(); i++) {
            ServerPlayer target = this.players.get(i);
            target.connection
               .send(
                  new ClientboundPlayerInfoUpdatePacket(
                     EnumSet.of(ClientboundPlayerInfoUpdatePacket.Action.UPDATE_LATENCY),
                     Collections2.filter(this.players, t -> target.getBukkitEntity().canSee(t.getBukkitEntity()))
                  )
               );
         }

         this.sendAllPlayerInfoIn = 0;
      }
   }

   public void broadcastAll(Packet packet, Player entityhuman) {
      for (ServerPlayer entityplayer : this.players) {
         if (entityhuman == null || entityplayer.getBukkitEntity().canSee(entityhuman.getBukkitEntity())) {
            entityplayer.connection.send(packet);
         }
      }
   }

   public void broadcastAll(Packet packet, Level world) {
      for (int i = 0; i < world.players().size(); i++) {
         ((ServerPlayer)world.players().get(i)).connection.send(packet);
      }
   }

   public void broadcastAll(Packet<?> packet) {
      for (ServerPlayer player : this.players) {
         player.connection.send(packet);
      }
   }

   public void broadcastAll(Packet<?> packet, ResourceKey<Level> dimension) {
      for (ServerPlayer player : this.players) {
         if (player.level().dimension() == dimension) {
            player.connection.send(packet);
         }
      }
   }

   public void broadcastSystemToTeam(Player player, Component message) {
      Team team = player.getTeam();
      if (team != null) {
         for (String name : team.getPlayers()) {
            ServerPlayer teamPlayer = this.getPlayerByName(name);
            if (teamPlayer != null && teamPlayer != player) {
               teamPlayer.sendSystemMessage(message);
            }
         }
      }
   }

   public void broadcastSystemToAllExceptTeam(Player player, Component message) {
      Team team = player.getTeam();
      if (team == null) {
         this.broadcastSystemMessage(message, false);
      } else {
         for (int i = 0; i < this.players.size(); i++) {
            ServerPlayer targetPlayer = this.players.get(i);
            if (targetPlayer.getTeam() != team) {
               targetPlayer.sendSystemMessage(message);
            }
         }
      }
   }

   public String[] getPlayerNamesArray() {
      String[] names = new String[this.players.size()];

      for (int i = 0; i < this.players.size(); i++) {
         names[i] = this.players.get(i).getGameProfile().name();
      }

      return names;
   }

   public UserBanList getBans() {
      return this.bans;
   }

   public IpBanList getIpBans() {
      return this.ipBans;
   }

   public void op(NameAndId nameAndId) {
      this.op(nameAndId, Optional.empty(), Optional.empty());
   }

   public void op(NameAndId nameAndId, Optional<LevelBasedPermissionSet> permissions, Optional<Boolean> canBypassPlayerLimit) {
      this.ops
         .add(
            new ServerOpListEntry(
               nameAndId, permissions.orElse(this.server.operatorUserPermissions()), canBypassPlayerLimit.orElse(this.ops.canBypassPlayerLimit(nameAndId))
            )
         );
      ServerPlayer player = this.getPlayer(nameAndId.id());
      if (player != null) {
         this.sendPlayerPermissionLevel(player);
      }
   }

   public void deop(NameAndId nameAndId) {
      if (this.ops.remove(nameAndId)) {
         ServerPlayer player = this.getPlayer(nameAndId.id());
         if (player != null) {
            this.sendPlayerPermissionLevel(player);
         }
      }
   }

   private void sendPlayerPermissionLevel(ServerPlayer player, LevelBasedPermissionSet permissions) {
      this.sendPlayerPermissionLevel(player, permissions, true);
   }

   // $VF: Unable to simplify switch on enum
   // Please report this to the Vineflower issue tracker, at https://github.com/Vineflower/vineflower/issues with a copy of the class file (if you have the rights to distribute it!)
   public void sendPlayerPermissionLevel(ServerPlayer player, LevelBasedPermissionSet permissions, boolean recalculatePermissions) {
      if (player.connection != null) {
         byte eventId = switch (2.$SwitchMap$net$minecraft$server$permissions$PermissionLevel[permissions.level().ordinal()]) {
            case 1 -> 24;
            case 2 -> 25;
            case 3 -> 26;
            case 4 -> 27;
            case 5 -> 28;
            default -> throw new MatchException(null, null);
         };
         player.connection.send(new ClientboundEntityEventPacket(player, eventId));
      }

      if (recalculatePermissions) {
         player.getBukkitEntity().recalculatePermissions();
         this.server.getCommands().sendCommands(player);
      }
   }

   public LoginResult canBypassFullServerLogin(NameAndId nameAndId, LoginResult currentResult) {
      boolean shouldKick = this.players.size() >= this.getMaxPlayers() && !this.canBypassPlayerLimit(nameAndId);
      PlayerServerFullCheckEvent fullCheckEvent = new PlayerServerFullCheckEvent(
         new CraftPlayerProfile(nameAndId), PaperAdventure.asAdventure(currentResult.message), shouldKick
      );
      fullCheckEvent.callEvent();
      return fullCheckEvent.isAllowed() ? LoginResult.ALLOW : new LoginResult(PaperAdventure.asVanilla(fullCheckEvent.kickMessage()), currentResult.result);
   }

   public LoginResult isWhiteListedLogin(NameAndId nameAndId) {
      boolean isOp = this.ops.contains(nameAndId);
      boolean isWhitelisted = !this.isUsingWhitelist() || isOp || this.whitelist.contains(nameAndId);
      net.kyori.adventure.text.Component configuredMessage = LegacyComponentSerializer.legacySection().deserialize(SpigotConfig.whitelistMessage);
      ProfileWhitelistVerifyEvent event = new ProfileWhitelistVerifyEvent(
         new CraftPlayerProfile(nameAndId), this.isUsingWhitelist(), isWhitelisted, isOp, configuredMessage
      );
      event.callEvent();
      return !event.isWhitelisted()
         ? new LoginResult(PaperAdventure.asVanilla(event.kickMessage() == null ? configuredMessage : event.kickMessage()), Result.KICK_WHITELIST)
         : LoginResult.ALLOW;
   }

   @DoNotUse
   public boolean isWhiteListed(NameAndId nameAndId) {
      return !this.isUsingWhitelist() || this.ops.contains(nameAndId) || this.whitelist.contains(nameAndId);
   }

   public boolean isOp(NameAndId nameAndId) {
      if (this.ops.contains(nameAndId)) {
         return true;
      } else {
         return this.server.isSingleplayerOwner(nameAndId) ? this.server.getWorldData().isAllowCommands() : this.allowCommandsForAllPlayers;
      }
   }

   public @Nullable ServerPlayer getPlayerByName(String name) {
      return this.playersByName.get(name.toLowerCase(Locale.ROOT));
   }

   public void broadcast(@Nullable Player except, double x, double y, double z, double range, ResourceKey<Level> dimension, Packet<?> packet) {
      for (int i = 0; i < this.players.size(); i++) {
         ServerPlayer player = this.players.get(i);
         if ((except == null || player.getBukkitEntity().canSee(except.getBukkitEntity())) && player != except && player.level().dimension() == dimension) {
            double xd = x - player.getX();
            double yd = y - player.getY();
            double zd = z - player.getZ();
            if (xd * xd + yd * yd + zd * zd < range * range) {
               player.connection.send(packet);
            }
         }
      }
   }

   public void saveAll() {
      this.saveAll(-1);
   }

   public void saveAll(int interval) {
      MCUtil.ensureMain("Save Players", () -> {
         int numSaved = 0;
         long now = MinecraftServer.currentTick;

         for (ServerPlayer player : this.players) {
            if (interval == -1 || now - player.lastSave >= interval) {
               this.save(player);
               if (interval != -1) {
                  if (++numSaved >= GlobalConfiguration.get().playerAutoSave.maxPerTick()) {
                     break;
                  }
               }
            }
         }

         return null;
      });
   }

   public UserWhiteList getWhiteList() {
      return this.whitelist;
   }

   public String[] getWhiteListNames() {
      return this.whitelist.getUserList();
   }

   public ServerOpList getOps() {
      return this.ops;
   }

   public String[] getOpNames() {
      return this.ops.getUserList();
   }

   public void reloadWhiteList() {
   }

   public void sendLevelInfo(ServerPlayer player, ServerLevel level) {
      WorldBorder worldBorder = level.getWorldBorder();
      player.connection.send(new ClientboundInitializeBorderPacket(worldBorder));
      player.connection.send(level.clockManager().createFullSyncPacket(player));
      player.connection.send(new ClientboundSetDefaultSpawnPositionPacket(level.getRespawnData()));
      if (level.isRaining()) {
         player.setPlayerWeather(WeatherType.DOWNFALL, false);
         player.updateWeather(-level.rainLevel, level.rainLevel, -level.thunderLevel, level.thunderLevel);
      }

      player.connection.send(new ClientboundGameEventPacket(ClientboundGameEventPacket.LEVEL_CHUNKS_LOAD_START, 0.0F));
      this.server.tickRateManager().updateJoiningPlayer(player);
   }

   public void sendAllPlayerInfo(ServerPlayer player) {
      player.inventoryMenu.sendAllDataToRemote();
      Collection<AttributeInstance> syncableAttributes = player.getAttributes().getSyncableAttributes();
      player.getBukkitEntity().injectScaledMaxHealth(syncableAttributes, true);
      player.connection.send(new ClientboundUpdateAttributesPacket(player.getId(), syncableAttributes));
      player.refreshEntityData(player);
      player.connection.send(new ClientboundSetHeldSlotPacket(player.getInventory().getSelectedSlot()));
      int i = player.level().getGameRules().get(GameRules.REDUCED_DEBUG_INFO) ? 22 : 23;
      player.connection.send(new ClientboundEntityEventPacket(player, (byte)i));
      float immediateRespawn = player.level().getGameRules().get(GameRules.IMMEDIATE_RESPAWN) ? 1.0F : 0.0F;
      player.connection.send(new ClientboundGameEventPacket(ClientboundGameEventPacket.IMMEDIATE_RESPAWN, immediateRespawn));
   }

   public int getPlayerCount() {
      return this.players.size();
   }

   public int getMaxPlayers() {
      return this.server.getMaxPlayers();
   }

   public boolean isUsingWhitelist() {
      return this.server.isUsingWhitelist();
   }

   public List<ServerPlayer> getPlayersWithAddress(String ip) {
      List<ServerPlayer> result = Lists.newArrayList();

      for (ServerPlayer player : this.players) {
         if (player.getIpAddress().equals(ip)) {
            result.add(player);
         }
      }

      return result;
   }

   public int getViewDistance() {
      return this.viewDistance;
   }

   public int getSimulationDistance() {
      return this.simulationDistance;
   }

   public MinecraftServer getServer() {
      return this.server;
   }

   public void setAllowCommandsForAllPlayers(boolean allowCommands) {
      this.allowCommandsForAllPlayers = allowCommands;
   }

   public void removeAll() {
      this.removeAll(false);
   }

   public void removeAll(boolean isRestarting) {
      for (ServerPlayer player : this.players) {
         if (isRestarting) {
            player.connection
               .disconnect(
                  LegacyComponentSerializer.legacySection().deserialize(SpigotConfig.restartMessage), org.bukkit.event.player.PlayerKickEvent.Cause.UNKNOWN
               );
         } else {
            player.connection.disconnect(Objects.requireNonNullElseGet(this.server.server.shutdownMessage(), net.kyori.adventure.text.Component::empty));
         }
      }

      if (this.collideRuleTeamName != null) {
         Scoreboard scoreboard = this.getServer().getLevel(Level.OVERWORLD).getScoreboard();
         PlayerTeam team = scoreboard.getPlayersTeam(this.collideRuleTeamName);
         if (team != null) {
            scoreboard.removePlayerTeam(team);
         }
      }
   }

   public void broadcastSystemMessage(Component message, boolean overlay) {
      this.broadcastSystemMessage(message, player -> message, overlay);
   }

   public void broadcastSystemMessage(Component message, Function<ServerPlayer, Component> playerMessages, boolean overlay) {
      this.server.sendSystemMessage(message);

      for (ServerPlayer player : this.players) {
         Component playerMessage = playerMessages.apply(player);
         if (playerMessage != null) {
            player.sendSystemMessage(playerMessage, overlay);
         }
      }
   }

   public void broadcastChatMessage(PlayerChatMessage message, CommandSourceStack sender, Bound chatType) {
      this.broadcastChatMessage(message, sender::shouldFilterMessageTo, sender.getPlayer(), chatType);
   }

   public void broadcastChatMessage(PlayerChatMessage message, ServerPlayer sender, Bound chatType) {
      this.broadcastChatMessage(message, sender, chatType, null);
   }

   public void broadcastChatMessage(PlayerChatMessage message, ServerPlayer sender, Bound chatType, @Nullable Function<Audience, Component> unsignedFunction) {
      this.broadcastChatMessage(message, sender::shouldFilterMessageTo, sender, chatType, unsignedFunction);
   }

   private void broadcastChatMessage(PlayerChatMessage message, Predicate<ServerPlayer> isFiltered, @Nullable ServerPlayer senderPlayer, Bound chatType) {
      this.broadcastChatMessage(message, isFiltered, senderPlayer, chatType, null);
   }

   public void broadcastChatMessage(
      PlayerChatMessage message,
      Predicate<ServerPlayer> isFiltered,
      @Nullable ServerPlayer senderPlayer,
      Bound chatType,
      @Nullable Function<Audience, Component> unsignedFunction
   ) {
      boolean trusted = this.verifyChatTrusted(message);
      this.server
         .logChatMessage(
            unsignedFunction == null ? message.decoratedContent() : unsignedFunction.apply(this.server.console), chatType, trusted ? null : "Not Secure"
         );
      OutgoingChatMessage tracked = OutgoingChatMessage.create(message);
      boolean wasFullyFiltered = false;
      Packet<?> disguised = senderPlayer != null && unsignedFunction == null ? new ClientboundDisguisedChatPacket(tracked.content(), chatType) : null;

      for (ServerPlayer player : this.players) {
         boolean filtered = isFiltered.test(player);
         if (senderPlayer != null && !player.getBukkitEntity().canSee(senderPlayer.getBukkitEntity())) {
            player.connection
               .send(
                  (Packet)(unsignedFunction != null
                     ? new ClientboundDisguisedChatPacket(unsignedFunction.apply(player.getBukkitEntity()), chatType)
                     : disguised)
               );
         } else {
            player.sendChatMessage(tracked, filtered, chatType, unsignedFunction == null ? null : unsignedFunction.apply(player.getBukkitEntity()));
            wasFullyFiltered |= filtered && message.isFullyFiltered();
         }
      }

      if (wasFullyFiltered && senderPlayer != null) {
         senderPlayer.sendSystemMessage(CHAT_FILTERED_FULL);
      }
   }

   public boolean verifyChatTrusted(PlayerChatMessage message) {
      return message.hasSignature() && !message.hasExpiredServer(Instant.now());
   }

   public ServerStatsCounter getPlayerStats(ServerPlayer player) {
      GameProfile gameProfile = player.getGameProfile();
      ServerStatsCounter playerStatsCounter = player.getStats();
      return playerStatsCounter == null ? this.getPlayerStats(gameProfile) : playerStatsCounter;
   }

   public ServerStatsCounter getPlayerStats(GameProfile gameProfile) {
      Path targetFile = this.locateStatsFile(gameProfile);
      return new ServerStatsCounter(this.server, targetFile);
   }

   private Path locateStatsFile(GameProfile gameProfile) {
      Path statFolder = this.server.getWorldPath(LevelResource.PLAYER_STATS_DIR);
      Path uuidStatsFile = statFolder.resolve(gameProfile.id() + ".json");
      if (Files.exists(uuidStatsFile)) {
         return uuidStatsFile;
      }

      String playerNameStatsFile = gameProfile.name() + ".json";
      if (FileUtil.isValidPathSegment(playerNameStatsFile)) {
         Path playerNameStatsPath = statFolder.resolve(playerNameStatsFile);
         if (Files.isRegularFile(playerNameStatsPath)) {
            try {
               return Files.move(playerNameStatsPath, uuidStatsFile);
            } catch (IOException e) {
               LOGGER.warn("Failed to copy file {} to {}", playerNameStatsFile, uuidStatsFile);
               return playerNameStatsPath;
            }
         }
      }

      return uuidStatsFile;
   }

   public PlayerAdvancements getPlayerAdvancements(ServerPlayer player) {
      UUID uuid = player.getUUID();
      PlayerAdvancements result = player.getAdvancements();
      if (result == null) {
         Path uuidStatsFile = this.server.getWorldPath(LevelResource.PLAYER_ADVANCEMENTS_DIR).resolve(uuid + ".json");
         result = new PlayerAdvancements(this.server.getFixerUpper(), this, this.server.getAdvancements(), uuidStatsFile, player);
      }

      result.setPlayer(player);
      return result;
   }

   public void setViewDistance(int viewDistance) {
      this.viewDistance = viewDistance;

      for (ServerLevel level : this.server.getAllLevels()) {
         level.getChunkSource().setViewDistance(viewDistance);
      }
   }

   public void setSimulationDistance(int simulationDistance) {
      this.simulationDistance = simulationDistance;

      for (ServerLevel level : this.server.getAllLevels()) {
         level.getChunkSource().setSimulationDistance(simulationDistance);
      }
   }

   public List<ServerPlayer> getPlayers() {
      return this.players;
   }

   public Map<UUID, ServerPlayer> getPlayersByUUID() {
      return this.playersByUUID;
   }

   public @Nullable ServerPlayer getPlayer(UUID uuid) {
      return this.playersByUUID.get(uuid);
   }

   public @Nullable ServerPlayer getPlayer(String playerName) {
      for (ServerPlayer player : this.players) {
         if (player.getGameProfile().name().equalsIgnoreCase(playerName)) {
            return player;
         }
      }

      return null;
   }

   public boolean canBypassPlayerLimit(NameAndId nameAndId) {
      return false;
   }

   public void reloadResources() {
      this.reloadAdvancementData();
      this.reloadTagData();
      this.reloadRecipes();
   }

   public void reloadAdvancementData() {
      for (ServerPlayer player : this.players) {
         player.getAdvancements().reload(this.server.getAdvancements());
         player.getAdvancements().flushDirty(player, false);
      }
   }

   public void reloadTagData() {
      this.broadcastAll(new ClientboundUpdateTagsPacket(TagNetworkSerialization.serializeTagsToNetwork(this.registries)));
   }

   public void reloadRecipes() {
      RecipeManager recipeManager = this.server.getRecipeManager();
      ClientboundUpdateRecipesPacket recipes = new ClientboundUpdateRecipesPacket(
         recipeManager.getSynchronizedItemProperties(), recipeManager.getSynchronizedStonecutterRecipes()
      );

      for (ServerPlayer player : this.players) {
         player.connection.send(recipes);
         player.getRecipeBook().sendInitialRecipeBook(player);
      }
   }

   public boolean isAllowCommandsForAllPlayers() {
      return this.allowCommandsForAllPlayers;
   }
}
