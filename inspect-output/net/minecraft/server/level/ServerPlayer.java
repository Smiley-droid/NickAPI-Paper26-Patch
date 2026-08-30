package net.minecraft.server.level;

import ca.spottedleaf.moonrise.patches.chunk_system.player.ChunkSystemServerPlayer;
import ca.spottedleaf.moonrise.patches.chunk_system.player.RegionizedPlayerChunkLoader.PlayerChunkLoaderData;
import ca.spottedleaf.moonrise.patches.chunk_system.player.RegionizedPlayerChunkLoader.ViewDistanceHolder;
import com.destroystokyo.paper.ClientOption;
import com.destroystokyo.paper.PaperSkinParts;
import com.destroystokyo.paper.ClientOption.ChatVisibility;
import com.destroystokyo.paper.ClientOption.ParticleVisibility;
import com.destroystokyo.paper.event.entity.PlayerNaturallySpawnCreaturesEvent;
import com.destroystokyo.paper.event.player.PlayerClientOptionsChangeEvent;
import com.destroystokyo.paper.event.player.PlayerSetSpawnEvent;
import com.destroystokyo.paper.event.player.PlayerStartSpectatingEntityEvent;
import com.destroystokyo.paper.event.player.PlayerStopSpectatingEntityEvent;
import com.destroystokyo.paper.event.player.PlayerTeleportEndGatewayEvent;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.hash.HashCode;
import com.google.common.net.InetAddresses;
import com.mojang.authlib.GameProfile;
import com.mojang.datafixers.util.Either;
import com.mojang.datafixers.util.Pair;
import com.mojang.logging.LogUtils;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.papermc.paper.adventure.PaperAdventure;
import io.papermc.paper.configuration.GlobalConfiguration;
import io.papermc.paper.event.player.PlayerInventorySlotChangeEvent;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.translation.Translator;
import net.minecraft.ChatFormatting;
import net.minecraft.CrashReport;
import net.minecraft.CrashReportCategory;
import net.minecraft.ReportedException;
import net.minecraft.advancements.triggers.CriteriaTriggers;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.EntityAnchorArgument.Anchor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.Holder;
import net.minecraft.core.Position;
import net.minecraft.core.SectionPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.component.TypedDataComponent;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.chat.ChatType;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.OutgoingChatMessage;
import net.minecraft.network.chat.RemoteChatSession;
import net.minecraft.network.chat.ResolutionContext;
import net.minecraft.network.chat.HoverEvent.ShowText;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundShowDialogPacket;
import net.minecraft.network.protocol.game.ClientboundAnimatePacket;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundChangeDifficultyPacket;
import net.minecraft.network.protocol.game.ClientboundContainerClosePacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetDataPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.network.protocol.game.ClientboundGameEventPacket;
import net.minecraft.network.protocol.game.ClientboundHurtAnimationPacket;
import net.minecraft.network.protocol.game.ClientboundMerchantOffersPacket;
import net.minecraft.network.protocol.game.ClientboundMountScreenOpenPacket;
import net.minecraft.network.protocol.game.ClientboundOpenBookPacket;
import net.minecraft.network.protocol.game.ClientboundOpenScreenPacket;
import net.minecraft.network.protocol.game.ClientboundOpenSignEditorPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerAbilitiesPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerCombatEndPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerCombatEnterPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerCombatKillPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerLookAtPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerRotationPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveMobEffectPacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.network.protocol.game.ClientboundServerDataPacket;
import net.minecraft.network.protocol.game.ClientboundSetCameraPacket;
import net.minecraft.network.protocol.game.ClientboundSetCursorItemPacket;
import net.minecraft.network.protocol.game.ClientboundSetExperiencePacket;
import net.minecraft.network.protocol.game.ClientboundSetHealthPacket;
import net.minecraft.network.protocol.game.ClientboundSetPassengersPacket;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.network.protocol.game.ClientboundUpdateMobEffectPacket;
import net.minecraft.network.protocol.game.CommonPlayerSpawnInfo;
import net.minecraft.network.protocol.status.ServerStatus;
import net.minecraft.network.protocol.status.ServerStatus.Favicon;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.PlayerAdvancements;
import net.minecraft.server.dialog.Dialog;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.network.TextFilter;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.server.players.PlayerList;
import net.minecraft.server.waypoints.ServerWaypointManager;
import net.minecraft.stats.ServerRecipeBook;
import net.minecraft.stats.ServerStatsCounter;
import net.minecraft.stats.Stat;
import net.minecraft.stats.Stats;
import net.minecraft.stats.ServerRecipeBook.Packed;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.HashOps;
import net.minecraft.util.Mth;
import net.minecraft.util.Unit;
import net.minecraft.util.Util;
import net.minecraft.util.ProblemReporter.ScopedCollector;
import net.minecraft.util.debug.DebugSubscription;
import net.minecraft.util.profiling.Profiler;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.Container;
import net.minecraft.world.Difficulty;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.attribute.BedRule;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.damagesource.CombatTracker;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntitySpawnRequest;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.NeutralMob;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.Entity.DefaultDrop;
import net.minecraft.world.entity.Entity.RemovalReason;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation;
import net.minecraft.world.entity.animal.equine.AbstractHorse;
import net.minecraft.world.entity.animal.happyghast.HappyGhast;
import net.minecraft.world.entity.animal.nautilus.AbstractNautilus;
import net.minecraft.world.entity.animal.parrot.Parrot;
import net.minecraft.world.entity.animal.pig.Pig;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.monster.Strider;
import net.minecraft.world.entity.monster.warden.WardenSpawnTracker;
import net.minecraft.world.entity.player.ChatVisiblity;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.player.Player.BedSleepingProblem;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;
import net.minecraft.world.food.FoodData;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerListener;
import net.minecraft.world.inventory.ContainerSynchronizer;
import net.minecraft.world.inventory.HorseInventoryMenu;
import net.minecraft.world.inventory.NautilusInventoryMenu;
import net.minecraft.world.inventory.RemoteSlot;
import net.minecraft.world.inventory.ResultSlot;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.inventory.RemoteSlot.Synchronized;
import net.minecraft.world.item.ItemCooldowns;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.MapItem;
import net.minecraft.world.item.ServerItemCooldowns;
import net.minecraft.world.item.component.WrittenBookContent;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.enchantment.EnchantmentEffectComponents;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.trading.MerchantOffers;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EndGatewayBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.RespawnAnchorBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.CommandBlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.TheEndGatewayBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.level.portal.TeleportTransition.PostTeleportTransition;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.level.storage.LevelData.RespawnData;
import net.minecraft.world.level.storage.ValueOutput.ValueOutputList;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.ScoreAccess;
import net.minecraft.world.scores.ScoreHolder;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.Team;
import net.minecraft.world.scores.TeamColor;
import net.minecraft.world.scores.Team.Visibility;
import net.minecraft.world.scores.criteria.ObjectiveCriteria;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.WeatherType;
import org.bukkit.command.CommandSender;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.craftbukkit.CraftWorldBorder;
import org.bukkit.craftbukkit.block.CraftBlock;
import org.bukkit.craftbukkit.block.CraftEndGateway;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.craftbukkit.event.CraftEventFactory;
import org.bukkit.craftbukkit.inventory.CraftItemStack;
import org.bukkit.craftbukkit.inventory.CraftInventoryDoubleChest.Provider;
import org.bukkit.craftbukkit.util.CraftDimensionUtil;
import org.bukkit.craftbukkit.util.CraftLocation;
import org.bukkit.entity.Item;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason;
import org.bukkit.event.entity.EntityExhaustionEvent.ExhaustionReason;
import org.bukkit.event.entity.EntityRegainHealthEvent.RegainReason;
import org.bukkit.event.inventory.InventoryCloseEvent.Reason;
import org.bukkit.event.player.PlayerBedLeaveEvent;
import org.bukkit.event.player.PlayerChangedMainHandEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerLocaleChangeEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerSpawnChangeEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent.Cause;
import org.bukkit.event.player.PlayerQuitEvent.QuitReason;
import org.bukkit.event.player.PlayerRespawnEvent.RespawnReason;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.MainHand;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

public class ServerPlayer extends Player implements ChunkSystemServerPlayer {
   private static final Logger LOGGER = LogUtils.getLogger();
   public long lastSave = MinecraftServer.currentTick;
   private static final int NEUTRAL_MOB_DEATH_NOTIFICATION_RADII_XZ = 32;
   private static final int NEUTRAL_MOB_DEATH_NOTIFICATION_RADII_Y = 10;
   private static final int FLY_STAT_RECORDING_SPEED = 25;
   public static final double BLOCK_INTERACTION_DISTANCE_VERIFICATION_BUFFER = 1.0;
   public static final double ENTITY_INTERACTION_DISTANCE_VERIFICATION_BUFFER = 3.0;
   public static final int ENDER_PEARL_TICKET_RADIUS = 2;
   public static final String ENDER_PEARLS_TAG = "ender_pearls";
   public static final String ENDER_PEARL_DIMENSION_TAG = "ender_pearl_dimension";
   public static final String TAG_DIMENSION = "Dimension";
   private static final AttributeModifier CREATIVE_BLOCK_INTERACTION_RANGE_MODIFIER = new AttributeModifier(
      Identifier.withDefaultNamespace("creative_mode_block_range"), 0.5, Operation.ADD_VALUE
   );
   private static final AttributeModifier CREATIVE_ENTITY_INTERACTION_RANGE_MODIFIER = new AttributeModifier(
      Identifier.withDefaultNamespace("creative_mode_entity_range"), 2.0, Operation.ADD_VALUE
   );
   private static final net.minecraft.network.chat.Component SPAWN_SET_MESSAGE = net.minecraft.network.chat.Component.translatable("block.minecraft.set_spawn");
   private static final AttributeModifier WAYPOINT_TRANSMIT_RANGE_CROUCH_MODIFIER = new AttributeModifier(
      Identifier.withDefaultNamespace("waypoint_transmit_range_crouch"), -1.0, Operation.ADD_MULTIPLIED_TOTAL
   );
   private static final boolean DEFAULT_SEEN_CREDITS = false;
   private static final boolean DEFAULT_SPAWN_EXTRA_PARTICLES_ON_FALL = false;
   public ServerGamePacketListenerImpl connection;
   private final MinecraftServer server;
   public final ServerPlayerGameMode gameMode;
   private final PlayerAdvancements advancements;
   private final ServerStatsCounter stats;
   private float lastRecordedHealthAndAbsorption = Float.MIN_VALUE;
   private int lastRecordedFoodLevel = Integer.MIN_VALUE;
   private int lastRecordedAirLevel = Integer.MIN_VALUE;
   private int lastRecordedArmor = Integer.MIN_VALUE;
   private int lastRecordedLevel = Integer.MIN_VALUE;
   private int lastRecordedExperience = Integer.MIN_VALUE;
   private float lastSentHealth = -1.0E8F;
   private int lastSentFood = -99999999;
   private boolean lastFoodSaturationZero = true;
   public int lastSentExp = -99999999;
   private ChatVisiblity chatVisibility = ChatVisiblity.FULL;
   public ParticleStatus particleStatus = ParticleStatus.ALL;
   private boolean canChatColor = true;
   private long lastActionTime = Util.getMillis();
   private @Nullable Entity camera;
   private boolean isChangingDimension;
   public boolean seenCredits = false;
   private final ServerRecipeBook recipeBook;
   private @Nullable Vec3 levitationStartPos;
   private int levitationStartTime;
   private boolean disconnected;
   private int requestedViewDistance = 2;
   public String language = null;
   public Locale adventure$locale = Locale.US;
   private @Nullable Vec3 startingToFallPosition;
   private @Nullable Vec3 enteredNetherPosition;
   private @Nullable Vec3 enteredLavaOnVehiclePosition;
   private @Nullable Vec3 currentExplosionImpactPos;
   private @Nullable Entity currentExplosionCause;
   private SectionPos lastSectionPos = SectionPos.of(0, 0, 0);
   private ChunkTrackingView chunkTrackingView = ChunkTrackingView.EMPTY;
   private ServerPlayer.@Nullable RespawnConfig respawnConfig;
   private final TextFilter textFilter;
   private boolean textFilteringEnabled;
   private boolean allowsListing;
   private boolean spawnExtraParticlesOnFall = false;
   public WardenSpawnTracker wardenSpawnTracker = new WardenSpawnTracker();
   private @Nullable BlockPos raidOmenPosition;
   private Vec3 lastKnownClientMovement = Vec3.ZERO;
   private Input lastClientInput = Input.EMPTY;
   private final Set<ThrownEnderpearl> enderPearls = new HashSet<>();
   private long timeEntitySatOnShoulder;
   private CompoundTag shoulderEntityLeft = new CompoundTag();
   private CompoundTag shoulderEntityRight = new CompoundTag();
   public final ContainerSynchronizer containerSynchronizer = new ContainerSynchronizer() {
      private final LoadingCache<TypedDataComponent<?>, Integer> cache = CacheBuilder.newBuilder()
         .maximumSize(256L)
         .build(
            new CacheLoader<TypedDataComponent<?>, Integer>() {
               private final DynamicOps<HashCode> registryHashOps = ServerPlayer.this.registryAccess().createSerializationContext(HashOps.CRC32C_INSTANCE);

               public Integer load(TypedDataComponent<?> component) {
                  return ((HashCode)component.encodeValue(this.registryHashOps)
                        .getOrThrow(msg -> new IllegalArgumentException("Failed to hash " + component + ": " + msg)))
                     .asInt();
               }
            }
         );

      public void sendInitialData(
         AbstractContainerMenu container, List<net.minecraft.world.item.ItemStack> slotItems, net.minecraft.world.item.ItemStack carriedItem, int[] dataSlots
      ) {
         ServerPlayer.this.connection
            .send(new ClientboundContainerSetContentPacket(container.containerId, container.incrementStateId(), slotItems, carriedItem));

         for (int slot = 0; slot < dataSlots.length; slot++) {
            this.broadcastDataValue(container, slot, dataSlots[slot]);
         }
      }

      public void sendOffHandSlotChange() {
         this.sendSlotChange(ServerPlayer.this.inventoryMenu, 45, ServerPlayer.this.inventoryMenu.getSlot(45).getItem().copy());
      }

      public void sendSlotChange(AbstractContainerMenu container, int slotIndex, net.minecraft.world.item.ItemStack itemStack) {
         ServerPlayer.this.connection.send(new ClientboundContainerSetSlotPacket(container.containerId, container.incrementStateId(), slotIndex, itemStack));
      }

      public void sendCarriedChange(AbstractContainerMenu container, net.minecraft.world.item.ItemStack itemStack) {
         ServerPlayer.this.connection.send(new ClientboundSetCursorItemPacket(itemStack));
      }

      public void sendDataChange(AbstractContainerMenu container, int id, int value) {
         this.broadcastDataValue(container, id, value);
      }

      private void broadcastDataValue(AbstractContainerMenu container, int id, int value) {
         ServerPlayer.this.connection.send(new ClientboundContainerSetDataPacket(container.containerId, id, value));
      }

      public RemoteSlot createSlot() {
         return new Synchronized(this.cache::getUnchecked);
      }
   };
   private final ContainerListener containerListener = new ContainerListener() {
      public void slotChanged(AbstractContainerMenu container, int slotIndex, net.minecraft.world.item.ItemStack changedItem) {
         Slot slot = container.getSlot(slotIndex);
         if (!(slot instanceof ResultSlot) && slot.container == ServerPlayer.this.getInventory()) {
            CriteriaTriggers.INVENTORY_CHANGED.trigger(ServerPlayer.this, ServerPlayer.this.getInventory(), changedItem);
         }
      }

      public void slotChanged(
         AbstractContainerMenu containerToSend, int dataSlotIndex, net.minecraft.world.item.ItemStack oldStack, net.minecraft.world.item.ItemStack stack
      ) {
         Slot slot = containerToSend.getSlot(dataSlotIndex);
         if (!(slot instanceof ResultSlot) && slot.container == ServerPlayer.this.getInventory()) {
            if (PlayerInventorySlotChangeEvent.getHandlerList().getRegisteredListeners().length == 0) {
               CriteriaTriggers.INVENTORY_CHANGED.trigger(ServerPlayer.this, ServerPlayer.this.getInventory(), stack);
               return;
            }

            PlayerInventorySlotChangeEvent event = new PlayerInventorySlotChangeEvent(
               ServerPlayer.this.getBukkitEntity(), dataSlotIndex, CraftItemStack.asBukkitCopy(oldStack), CraftItemStack.asBukkitCopy(stack)
            );
            event.callEvent();
            if (event.shouldTriggerAdvancements()) {
               CriteriaTriggers.INVENTORY_CHANGED.trigger(ServerPlayer.this, ServerPlayer.this.getInventory(), stack);
            }
         }
      }

      public void dataChanged(AbstractContainerMenu container, int id, int value) {
      }
   };
   private @Nullable RemoteChatSession chatSession;
   public final @Nullable Object object;
   private final CommandSource commandSource = new CommandSource() {
      public boolean acceptsSuccess() {
         return (Boolean)ServerPlayer.this.level().getGameRules().get(GameRules.SEND_COMMAND_FEEDBACK);
      }

      public boolean acceptsFailure() {
         return true;
      }

      public boolean shouldInformAdmins() {
         return true;
      }

      public void sendSystemMessage(net.minecraft.network.chat.Component message) {
         ServerPlayer.this.sendSystemMessage(message);
      }

      public CommandSender getBukkitSender(CommandSourceStack wrapper) {
         return ServerPlayer.this.getBukkitEntity();
      }
   };
   private Set<DebugSubscription<?>> requestedDebugSubscriptions = Set.of();
   private int containerCounter;
   public boolean wonGame;
   private int containerUpdateDelay;
   public long loginTime;
   public int patrolSpawnDelay;
   public boolean queueHealthUpdatePacket;
   public @Nullable ClientboundSetHealthPacket queuedHealthUpdatePacket;
   public static final int MOBCATEGORY_TOTAL_ENUMS = MobCategory.values().length;
   public final int[] mobCounts = new int[MOBCATEGORY_TOTAL_ENUMS];
   public final int[] mobBackoffCounts = new int[MOBCATEGORY_TOTAL_ENUMS];
   public @Nullable String lastKnownName;
   public String displayName;
   public Component adventure$displayName;
   public @Nullable Component listName;
   public int listOrder = 0;
   public Location compassTarget;
   public int newExp = 0;
   public int newLevel = 0;
   public int newTotalExp = 0;
   public boolean keepLevel = false;
   public double maxHealthCache;
   public boolean joining = true;
   public boolean sentListPacket = false;
   public boolean suppressTrackerForLogin = false;
   public boolean isRealPlayer;
   public @Nullable PlayerNaturallySpawnCreaturesEvent playerNaturallySpawnedEvent;
   public @Nullable QuitReason quitReason = null;
   private PlayerChunkLoaderData chunkLoader;
   private final ViewDistanceHolder viewDistanceHolder = new ViewDistanceHolder();
   public long timeOffset = 0L;
   public boolean relativeTime = true;
   public @Nullable WeatherType weatherType = null;
   private float pluginRainPosition;
   private float pluginRainPositionPrevious;

   public final boolean moonrise$isRealPlayer() {
      return this.isRealPlayer;
   }

   public final void moonrise$setRealPlayer(boolean real) {
      this.isRealPlayer = real;
   }

   public final PlayerChunkLoaderData moonrise$getChunkLoader() {
      return this.chunkLoader;
   }

   public final void moonrise$setChunkLoader(PlayerChunkLoaderData loader) {
      this.chunkLoader = loader;
   }

   public final ViewDistanceHolder moonrise$getViewDistanceHolder() {
      return this.viewDistanceHolder;
   }

   public ServerPlayer(MinecraftServer server, ServerLevel level, GameProfile gameProfile, ClientInformation clientInformation) {
      super(level, gameProfile);
      this.server = server;
      this.textFilter = server.createTextFilterForPlayer(this);
      this.gameMode = server.createGameModeForPlayer(this);
      this.gameMode.setGameModeForPlayer(this.calculateGameModeForNewPlayer(null), null);
      this.recipeBook = new ServerRecipeBook((id, output) -> server.getRecipeManager().listDisplaysForRecipe(id, output));
      this.stats = server.getPlayerList().getPlayerStats(this);
      this.advancements = server.getPlayerList().getPlayerAdvancements(this);
      this.updateOptionsNoEvents(clientInformation);
      this.object = null;
      this.displayName = this.getScoreboardName();
      this.adventure$displayName = Component.text(this.getScoreboardName());
      this.bukkitPickUpLoot = true;
      this.maxHealthCache = this.getMaxHealth();
   }

   public BlockPos adjustSpawnLocation(ServerLevel level, BlockPos spawnSuggestion) {
      CompletableFuture<Vec3> future = PlayerSpawnFinder.findSpawn(level, spawnSuggestion);
      level.getChunkSource().mainThreadProcessor.managedBlock(future::isDone);
      return BlockPos.containing((Position)future.join());
   }

   protected void readAdditionalSaveData(ValueInput input) {
      super.readAdditionalSaveData(input);
      this.wardenSpawnTracker = (WardenSpawnTracker)input.read("warden_spawn_tracker", WardenSpawnTracker.CODEC).orElseGet(WardenSpawnTracker::new);
      this.enteredNetherPosition = (Vec3)input.read("entered_nether_pos", Vec3.CODEC).orElse(null);
      this.currentExplosionImpactPos = (Vec3)input.read("last_explosion_impact_pos", Vec3.CODEC).orElse(null);
      this.seenCredits = input.getBooleanOr("seenCredits", false);
      input.read("recipeBook", Packed.CODEC).ifPresent(p -> this.recipeBook.loadUntrusted(p, id -> this.server.getRecipeManager().byKey(id).isPresent()));
      this.getBukkitEntity().readExtraData(input);
      if (this.isSleeping()) {
         this.stopSleeping();
      }

      this.respawnConfig = (ServerPlayer.RespawnConfig)input.read("respawn", ServerPlayer.RespawnConfig.CODEC).orElse(null);
      this.spawnExtraParticlesOnFall = input.getBooleanOr("spawn_extra_particles_on_fall", false);
      this.raidOmenPosition = (BlockPos)input.read("raid_omen_position", BlockPos.CODEC).orElse(null);
      this.loadGameTypes(input);
      this.setShoulderEntityLeft((CompoundTag)input.read("ShoulderEntityLeft", CompoundTag.CODEC).orElseGet(CompoundTag::new));
      this.setShoulderEntityRight((CompoundTag)input.read("ShoulderEntityRight", CompoundTag.CODEC).orElseGet(CompoundTag::new));
   }

   private void loadGameTypes(ValueInput input) {
      if (this.server.getForcedGameType() != null && this.server.getForcedGameType() != readPlayerMode(input, "playerGameType")) {
         if (new PlayerGameModeChangeEvent(this.getBukkitEntity(), GameMode.getByValue(this.server.getDefaultGameType().getId()), Cause.DEFAULT_GAMEMODE, null)
            .callEvent()) {
            this.gameMode.setGameModeForPlayer(this.server.getForcedGameType(), GameType.DEFAULT_MODE);
         } else {
            this.gameMode.setGameModeForPlayer(readPlayerMode(input, "playerGameType"), readPlayerMode(input, "previousPlayerGameType"));
         }
      } else {
         this.gameMode
            .setGameModeForPlayer(this.calculateGameModeForNewPlayer(readPlayerMode(input, "playerGameType")), readPlayerMode(input, "previousPlayerGameType"));
      }
   }

   protected void addAdditionalSaveData(ValueOutput output) {
      super.addAdditionalSaveData(output);
      output.store("warden_spawn_tracker", WardenSpawnTracker.CODEC, this.wardenSpawnTracker);
      this.storeGameTypes(output);
      output.putBoolean("seenCredits", this.seenCredits);
      output.storeNullable("entered_nether_pos", Vec3.CODEC, this.enteredNetherPosition);
      output.storeNullable("last_explosion_impact_pos", Vec3.CODEC, this.currentExplosionImpactPos);
      this.saveParentVehicle(output);
      output.store("recipeBook", Packed.CODEC, this.recipeBook.pack());
      output.putString("Dimension", this.level().dimension().identifier().toString());
      output.storeNullable("respawn", ServerPlayer.RespawnConfig.CODEC, this.respawnConfig);
      output.putBoolean("spawn_extra_particles_on_fall", this.spawnExtraParticlesOnFall);
      output.storeNullable("raid_omen_position", BlockPos.CODEC, this.raidOmenPosition);
      this.saveEnderPearls(output);
      if (!this.getShoulderEntityLeft().isEmpty()) {
         output.store("ShoulderEntityLeft", CompoundTag.CODEC, this.getShoulderEntityLeft());
      }

      if (!this.getShoulderEntityRight().isEmpty()) {
         output.store("ShoulderEntityRight", CompoundTag.CODEC, this.getShoulderEntityRight());
      }

      this.getBukkitEntity().setExtraData(output);
   }

   private void saveParentVehicle(ValueOutput playerOutput) {
      Entity rootVehicle = this.getRootVehicle();
      Entity vehicle = this.getVehicle();
      boolean persistVehicle = true;
      if (vehicle != null) {
         for (Entity topVehicle = vehicle; topVehicle != null; topVehicle = topVehicle.getVehicle()) {
            if (!topVehicle.persist) {
               persistVehicle = false;
               break;
            }
         }
      }

      if (persistVehicle && vehicle != null && rootVehicle != this && rootVehicle.hasExactlyOnePlayerPassenger() && !rootVehicle.isRemoved()) {
         ValueOutput vehicleWrapper = playerOutput.child("RootVehicle");
         vehicleWrapper.store("Attach", UUIDUtil.CODEC, vehicle.getUUID());
         rootVehicle.save(vehicleWrapper.child("Entity"));
      }
   }

   public void loadAndSpawnParentVehicle(ValueInput playerInput) {
      Optional<ValueInput> rootTag = playerInput.child("RootVehicle");
      if (!rootTag.isEmpty()) {
         ServerLevel serverLevel = this.level();
         Entity vehicle = EntityType.loadEntityRecursive(
            rootTag.get().childOrEmpty("Entity"), serverLevel, EntitySpawnReason.LOAD, e -> !serverLevel.addWithUUID(e, SpawnReason.MOUNT) ? null : e
         );
         if (vehicle != null) {
            UUID attachTo = (UUID)rootTag.get().read("Attach", UUIDUtil.CODEC).orElse(null);
            if (vehicle.getUUID().equals(attachTo)) {
               this.startRiding(vehicle, true, false);
            } else {
               for (Entity entity : vehicle.getIndirectPassengers()) {
                  if (entity.getUUID().equals(attachTo)) {
                     this.startRiding(entity, true, false);
                     break;
                  }
               }
            }

            if (!this.isPassenger()) {
               LOGGER.warn("Couldn't reattach entity to player");
               vehicle.discard(null);

               for (Entity entity : vehicle.getIndirectPassengers()) {
                  entity.discard(null);
               }
            }
         }
      }
   }

   private void saveEnderPearls(ValueOutput playerOutput) {
      if (!this.enderPearls.isEmpty()) {
         ValueOutputList pearlsOutput = playerOutput.childrenList("ender_pearls");

         for (ThrownEnderpearl enderPearl : this.enderPearls) {
            if (!enderPearl.level().paperConfig().misc.legacyEnderPearlBehavior) {
               if (enderPearl.isRemoved()) {
                  LOGGER.warn("Trying to save removed ender pearl, skipping");
               } else {
                  ValueOutput pearlTag = pearlsOutput.addChild();
                  enderPearl.save(pearlTag);
                  pearlTag.store("ender_pearl_dimension", Level.RESOURCE_KEY_CODEC, enderPearl.level().dimension());
               }
            }
         }
      }
   }

   public void loadAndSpawnEnderPearls(ValueInput playerInput) {
      playerInput.childrenListOrEmpty("ender_pearls").forEach(this::loadAndSpawnEnderPearl);
   }

   private void loadAndSpawnEnderPearl(ValueInput pearlInput) {
      Optional<ResourceKey<Level>> pearlLevelKey = pearlInput.read("ender_pearl_dimension", Level.RESOURCE_KEY_CODEC);
      if (!pearlLevelKey.isEmpty()) {
         ServerLevel pearlLevel = this.level().getServer().getLevel(pearlLevelKey.get());
         if (pearlLevel != null) {
            Entity pearl = EntityType.loadEntityRecursive(
               pearlInput, pearlLevel, EntitySpawnReason.LOAD, entity -> !pearlLevel.addWithUUID(entity) ? null : entity
            );
            if (pearl != null) {
               placeEnderPearlTicket(pearlLevel, pearl.chunkPosition());
            } else {
               LOGGER.warn("Failed to spawn player ender pearl in level ({}), skipping", pearlLevelKey.get());
            }
         } else {
            LOGGER.warn("Trying to load ender pearl without level ({}) being loaded, skipping", pearlLevelKey.get());
         }
      }
   }

   public void setExperiencePoints(int amount) {
      float limit = this.getXpNeededForNextLevel();
      float max = (limit - 1.0F) / limit;
      float experiencePointsToSet = Mth.clamp(amount / limit, 0.0F, max);
      if (experiencePointsToSet != this.experienceProgress) {
         this.experienceProgress = experiencePointsToSet;
         this.lastSentExp = -1;
      }
   }

   public void setExperienceLevels(int amount) {
      if (amount != this.experienceLevel) {
         this.experienceLevel = amount;
         this.lastSentExp = -1;
      }
   }

   public void giveExperienceLevels(int amount) {
      if (amount != 0) {
         super.giveExperienceLevels(amount);
         this.lastSentExp = -1;
      }
   }

   public void onEnchantmentPerformed(net.minecraft.world.item.ItemStack itemStack, int enchantmentCost) {
      super.onEnchantmentPerformed(itemStack, enchantmentCost);
      this.lastSentExp = -1;
   }

   public void initMenu(AbstractContainerMenu container) {
      container.addSlotListener(this.containerListener);
      container.setSynchronizer(this.containerSynchronizer);
   }

   public void initInventoryMenu() {
      this.initMenu(this.inventoryMenu);
   }

   public void onEnterCombat() {
      super.onEnterCombat();
      this.connection.send(ClientboundPlayerCombatEnterPacket.INSTANCE);
   }

   public void onLeaveCombat() {
      super.onLeaveCombat();
      this.connection.send(new ClientboundPlayerCombatEndPacket(this.getCombatTracker()));
   }

   public void onInsideBlock(BlockState state) {
      CriteriaTriggers.ENTER_BLOCK.trigger(this, state);
   }

   protected ItemCooldowns createItemCooldowns() {
      return new ServerItemCooldowns(this);
   }

   public void tick() {
      if (this.joining) {
         this.joining = false;
      }

      this.connection.tickClientLoadTimeout();
      this.gameMode.tick();
      this.wardenSpawnTracker.tick();
      if (this.invulnerableTime > 0) {
         this.invulnerableTime--;
      }

      if (--this.containerUpdateDelay <= 0) {
         this.containerMenu.broadcastChanges();
         if (this.containerMenu != this.inventoryMenu) {
            this.inventoryMenu.broadcastNonContainerSlotChanges();
         }

         this.containerUpdateDelay = this.level().paperConfig().tickRates.containerUpdate;
      }

      if (this.containerMenu != this.inventoryMenu && (this.isImmobile() || !this.containerMenu.stillValid(this))) {
         this.closeContainer(Reason.CANT_USE);
         this.containerMenu = this.inventoryMenu;
      }

      Entity camera = this.getCamera();
      if (camera != this) {
         if (camera.isAlive()) {
            this.absSnapTo(camera.getX(), camera.getY(), camera.getZ(), camera.getYRot(), camera.getXRot());
            this.level().getChunkSource().move(this);
            if (this.wantsToStopRiding()) {
               this.setCamera(this);
            }
         } else {
            this.setCamera(this);
         }
      }

      CriteriaTriggers.TICK.trigger(this);
      if (this.levitationStartPos != null) {
         CriteriaTriggers.LEVITATION.trigger(this, this.levitationStartPos, this.tickCount - this.levitationStartTime);
      }

      this.trackStartFallingPosition();
      this.trackEnteredOrExitedLavaOnVehicle();
      this.updatePlayerAttributes();
      this.advancements.flushDirty(this, true);
   }

   private void updatePlayerAttributes() {
      AttributeInstance blockInteractionRange = this.getAttribute(Attributes.BLOCK_INTERACTION_RANGE);
      if (blockInteractionRange != null) {
         if (this.isCreative()) {
            blockInteractionRange.addOrUpdateTransientModifier(CREATIVE_BLOCK_INTERACTION_RANGE_MODIFIER);
         } else {
            blockInteractionRange.removeModifier(CREATIVE_BLOCK_INTERACTION_RANGE_MODIFIER);
         }
      }

      AttributeInstance entityInteractionRange = this.getAttribute(Attributes.ENTITY_INTERACTION_RANGE);
      if (entityInteractionRange != null) {
         if (this.isCreative()) {
            entityInteractionRange.addOrUpdateTransientModifier(CREATIVE_ENTITY_INTERACTION_RANGE_MODIFIER);
         } else {
            entityInteractionRange.removeModifier(CREATIVE_ENTITY_INTERACTION_RANGE_MODIFIER);
         }
      }

      AttributeInstance waypointTransmitRange = this.getAttribute(Attributes.WAYPOINT_TRANSMIT_RANGE);
      if (waypointTransmitRange != null) {
         if (this.isCrouching()) {
            waypointTransmitRange.addOrUpdateTransientModifier(WAYPOINT_TRANSMIT_RANGE_CROUCH_MODIFIER);
         } else {
            waypointTransmitRange.removeModifier(WAYPOINT_TRANSMIT_RANGE_CROUCH_MODIFIER);
         }
      }
   }

   public void doTick() {
      try {
         if (this.valid && (!this.isSpectator() || !this.touchingUnloadedChunk())) {
            super.tick();
            if (!this.containerMenu.stillValid(this)) {
               this.closeContainer(Reason.CANT_USE);
               this.containerMenu = this.inventoryMenu;
            }

            this.foodData.tick(this);
            this.awardStat(Stats.PLAY_TIME);
            this.awardStat(Stats.TOTAL_WORLD_TIME);
            if (this.isAlive()) {
               this.awardStat(Stats.TIME_SINCE_DEATH);
            }

            if (this.isDiscrete()) {
               this.awardStat(Stats.CROUCH_TIME);
            }

            if (!this.isSleeping()) {
               this.awardStat(Stats.TIME_SINCE_REST);
            }
         }

         for (int i = 0; i < this.getInventory().getContainerSize(); i++) {
            net.minecraft.world.item.ItemStack itemStack = this.getInventory().getItem(i);
            if (!itemStack.isEmpty()) {
               this.synchronizeSpecialItemUpdates(itemStack);
            }
         }

         if (this.getHealth() != this.lastSentHealth
            || this.lastSentFood != this.foodData.getFoodLevel()
            || this.foodData.getSaturationLevel() == 0.0F != this.lastFoodSaturationZero) {
            this.connection
               .send(new ClientboundSetHealthPacket(this.getBukkitEntity().getScaledHealth(), this.foodData.getFoodLevel(), this.foodData.getSaturationLevel()));
            this.lastSentHealth = this.getHealth();
            this.lastSentFood = this.foodData.getFoodLevel();
            this.lastFoodSaturationZero = this.foodData.getSaturationLevel() == 0.0F;
         }

         if (this.getHealth() + this.getAbsorptionAmount() != this.lastRecordedHealthAndAbsorption) {
            this.lastRecordedHealthAndAbsorption = this.getHealth() + this.getAbsorptionAmount();
            this.updateScoreForCriteria(ObjectiveCriteria.HEALTH, Mth.ceil(this.lastRecordedHealthAndAbsorption));
         }

         if (this.foodData.getFoodLevel() != this.lastRecordedFoodLevel) {
            this.lastRecordedFoodLevel = this.foodData.getFoodLevel();
            this.updateScoreForCriteria(ObjectiveCriteria.FOOD, Mth.ceil(this.lastRecordedFoodLevel));
         }

         if (this.getAirSupply() != this.lastRecordedAirLevel) {
            this.lastRecordedAirLevel = this.getAirSupply();
            this.updateScoreForCriteria(ObjectiveCriteria.AIR, Mth.ceil(this.lastRecordedAirLevel));
         }

         if (this.getArmorValue() != this.lastRecordedArmor) {
            this.lastRecordedArmor = this.getArmorValue();
            this.updateScoreForCriteria(ObjectiveCriteria.ARMOR, Mth.ceil(this.lastRecordedArmor));
         }

         if (this.totalExperience != this.lastRecordedExperience) {
            this.lastRecordedExperience = this.totalExperience;
            this.updateScoreForCriteria(ObjectiveCriteria.EXPERIENCE, Mth.ceil(this.lastRecordedExperience));
         }

         if (this.maxHealthCache != this.getMaxHealth()) {
            this.getBukkitEntity().updateScaledHealth();
         }

         if (this.experienceLevel != this.lastRecordedLevel) {
            this.lastRecordedLevel = this.experienceLevel;
            this.updateScoreForCriteria(ObjectiveCriteria.LEVEL, Mth.ceil(this.lastRecordedLevel));
         }

         if (this.totalExperience != this.lastSentExp) {
            this.lastSentExp = this.totalExperience;
            this.connection.send(new ClientboundSetExperiencePacket(this.experienceProgress, this.totalExperience, this.experienceLevel));
         }

         if (this.tickCount % 20 == 0) {
            CriteriaTriggers.LOCATION.trigger(this);
         }

         if (this.oldLevel == -1) {
            this.oldLevel = this.experienceLevel;
         }

         if (this.oldLevel != this.experienceLevel) {
            CraftEventFactory.callPlayerLevelChangeEvent(this.getBukkitEntity(), this.oldLevel, this.experienceLevel);
            this.oldLevel = this.experienceLevel;
         }

         if (this.getBukkitEntity().hasClientWorldBorder()) {
            ((CraftWorldBorder)this.getBukkitEntity().getWorldBorder()).getHandle().tick();
         }
      } catch (Throwable t) {
         CrashReport report = CrashReport.forThrowable(t, "Ticking player");
         CrashReportCategory category = report.addCategory("Player being ticked");
         this.fillCrashReportCategory(category);
         throw new ReportedException(report);
      }
   }

   private void synchronizeSpecialItemUpdates(net.minecraft.world.item.ItemStack itemStack) {
      MapId mapId = (MapId)itemStack.get(DataComponents.MAP_ID);
      MapItemSavedData data = MapItem.getSavedData(mapId, this.level());
      if (data != null) {
         Packet<?> packet = data.getUpdatePacket(mapId, this);
         if (packet != null) {
            this.connection.send(packet);
         }
      }
   }

   protected void tickRegeneration() {
      if (this.level().getDifficulty() == Difficulty.PEACEFUL && (Boolean)this.level().getGameRules().get(GameRules.NATURAL_HEALTH_REGENERATION)) {
         if (this.tickCount % 20 == 0) {
            if (this.getHealth() < this.getMaxHealth()) {
               this.heal(1.0F, RegainReason.REGEN);
            }

            float saturation = this.foodData.getSaturationLevel();
            if (saturation < 20.0F) {
               this.foodData.setSaturation(saturation + 1.0F);
            }
         }

         if (this.tickCount % 10 == 0 && this.foodData.needsFood()) {
            this.foodData.setFoodLevel(this.foodData.getFoodLevel() + 1);
         }
      }
   }

   public void handleShoulderEntities() {
      this.playShoulderEntityAmbientSound(this.getShoulderEntityLeft());
      this.playShoulderEntityAmbientSound(this.getShoulderEntityRight());
      if ((this.fallDistance > 0.5 || this.isInWater() || this.getAbilities().flying || this.isSleeping() || this.isInPowderSnow)
         && !this.level().paperConfig().entities.behavior.parrotsAreUnaffectedByPlayerMovement) {
         this.removeEntitiesOnShoulder();
      }
   }

   private void playShoulderEntityAmbientSound(CompoundTag shoulderEntityTag) {
      if (!shoulderEntityTag.isEmpty() && !shoulderEntityTag.getBooleanOr("Silent", false) && this.random.nextInt(200) == 0) {
         EntityType<?> entityType = (EntityType<?>)shoulderEntityTag.read("id", EntityType.CODEC).orElse(null);
         if (entityType == EntityTypes.PARROT && !Parrot.imitateNearbyMobs(this.level(), this)) {
            this.level()
               .playSound(
                  null,
                  this.getX(),
                  this.getY(),
                  this.getZ(),
                  Parrot.getAmbient(this.level(), this.random),
                  this.getSoundSource(),
                  1.0F,
                  Parrot.getPitch(this.random)
               );
         }
      }
   }

   public boolean setEntityOnShoulder(CompoundTag entityTag) {
      if (this.isPassenger() || !this.onGround() || this.isInWater() || this.isInPowderSnow) {
         return false;
      } else if (this.getShoulderEntityLeft().isEmpty()) {
         this.setShoulderEntityLeft(entityTag);
         this.timeEntitySatOnShoulder = this.level().getGameTime();
         return true;
      } else if (this.getShoulderEntityRight().isEmpty()) {
         this.setShoulderEntityRight(entityTag);
         this.timeEntitySatOnShoulder = this.level().getGameTime();
         return true;
      } else {
         return false;
      }
   }

   public void removeEntitiesOnShoulder() {
      if (this.timeEntitySatOnShoulder + 20L < this.level().getGameTime()) {
         if (this.respawnEntityOnShoulder(this.getShoulderEntityLeft())) {
            this.setShoulderEntityLeft(new CompoundTag());
         }

         if (this.respawnEntityOnShoulder(this.getShoulderEntityRight())) {
            this.setShoulderEntityRight(new CompoundTag());
         }
      }
   }

   public Entity releaseLeftShoulderEntity() {
      Entity entity = this.respawnEntityOnShoulder0(this.getShoulderEntityLeft());
      if (entity != null) {
         this.setShoulderEntityLeft(new CompoundTag());
      }

      return entity;
   }

   public Entity releaseRightShoulderEntity() {
      Entity entity = this.respawnEntityOnShoulder0(this.getShoulderEntityRight());
      if (entity != null) {
         this.setShoulderEntityRight(new CompoundTag());
      }

      return entity;
   }

   private boolean respawnEntityOnShoulder(CompoundTag tag) {
      return this.respawnEntityOnShoulder0(tag) != null;
   }

   private @Nullable Entity respawnEntityOnShoulder0(CompoundTag tag) {
      ServerLevel serverLevel = this.level();
      if (!tag.isEmpty()) {
         ScopedCollector reporter = new ScopedCollector(this.problemPath(), LOGGER);

         Entity var4;
         try {
            var4 = EntityType.create(
                  TagValueInput.create(reporter.forChild(() -> ".shoulder"), serverLevel.registryAccess(), tag),
                  serverLevel,
                  new EntitySpawnRequest(EntitySpawnReason.LOAD, false)
               )
               .map(entity -> {
                  if (entity instanceof TamableAnimal tamed) {
                     tamed.setOwner(this);
                  }

                  entity.setPos(this.getX(), this.getY() + 0.7F, this.getZ());
                  return (Entity)(serverLevel.addWithUUID(entity, SpawnReason.SHOULDER_ENTITY) ? entity : null);
               })
               .orElse(null);
         } catch (Throwable var7) {
            try {
               reporter.close();
            } catch (Throwable var6) {
               var7.addSuppressed(var6);
            }

            throw var7;
         }

         reporter.close();
         return var4;
      } else {
         return null;
      }
   }

   public void resetFallDistance() {
      if (this.getHealth() > 0.0F && this.startingToFallPosition != null) {
         CriteriaTriggers.FALL_FROM_HEIGHT.trigger(this, this.startingToFallPosition);
      }

      this.startingToFallPosition = null;
      super.resetFallDistance();
   }

   public void trackStartFallingPosition() {
      if (this.fallDistance > 0.0 && this.startingToFallPosition == null) {
         this.startingToFallPosition = this.position();
         if (this.currentExplosionImpactPos != null && this.currentExplosionImpactPos.y <= this.startingToFallPosition.y) {
            CriteriaTriggers.FALL_AFTER_EXPLOSION.trigger(this, this.currentExplosionImpactPos, this.currentExplosionCause);
         }

         this.currentExplosionImpactPos = null;
         this.currentExplosionCause = null;
      }
   }

   public void trackEnteredOrExitedLavaOnVehicle() {
      if (this.getVehicle() != null && this.getVehicle().isInLava()) {
         if (this.enteredLavaOnVehiclePosition == null) {
            this.enteredLavaOnVehiclePosition = this.position();
         } else {
            CriteriaTriggers.RIDE_ENTITY_IN_LAVA_TRIGGER.trigger(this, this.enteredLavaOnVehiclePosition);
         }
      }

      if (this.enteredLavaOnVehiclePosition != null && (this.getVehicle() == null || !this.getVehicle().isInLava())) {
         this.enteredLavaOnVehiclePosition = null;
      }
   }

   private void updateScoreForCriteria(ObjectiveCriteria criteria, int value) {
      this.level().getCraftServer().getScoreboardManager().forAllObjectives(criteria, this, score -> score.set(value));
   }

   private static boolean shouldKeepDeathEventItem(PlayerDeathEvent event, net.minecraft.world.item.ItemStack item) {
      List<ItemStack> itemsToKeep = event.getItemsToKeep();
      if (!EnchantmentHelper.has(item, EnchantmentEffectComponents.PREVENT_EQUIPMENT_DROP) && !itemsToKeep.isEmpty() && !item.isEmpty()) {
         ItemStack bukkitStack = item.getBukkitStack();
         Iterator<ItemStack> iterator = itemsToKeep.iterator();

         while (iterator.hasNext()) {
            ItemStack itemStack = iterator.next();
            if (bukkitStack.equals(itemStack)) {
               iterator.remove();
               return true;
            }
         }

         return false;
      } else {
         return false;
      }
   }

   private void sendClientboundPlayerCombatKillPacket(boolean displayMessage, net.minecraft.network.chat.Component deathMessage) {
      if (displayMessage && deathMessage != CommonComponents.EMPTY) {
         this.connection
            .send(
               new ClientboundPlayerCombatKillPacket(this.getId(), deathMessage),
               PacketSendListener.exceptionallySend(
                  () -> {
                     int truncatedMessageSize = 256;
                     String truncatedDeathMessage = deathMessage.getString(256);
                     net.minecraft.network.chat.Component explanation = net.minecraft.network.chat.Component.translatable(
                        "death.attack.message_too_long", net.minecraft.network.chat.Component.literal(truncatedDeathMessage).withStyle(ChatFormatting.YELLOW)
                     );
                     net.minecraft.network.chat.Component fakeDeathMessage = net.minecraft.network.chat.Component.translatable(
                           "death.attack.even_more_magic", this.getDisplayName()
                        )
                        .withStyle(style -> style.withHoverEvent(new ShowText(explanation)));
                     return new ClientboundPlayerCombatKillPacket(this.getId(), fakeDeathMessage);
                  }
               )
            );
      } else {
         this.connection.send(new ClientboundPlayerCombatKillPacket(this.getId(), CommonComponents.EMPTY));
      }
   }

   public void die(DamageSource source) {
      boolean showDeathMessage = (Boolean)this.level().getGameRules().get(GameRules.SHOW_DEATH_MESSAGES);
      if (!this.isRemoved()) {
         List<DefaultDrop> loot = new ArrayList<>(this.getInventory().getContainerSize());
         boolean keepInventory = (Boolean)this.level().getGameRules().get(GameRules.KEEP_INVENTORY) || this.isSpectator();
         if (!keepInventory) {
            for (net.minecraft.world.item.ItemStack item : this.getInventory().getContents()) {
               if (!item.isEmpty() && !EnchantmentHelper.has(item, EnchantmentEffectComponents.PREVENT_EQUIPMENT_DROP)) {
                  loot.add(new DefaultDrop(item, stack -> this.drop(stack, true, false, false, null)));
               }
            }
         }

         if (!this.isSpectator() && this.shouldDropLoot(this.level())) {
            this.deathDropItems = new ArrayList();
            this.dropFromLootTable(this.level(), source, this.lastHurtByPlayerMemoryTime > 0);
            loot.addAll(this.deathDropItems);
            this.deathDropItems = null;
         }

         net.minecraft.network.chat.Component defaultMessage = this.getCombatTracker().getDeathMessage();
         this.keepLevel = keepInventory;
         PlayerDeathEvent event = CraftEventFactory.callPlayerDeathEvent(
            this, source, loot, PaperAdventure.asAdventure(defaultMessage), showDeathMessage, keepInventory
         );
         if (event.isCancelled()) {
            if (this.getHealth() <= 0.0F) {
               this.setHealth((float)event.getReviveHealth());
            }
         } else {
            this.gameEvent(GameEvent.ENTITY_DIE);
            if (this.containerMenu != this.inventoryMenu) {
               this.closeContainer(Reason.DEATH);
            }

            Component apiDeathMessage = (Component)(event.deathMessage() != null ? event.deathMessage() : Component.empty());
            net.minecraft.network.chat.Component deathScreenMessage = PaperAdventure.asVanilla(
               event.deathScreenMessageOverride() != null ? event.deathScreenMessageOverride() : apiDeathMessage
            );
            if (apiDeathMessage != null && apiDeathMessage != Component.empty() && event.getShowDeathMessages()) {
               net.minecraft.network.chat.Component deathMessage = PaperAdventure.asVanilla(apiDeathMessage);
               this.sendClientboundPlayerCombatKillPacket(event.getShowDeathMessages(), deathScreenMessage);
               Team team = this.getTeam();
               if (team == null || team.getDeathMessageVisibility() == Visibility.ALWAYS) {
                  this.server.getPlayerList().broadcastSystemMessage(deathMessage, false);
               } else if (team.getDeathMessageVisibility() == Visibility.HIDE_FOR_OTHER_TEAMS) {
                  this.server.getPlayerList().broadcastSystemToTeam(this, deathMessage);
               } else if (team.getDeathMessageVisibility() == Visibility.HIDE_FOR_OWN_TEAM) {
                  this.server.getPlayerList().broadcastSystemToAllExceptTeam(this, deathMessage);
               }
            } else {
               this.sendClientboundPlayerCombatKillPacket(event.getShowDeathMessages(), deathScreenMessage);
            }

            this.removeEntitiesOnShoulder();
            if ((Boolean)this.level().getGameRules().get(GameRules.FORGIVE_DEAD_PLAYERS)) {
               this.tellNeutralMobsThatIDied();
            }

            if (event.shouldDropExperience()) {
               this.dropExperience(this.level(), source.getEntity());
            }

            if (!event.getKeepInventory()) {
               for (int i = 0; i < this.getInventory().getNonEquipmentItems().size(); i++) {
                  if (!shouldKeepDeathEventItem(event, (net.minecraft.world.item.ItemStack)this.getInventory().getNonEquipmentItems().get(i))) {
                     this.getInventory().getNonEquipmentItems().set(i, net.minecraft.world.item.ItemStack.EMPTY);
                  }
               }

               for (EquipmentSlot value : EquipmentSlot.VALUES) {
                  if (this.getInventory().equipment.has(value) && !shouldKeepDeathEventItem(event, this.getInventory().equipment.get(value))) {
                     this.getInventory().equipment.set(value, net.minecraft.world.item.ItemStack.EMPTY);
                  }
               }

               if (!event.getItemsToKeep().isEmpty()) {
                  for (ItemStack itemStack : event.getItemsToKeep()) {
                     event.getEntity().getInventory().addItem(new ItemStack[]{itemStack});
                  }
               }
            }

            this.setCamera(this);
            this.level().getCraftServer().getScoreboardManager().forAllObjectives(ObjectiveCriteria.DEATH_COUNT, this, ScoreAccess::increment);
            LivingEntity killer = this.getKillCredit();
            if (killer != null) {
               this.awardStat(Stats.ENTITY_KILLED_BY.get(killer.getType()));
               killer.awardKillScore(this, source);
               this.createWitherRose(killer);
            }

            this.level().broadcastEntityEvent(this, (byte)3);
            this.awardStat(Stats.DEATHS);
            this.resetStat(Stats.CUSTOM.get(Stats.TIME_SINCE_DEATH));
            this.resetStat(Stats.CUSTOM.get(Stats.TIME_SINCE_REST));
            this.clearFire();
            this.setTicksFrozen(0);
            this.setSharedFlagOnFire(false);
            this.getCombatTracker().recheckStatus();
            this.setLastDeathLocation(Optional.of(GlobalPos.of(this.level().dimension(), this.blockPosition())));
            this.connection.markClientUnloadedAfterDeath();
         }
      }
   }

   private void tellNeutralMobsThatIDied() {
      AABB aabb = new AABB(this.blockPosition()).inflate(32.0, 10.0, 32.0);
      this.level()
         .getEntitiesOfClass(Mob.class, aabb, EntitySelector.NO_SPECTATORS)
         .stream()
         .filter(mob -> mob instanceof NeutralMob)
         .forEach(mob -> ((NeutralMob)mob).playerDied(this.level(), this));
   }

   public void awardKillScore(Entity victim, DamageSource killingBlow) {
      if (victim != this) {
         super.awardKillScore(victim, killingBlow);
         Scoreboard scoreboard = this.level().getScoreboard();
         this.level().getCraftServer().getScoreboardManager().forAllObjectives(ObjectiveCriteria.KILL_COUNT_ALL, this, ScoreAccess::increment);
         if (victim instanceof Player) {
            this.awardStat(Stats.PLAYER_KILLS);
            this.level().getCraftServer().getScoreboardManager().forAllObjectives(ObjectiveCriteria.KILL_COUNT_PLAYERS, this, ScoreAccess::increment);
         } else {
            this.awardStat(Stats.MOB_KILLS);
         }

         this.handleTeamKill(this, victim, ObjectiveCriteria.TEAM_KILL);
         this.handleTeamKill(victim, this, ObjectiveCriteria.KILLED_BY_TEAM);
         CriteriaTriggers.PLAYER_KILLED_ENTITY.trigger(this, victim, killingBlow);
      }
   }

   private void handleTeamKill(ScoreHolder source, ScoreHolder target, Map<TeamColor, ObjectiveCriteria> criteriaByTeam) {
      Scoreboard scoreboard = this.level().getScoreboard();
      PlayerTeam ownTeam = this.getBukkitEntity().getScoreboard().getHandle().getPlayersTeam(target.getScoreboardName());
      if (ownTeam != null && ownTeam.getColor().isPresent()) {
         ObjectiveCriteria criteria = criteriaByTeam.get(ownTeam.getColor().get());
         if (criteria != null) {
            this.level().getCraftServer().getScoreboardManager().forAllObjectives(criteria, source, ScoreAccess::increment);
         }
      }
   }

   public boolean hurtServer(ServerLevel level, DamageSource source, float damage) {
      if (this.isInvulnerableTo(level, source)) {
         return false;
      }

      Entity entity = source.getEntity();
      if (!(entity instanceof Player player && !this.canHarmPlayer(player))
         && !(entity instanceof AbstractArrow arrow && arrow.getOwner() instanceof Player owner && !this.canHarmPlayer(owner))) {
         this.queueHealthUpdatePacket = true;
         boolean damaged = super.hurtServer(level, source, damage);
         this.queueHealthUpdatePacket = false;
         if (this.queuedHealthUpdatePacket != null) {
            this.connection.send(this.queuedHealthUpdatePacket);
            this.queuedHealthUpdatePacket = null;
         }

         return damaged;
      } else {
         return false;
      }
   }

   public boolean canHarmPlayer(Player target) {
      return this.isPvpAllowed() && super.canHarmPlayer(target);
   }

   private boolean isPvpAllowed() {
      return this.level().isPvpAllowed();
   }

   public @Nullable TeleportTransition findRespawnPositionAndUseSpawnBlock(
      boolean consumeSpawnBlock, PostTeleportTransition postTeleportTransition, RespawnReason respawnReason
   ) {
      ServerPlayer.RespawnResult result = this.findRespawnPositionAndUseSpawnBlock0(consumeSpawnBlock, postTeleportTransition, respawnReason);
      return result == null ? null : result.transition();
   }

   public ServerPlayer.@Nullable RespawnResult findRespawnPositionAndUseSpawnBlock0(
      boolean consumeSpawnBlock, PostTeleportTransition postTeleportTransition, RespawnReason respawnReason
   ) {
      boolean isBedSpawn = false;
      boolean isAnchorSpawn = false;
      Runnable consumeAnchorCharge = null;
      ServerPlayer.RespawnConfig respawnConfig = this.getRespawnConfig();
      ServerLevel respawnLevel = this.server.getLevel(ServerPlayer.RespawnConfig.getDimensionOrDefault(respawnConfig));
      TeleportTransition teleportTransition;
      if (respawnLevel != null && respawnConfig != null) {
         Optional<ServerPlayer.RespawnPosAngle> respawn = findRespawnAndUseSpawnBlock(respawnLevel, respawnConfig, consumeSpawnBlock);
         if (respawn.isPresent()) {
            ServerPlayer.RespawnPosAngle respawnPosAngle = respawn.get();
            isBedSpawn = respawnPosAngle.isBedSpawn();
            isAnchorSpawn = respawnPosAngle.isAnchorSpawn();
            consumeAnchorCharge = respawnPosAngle.consumeAnchorCharge();
            teleportTransition = new TeleportTransition(
               respawnLevel, respawnPosAngle.position(), Vec3.ZERO, respawnPosAngle.yaw(), respawnPosAngle.pitch(), postTeleportTransition
            );
         } else {
            teleportTransition = TeleportTransition.missingRespawnBlock(this, postTeleportTransition);
         }
      } else {
         teleportTransition = TeleportTransition.createDefault(this, postTeleportTransition);
      }

      org.bukkit.entity.Player respawnPlayer = this.getBukkitEntity();
      Location location = CraftLocation.toBukkit(
         teleportTransition.position(), teleportTransition.newLevel(), teleportTransition.yRot(), teleportTransition.xRot()
      );
      PlayerRespawnEvent respawnEvent = new PlayerRespawnEvent(
         respawnPlayer, location, isBedSpawn, isAnchorSpawn, teleportTransition.missingRespawnBlock(), respawnReason
      );
      this.level().getCraftServer().getPluginManager().callEvent(respawnEvent);
      if (this.connection.isDisconnected()) {
         return null;
      }

      if (location.equals(respawnEvent.getRespawnLocation()) && consumeAnchorCharge != null) {
         consumeAnchorCharge.run();
      }

      location = respawnEvent.getRespawnLocation();
      return new ServerPlayer.RespawnResult(teleportTransition.withChangedLocation(location), isBedSpawn, isAnchorSpawn);
   }

   public boolean isReceivingWaypoints() {
      return this.getAttributeValue(Attributes.WAYPOINT_RECEIVE_RANGE) > 0.0;
   }

   protected void onAttributeUpdated(Holder<Attribute> attribute) {
      if (attribute.is(Attributes.WAYPOINT_RECEIVE_RANGE)) {
         ServerWaypointManager waypointManager = this.level().getWaypointManager();
         if (this.getAttributes().getValue(attribute) > 0.0) {
            waypointManager.addPlayer(this);
         } else {
            waypointManager.removePlayer(this);
         }
      }

      super.onAttributeUpdated(attribute);
   }

   public static Optional<ServerPlayer.RespawnPosAngle> findRespawnAndUseSpawnBlock(
      ServerLevel level, ServerPlayer.RespawnConfig respawnConfig, boolean consumeSpawnBlock
   ) {
      RespawnData respawnData = respawnConfig.respawnData;
      BlockPos pos = respawnData.pos();
      float yaw = respawnData.yaw();
      float pitch = respawnData.pitch();
      boolean forced = respawnConfig.forced;
      BlockState blockState = level.getBlockState(pos);
      Block block = blockState.getBlock();
      if (block instanceof RespawnAnchorBlock
         && (forced || (Integer)blockState.getValue(RespawnAnchorBlock.CHARGE) > 0)
         && RespawnAnchorBlock.canSetSpawn(level, pos)) {
         Runnable consumeAnchorCharge = null;
         Optional<Vec3> standUpPosition = RespawnAnchorBlock.findStandUpPosition(EntityTypes.PLAYER, level, pos);
         if (!forced && consumeSpawnBlock && standUpPosition.isPresent()) {
            consumeAnchorCharge = () -> level.setBlock(
               pos, (BlockState)blockState.setValue(RespawnAnchorBlock.CHARGE, (Integer)blockState.getValue(RespawnAnchorBlock.CHARGE) - 1), 3
            );
         }

         Runnable finalConsumeAnchorCharge = consumeAnchorCharge;
         return standUpPosition.map(p -> ServerPlayer.RespawnPosAngle.of(p, pos, 0.0F, false, true, finalConsumeAnchorCharge));
      } else {
         if (block instanceof BedBlock && ((BedRule)level.environmentAttributes().getValue(EnvironmentAttributes.BED_RULE, pos)).canSetSpawn(level)) {
            return BedBlock.findStandUpPosition(EntityTypes.PLAYER, level, pos, (Direction)blockState.getValue(BedBlock.FACING), yaw)
               .map(p -> ServerPlayer.RespawnPosAngle.of(p, pos, 0.0F, true, false, null));
         }

         if (!forced) {
            return Optional.empty();
         }

         boolean freeBottom = block.isPossibleToRespawnInThis(blockState);
         BlockState topState = level.getBlockState(pos.above());
         boolean freeTop = topState.getBlock().isPossibleToRespawnInThis(topState);
         return freeBottom && freeTop
            ? Optional.of(new ServerPlayer.RespawnPosAngle(new Vec3(pos.getX() + 0.5, pos.getY() + 0.1, pos.getZ() + 0.5), yaw, pitch, false, false, null))
            : Optional.empty();
      }
   }

   public void showEndCredits() {
      this.unRide();
      this.level().removePlayerImmediately(this, RemovalReason.CHANGED_DIMENSION);
      if (!this.wonGame) {
         this.wonGame = true;
         this.connection.send(new ClientboundGameEventPacket(ClientboundGameEventPacket.WIN_GAME, 0.0F));
         this.seenCredits = true;
      }
   }

   public @Nullable ServerPlayer teleport(TeleportTransition transition) {
      if (this.isSleeping()) {
         return null;
      }

      if (this.isRemoved()) {
         return null;
      }

      if (transition.missingRespawnBlock()) {
         this.connection.send(new ClientboundGameEventPacket(ClientboundGameEventPacket.NO_RESPAWN_BLOCK_AVAILABLE, 0.0F));
      }

      ServerLevel newLevel = transition.newLevel();
      ServerLevel oldLevel = this.level();
      ResourceKey<Level> lastDimension = oldLevel.dimension();
      ResourceKey<LevelStem> oldLevelTypeKey = oldLevel.getTypeKey();
      Location enter = this.getBukkitEntity().getLocation();
      PositionMoveRotation absolutePosition = PositionMoveRotation.calculateAbsolute(
         PositionMoveRotation.of(this), PositionMoveRotation.of(transition), transition.relatives()
      );
      Location exit = CraftLocation.toBukkit(absolutePosition.position(), newLevel, absolutePosition.yRot(), absolutePosition.xRot());
      PlayerTeleportEvent tpEvent;
      if (this.portalProcess != null
         && this.portalProcess.isSamePortal((EndGatewayBlock)Blocks.END_GATEWAY)
         && this.level().getBlockEntity(this.portalProcess.getEntryPosition()) instanceof TheEndGatewayBlockEntity theEndGatewayBlockEntity) {
         tpEvent = new PlayerTeleportEndGatewayEvent(
            this.getBukkitEntity(), enter, exit.clone(), new CraftEndGateway(this.level().getWorld(), theEndGatewayBlockEntity)
         );
      } else {
         tpEvent = new PlayerTeleportEvent(this.getBukkitEntity(), enter, exit.clone(), transition.cause());
      }

      Bukkit.getServer().getPluginManager().callEvent(tpEvent);
      Location newExit = tpEvent.getTo();
      if (tpEvent.isCancelled()) {
         return null;
      }

      if (!newExit.equals(exit)) {
         newLevel = ((CraftWorld)newExit.getWorld()).getHandle();
         transition = transition.withChangedLocation(newExit).withVelocity(Vec3.ZERO).withRelatives(Set.of());
      }

      if (!transition.asPassenger()) {
         this.removeVehicle();
      }

      if (newLevel.dimension() == lastDimension) {
         this.connection.internalTeleport(PositionMoveRotation.of(transition), transition.relatives());
         this.connection.resetPosition();
         transition.postTeleportTransition().onTransition(this);
         return this;
      }

      this.isChangingDimension = true;
      LevelData levelData = newLevel.getLevelData();
      this.connection.send(new ClientboundRespawnPacket(this.createCommonSpawnInfo(newLevel), (byte)3));
      this.connection.send(new ClientboundChangeDifficultyPacket(levelData.getDifficulty(), levelData.isDifficultyLocked()));
      PlayerList playerList = this.server.getPlayerList();
      playerList.sendPlayerPermissionLevel(this);
      this.portalProcess = null;
      oldLevel.removePlayerImmediately(this, RemovalReason.CHANGED_DIMENSION);
      this.unsetRemoved();
      ProfilerFiller profiler = Profiler.get();
      profiler.push("moving");
      if (oldLevelTypeKey == LevelStem.OVERWORLD && newLevel.getTypeKey() == LevelStem.NETHER) {
         this.enteredNetherPosition = this.position();
      }

      profiler.pop();
      profiler.push("placing");
      this.setServerLevel(newLevel);
      this.connection.internalTeleport(PositionMoveRotation.of(transition), transition.relatives());
      this.connection.resetPosition();
      newLevel.addDuringTeleport(this);
      profiler.pop();
      this.triggerDimensionChangeTriggers(oldLevel);
      this.stopUsingItem();
      this.connection.send(new ClientboundPlayerAbilitiesPacket(this.getAbilities()));
      playerList.sendLevelInfo(this, newLevel);
      playerList.sendAllPlayerInfo(this);
      playerList.sendActivePlayerEffects(this);
      transition.postTeleportTransition().onTransition(this);
      this.lastSentExp = -1;
      this.lastSentHealth = -1.0F;
      this.lastSentFood = -1;
      this.teleportSpectators(transition, oldLevel);
      PlayerChangedWorldEvent changeEvent = new PlayerChangedWorldEvent(this.getBukkitEntity(), oldLevel.getWorld());
      this.level().getCraftServer().getPluginManager().callEvent(changeEvent);
      if (this.isBlocking()) {
         this.stopUsingItem();
      }

      return this;
   }

   public void forceSetRotation(float yRot, boolean relativeY, float xRot, boolean relativeX) {
      super.forceSetRotation(yRot, relativeY, xRot, relativeX);
      this.connection.send(new ClientboundPlayerRotationPacket(yRot, relativeY, xRot, relativeX));
   }

   public void triggerDimensionChangeTriggers(ServerLevel oldLevel) {
      ResourceKey<Level> oldKey = oldLevel.dimension();
      ResourceKey<Level> newKey = this.level().dimension();
      ResourceKey<Level> oldMainDimensionKey = CraftDimensionUtil.getMainDimensionKey(oldLevel);
      ResourceKey<Level> newMainDimensionKey = CraftDimensionUtil.getMainDimensionKey(this.level());
      if (GlobalConfiguration.get().misc.strictAdvancementDimensionCheck) {
         oldMainDimensionKey = oldKey;
         newMainDimensionKey = newKey;
      }

      CriteriaTriggers.CHANGED_DIMENSION.trigger(this, oldMainDimensionKey, newMainDimensionKey);
      if (oldMainDimensionKey != oldKey || newMainDimensionKey != newKey) {
         CriteriaTriggers.CHANGED_DIMENSION.trigger(this, oldKey, newKey);
      }

      if (oldMainDimensionKey == Level.NETHER && newMainDimensionKey == Level.OVERWORLD && this.enteredNetherPosition != null) {
         CriteriaTriggers.NETHER_TRAVEL.trigger(this, this.enteredNetherPosition);
      }

      if (newMainDimensionKey != Level.NETHER) {
         this.enteredNetherPosition = null;
      }

      this.gameMode.hasDelayedDestroy = false;
   }

   public boolean broadcastToPlayer(ServerPlayer player) {
      return player.isSpectator() ? this.getCamera() == this : !this.isSpectator() && super.broadcastToPlayer(player);
   }

   public void take(Entity entity, int orgCount) {
      super.take(entity, orgCount);
      this.containerMenu.broadcastChanges();
   }

   private Either<BedSleepingProblem, Unit> getBedResult(BlockPos pos, Direction direction) {
      BedRule bedRule = (BedRule)this.level().environmentAttributes().getValue(EnvironmentAttributes.BED_RULE, pos);
      if (bedRule.explodes()) {
         return Either.left(BedSleepingProblem.EXPLOSION);
      }

      if (!this.isSleeping() && this.isAlive()) {
         BedRule rule = (BedRule)this.level().environmentAttributes().getValue(EnvironmentAttributes.BED_RULE, pos);
         boolean canSleep = rule.canSleep(this.level());
         boolean canSetSpawn = rule.canSetSpawn(this.level());
         if (!canSetSpawn && !canSleep) {
            return Either.left(rule.asProblem());
         }

         if (!this.bedInRange(pos, direction)) {
            return Either.left(BedSleepingProblem.TOO_FAR_AWAY);
         }

         if (this.bedBlocked(pos, direction)) {
            return Either.left(BedSleepingProblem.OBSTRUCTED);
         }

         if (canSetSpawn) {
            this.setRespawnPosition(
               new ServerPlayer.RespawnConfig(RespawnData.of(this.level().dimension(), pos, this.getYRot(), this.getXRot()), false),
               true,
               com.destroystokyo.paper.event.player.PlayerSetSpawnEvent.Cause.BED
            );
         }

         if (!canSleep) {
            return Either.left(rule.asProblem());
         }

         if (!this.isCreative()) {
            double hRange = 8.0;
            double vRange = 5.0;
            Vec3 bedCenter = Vec3.atBottomCenterOf(pos);
            List<Monster> monsters = this.level()
               .getEntitiesOfClass(
                  Monster.class,
                  new AABB(bedCenter.x() - 8.0, bedCenter.y() - 5.0, bedCenter.z() - 8.0, bedCenter.x() + 8.0, bedCenter.y() + 5.0, bedCenter.z() + 8.0),
                  monster -> monster.isPreventingPlayerRest(this.level(), this)
               );
            if (!monsters.isEmpty()) {
               return Either.left(BedSleepingProblem.NOT_SAFE);
            }
         }

         return Either.right(Unit.INSTANCE);
      } else {
         return Either.left(BedSleepingProblem.OTHER_PROBLEM);
      }
   }

   public Either<BedSleepingProblem, Unit> startSleepInBed(BlockPos pos, boolean force) {
      Direction direction = (Direction)this.level().getBlockState(pos).getValue(HorizontalDirectionalBlock.FACING);
      Either<BedSleepingProblem, Unit> bedResult = this.getBedResult(pos, direction);
      if (bedResult.left().orElse(null) == BedSleepingProblem.OTHER_PROBLEM) {
         return bedResult;
      }

      if (force) {
         bedResult = Either.right(Unit.INSTANCE);
      }

      bedResult = CraftEventFactory.callPlayerBedEnterEvent(this, pos, bedResult);
      if (bedResult.left().isPresent()) {
         return bedResult;
      }

      Either<BedSleepingProblem, Unit> result = super.startSleepInBed(pos, force).ifRight(unit -> {
         this.awardStat(Stats.SLEEP_IN_BED);
         CriteriaTriggers.SLEPT_IN_BED.trigger(this);
      });
      if (!this.level().canSleepThroughNights()) {
         this.sendOverlayMessage(net.minecraft.network.chat.Component.translatable("sleep.not_possible"));
      }

      this.level().updateSleepingPlayerList();
      return result;
   }

   public void startSleeping(BlockPos bedPosition) {
      this.resetStat(Stats.CUSTOM.get(Stats.TIME_SINCE_REST));
      super.startSleeping(bedPosition);
   }

   private boolean bedInRange(BlockPos pos, Direction direction) {
      return this.isReachableBedBlock(pos) || this.isReachableBedBlock(pos.relative(direction.getOpposite()));
   }

   private boolean isReachableBedBlock(BlockPos bedBlockPos) {
      Vec3 bedBlockCenter = Vec3.atBottomCenterOf(bedBlockPos);
      return Math.abs(this.getX() - bedBlockCenter.x()) <= 3.0
         && Math.abs(this.getY() - bedBlockCenter.y()) <= 2.0
         && Math.abs(this.getZ() - bedBlockCenter.z()) <= 3.0;
   }

   private boolean bedBlocked(BlockPos pos, Direction direction) {
      BlockPos above = pos.above();
      return !this.freeAt(above) || !this.freeAt(above.relative(direction.getOpposite()));
   }

   public void stopSleepInBed(boolean forcefulWakeUp, boolean updateLevelList) {
      if (this.isSleeping()) {
         org.bukkit.block.Block bed = CraftBlock.at(this.level(), this.getSleepingPos().orElse(this.blockPosition()));
         PlayerBedLeaveEvent event = new PlayerBedLeaveEvent(this.getBukkitEntity(), bed, true);
         if (event.callEvent()) {
            if (this.isSleeping()) {
               this.level().getChunkSource().sendToTrackingPlayersAndSelf(this, new ClientboundAnimatePacket(this, 2));
            }

            super.stopSleepInBed(forcefulWakeUp, updateLevelList);
            if (this.connection != null) {
               this.connection.teleport(this.getX(), this.getY(), this.getZ(), this.getYRot(), this.getXRot(), TeleportCause.EXIT_BED);
            }
         }
      }
   }

   public boolean isInvulnerableTo(ServerLevel level, DamageSource source) {
      return super.isInvulnerableTo(level, source)
         || this.isChangingDimension() && !source.is(DamageTypes.ENDER_PEARL)
         || !this.connection.hasClientLoaded()
         || !this.level().paperConfig().collisions.allowPlayerCrammingDamage && source.is(DamageTypes.CRAMMING);
   }

   protected void onChangedBlock(ServerLevel level, BlockPos pos) {
      if (!this.isSpectator()) {
         super.onChangedBlock(level, pos);
      }
   }

   protected void checkFallDamage(double ya, boolean onGround, BlockState onState, BlockPos pos) {
      if (this.spawnExtraParticlesOnFall && onGround && this.fallDistance > 0.0) {
         Vec3 centered = Vec3.atCenterOf(pos).add(0.0, 0.5, 0.0);
         int particles = (int)Mth.clamp(50.0 * this.fallDistance, 0.0, 200.0);
         this.level()
            .sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, onState), centered.x, centered.y, centered.z, particles, 0.3F, 0.3F, 0.3F, 0.15F);
         this.spawnExtraParticlesOnFall = false;
      }

      super.checkFallDamage(ya, onGround, onState, pos);
   }

   public void onExplosionHit(@Nullable Entity explosionCausedBy) {
      super.onExplosionHit(explosionCausedBy);
      this.currentExplosionImpactPos = this.position();
      this.currentExplosionCause = explosionCausedBy;
      this.setIgnoreFallDamageFromCurrentImpulse(explosionCausedBy != null && explosionCausedBy.is(EntityTypes.WIND_CHARGE), this.position());
   }

   protected void pushEntities() {
      if (this.level().tickRateManager().runsNormally()) {
         super.pushEntities();
      }
   }

   public void openTextEdit(SignBlockEntity sign, boolean isFrontText) {
      this.connection.send(new ClientboundBlockUpdatePacket(this.level(), sign.getBlockPos()));
      this.connection.send(new ClientboundOpenSignEditorPacket(sign.getBlockPos(), isFrontText));
   }

   public void openDialog(Holder<Dialog> dialog) {
      this.connection.send(new ClientboundShowDialogPacket(dialog));
   }

   public int nextContainerCounter() {
      this.containerCounter = this.containerCounter % 100 + 1;
      return this.containerCounter;
   }

   public OptionalInt openMenu(@Nullable MenuProvider provider) {
      if (provider == null) {
         return OptionalInt.empty();
      }

      this.nextContainerCounter();
      AbstractContainerMenu menu = provider.createMenu(this.containerCounter, this.getInventory(), this);
      net.minecraft.network.chat.Component title = null;
      if (menu != null) {
         menu.setTitle(provider.getDisplayName());
         boolean cancelled = false;
         Pair<Component, AbstractContainerMenu> result = CraftEventFactory.callInventoryOpenEventWithTitle(this, menu, cancelled);
         menu = (AbstractContainerMenu)result.getSecond();
         title = PaperAdventure.asVanilla((Component)result.getFirst());
         if (menu == null && !cancelled) {
            if (provider instanceof Container container) {
               container.stopOpen(this);
            } else if (provider instanceof Provider doubleChestProvider) {
               doubleChestProvider.container.stopOpen(this);
            } else if (!this.enderChestInventory.isActiveChest(null)) {
               this.enderChestInventory.stopOpen(this);
            }

            return OptionalInt.empty();
         }
      }

      if (menu == null) {
         if (this.isSpectator()) {
            this.sendOverlayMessage(net.minecraft.network.chat.Component.translatable("container.spectatorCantOpen").withStyle(ChatFormatting.RED));
         }

         return OptionalInt.empty();
      } else {
         this.containerMenu = menu;
         if (!this.isImmobile()) {
            this.connection.send(new ClientboundOpenScreenPacket(menu.containerId, menu.getType(), Objects.requireNonNullElseGet(title, menu::getTitle)));
         }

         this.initMenu(menu);
         return OptionalInt.of(this.containerCounter);
      }
   }

   public void sendMerchantOffers(int containerId, MerchantOffers offers, int merchantLevel, int merchantXp, boolean showProgressBar, boolean canRestock) {
      this.connection.send(new ClientboundMerchantOffersPacket(containerId, offers, merchantLevel, merchantXp, showProgressBar, canRestock));
   }

   public void openHorseInventory(AbstractHorse horse, Container container) {
      this.nextContainerCounter();
      AbstractContainerMenu menu = new HorseInventoryMenu(this.containerCounter, this.getInventory(), container, horse, horse.getInventoryColumns());
      menu.setTitle(horse.getDisplayName());
      menu = CraftEventFactory.callInventoryOpenEvent(this, menu);
      if (menu == null) {
         container.stopOpen(this);
      } else {
         if (this.containerMenu != this.inventoryMenu) {
            this.closeContainer(Reason.OPEN_NEW);
         }

         int inventoryColumns = horse.getInventoryColumns();
         this.connection.send(new ClientboundMountScreenOpenPacket(this.containerCounter, inventoryColumns, horse.getId()));
         this.containerMenu = menu;
         this.initMenu(this.containerMenu);
      }
   }

   public void openNautilusInventory(AbstractNautilus nautilus, Container container) {
      this.nextContainerCounter();
      AbstractContainerMenu menu = new NautilusInventoryMenu(this.containerCounter, this.getInventory(), container, nautilus, nautilus.getInventoryColumns());
      menu.setTitle(nautilus.getDisplayName());
      menu = CraftEventFactory.callInventoryOpenEvent(this, menu);
      if (menu == null) {
         container.stopOpen(this);
      } else {
         if (this.containerMenu != this.inventoryMenu) {
            this.closeContainer();
         }

         int inventoryColumns = nautilus.getInventoryColumns();
         this.connection.send(new ClientboundMountScreenOpenPacket(this.containerCounter, inventoryColumns, nautilus.getId()));
         this.containerMenu = menu;
         this.initMenu(this.containerMenu);
      }
   }

   public void openItemGui(net.minecraft.world.item.ItemStack itemStack, InteractionHand hand) {
      if (itemStack.has(DataComponents.WRITTEN_BOOK_CONTENT)) {
         if (WrittenBookContent.resolveForItem(itemStack, ResolutionContext.create(this.createCommandSourceStack()), this.registryAccess())) {
            this.containerMenu.broadcastChanges();
         }

         this.connection.send(new ClientboundOpenBookPacket(hand));
      }
   }

   public void openCommandBlock(CommandBlockEntity commandBlock) {
      this.connection.send(ClientboundBlockEntityDataPacket.create(commandBlock, BlockEntity::saveCustomOnly));
   }

   public void closeContainer() {
      this.closeContainer(Reason.UNKNOWN);
   }

   public void closeContainer(Reason reason) {
      CraftEventFactory.handleInventoryCloseEvent(this, reason);
      this.connection.send(new ClientboundContainerClosePacket(this.containerMenu.containerId));
      this.doCloseContainer();
   }

   public void closeUnloadedInventory(Reason reason) {
      CraftEventFactory.handleInventoryCloseEvent(this, reason);
      this.connection.send(new ClientboundContainerClosePacket(this.containerMenu.containerId));
      this.containerMenu = this.inventoryMenu;
   }

   public void doCloseContainer() {
      this.containerMenu.removed(this);
      this.inventoryMenu.transferState(this.containerMenu);
      this.containerMenu = this.inventoryMenu;
   }

   public void rideTick() {
      double preX = this.getX();
      double preY = this.getY();
      double preZ = this.getZ();
      super.rideTick();
      this.checkRidingStatistics(this.getX() - preX, this.getY() - preY, this.getZ() - preZ);
   }

   public void checkMovementStatistics(double dx, double dy, double dz) {
      if (!this.isPassenger() && !didNotMove(dx, dy, dz)) {
         if (this.isSwimming()) {
            int distance = Math.round((float)Math.sqrt(dx * dx + dy * dy + dz * dz) * 100.0F);
            if (distance > 0) {
               this.awardStat(Stats.SWIM_ONE_CM, distance);
               this.causeFoodExhaustion(this.level().spigotConfig.swimMultiplier * distance * 0.01F, ExhaustionReason.SWIM);
            }
         } else if (this.isEyeInFluid(FluidTags.WATER)) {
            int distance = Math.round((float)Math.sqrt(dx * dx + dy * dy + dz * dz) * 100.0F);
            if (distance > 0) {
               this.awardStat(Stats.WALK_UNDER_WATER_ONE_CM, distance);
               this.causeFoodExhaustion(this.level().spigotConfig.swimMultiplier * distance * 0.01F, ExhaustionReason.WALK_UNDERWATER);
            }
         } else if (this.isInWater()) {
            int horizontalDistance = Math.round((float)Math.sqrt(dx * dx + dz * dz) * 100.0F);
            if (horizontalDistance > 0) {
               this.awardStat(Stats.WALK_ON_WATER_ONE_CM, horizontalDistance);
               this.causeFoodExhaustion(this.level().spigotConfig.swimMultiplier * horizontalDistance * 0.01F, ExhaustionReason.WALK_ON_WATER);
            }
         } else if (this.onClimbable()) {
            if (dy > 0.0) {
               this.awardStat(Stats.CLIMB_ONE_CM, (int)Math.round(dy * 100.0));
            }
         } else if (this.onGround()) {
            int horizontalDistance = Math.round((float)Math.sqrt(dx * dx + dz * dz) * 100.0F);
            if (horizontalDistance > 0) {
               if (this.isSprinting()) {
                  this.awardStat(Stats.SPRINT_ONE_CM, horizontalDistance);
                  this.causeFoodExhaustion(this.level().spigotConfig.sprintMultiplier * horizontalDistance * 0.01F, ExhaustionReason.SPRINT);
               } else if (this.isCrouching()) {
                  this.awardStat(Stats.CROUCH_ONE_CM, horizontalDistance);
                  this.causeFoodExhaustion(this.level().spigotConfig.otherMultiplier * horizontalDistance * 0.01F, ExhaustionReason.CROUCH);
               } else {
                  this.awardStat(Stats.WALK_ONE_CM, horizontalDistance);
                  this.causeFoodExhaustion(this.level().spigotConfig.otherMultiplier * horizontalDistance * 0.01F, ExhaustionReason.WALK);
               }
            }
         } else if (this.isFallFlying()) {
            int distance = Math.round((float)Math.sqrt(dx * dx + dy * dy + dz * dz) * 100.0F);
            this.awardStat(Stats.AVIATE_ONE_CM, distance);
         } else {
            int horizontalDistance = Math.round((float)Math.sqrt(dx * dx + dz * dz) * 100.0F);
            if (horizontalDistance > 25) {
               this.awardStat(Stats.FLY_ONE_CM, horizontalDistance);
            }
         }
      }
   }

   private void checkRidingStatistics(double dx, double dy, double dz) {
      if (this.isPassenger() && !didNotMove(dx, dy, dz)) {
         int distance = Math.round((float)Math.sqrt(dx * dx + dy * dy + dz * dz) * 100.0F);
         Entity vehicle = this.getVehicle();
         if (vehicle instanceof AbstractMinecart) {
            this.awardStat(Stats.MINECART_ONE_CM, distance);
         } else if (vehicle instanceof AbstractBoat) {
            this.awardStat(Stats.BOAT_ONE_CM, distance);
         } else if (vehicle instanceof Pig) {
            this.awardStat(Stats.PIG_ONE_CM, distance);
         } else if (vehicle instanceof AbstractHorse) {
            this.awardStat(Stats.HORSE_ONE_CM, distance);
         } else if (vehicle instanceof Strider) {
            this.awardStat(Stats.STRIDER_ONE_CM, distance);
         } else if (vehicle instanceof HappyGhast) {
            this.awardStat(Stats.HAPPY_GHAST_ONE_CM, distance);
         } else if (vehicle instanceof AbstractNautilus) {
            this.awardStat(Stats.NAUTILUS_ONE_CM, distance);
         }
      }
   }

   private static boolean didNotMove(double dx, double dy, double dz) {
      return dx == 0.0 && dy == 0.0 && dz == 0.0;
   }

   public void awardStat(Stat<?> stat, int count) {
      this.stats.increment(this, stat, count);
      this.level().getCraftServer().getScoreboardManager().forAllObjectives(stat, this, score -> score.add(count));
   }

   public void resetStat(Stat<?> stat) {
      this.stats.setValue(this, stat, 0);
      this.level().getCraftServer().getScoreboardManager().forAllObjectives(stat, this, ScoreAccess::reset);
   }

   public int awardRecipes(Collection<RecipeHolder<?>> recipes) {
      return this.recipeBook.addRecipes(recipes, this);
   }

   public void triggerRecipeCrafted(RecipeHolder<?> recipe, List<net.minecraft.world.item.ItemStack> itemStacks) {
      CriteriaTriggers.RECIPE_CRAFTED.trigger(this, recipe.id(), itemStacks);
   }

   public void awardRecipesByKey(List<ResourceKey<Recipe<?>>> recipeIds) {
      List<RecipeHolder<?>> recipes = recipeIds.stream().flatMap(id -> this.server.getRecipeManager().byKey(id).stream()).collect(Collectors.toList());
      this.awardRecipes(recipes);
   }

   public int resetRecipes(Collection<RecipeHolder<?>> recipe) {
      return this.recipeBook.removeRecipes(recipe, this);
   }

   public void jumpFromGround() {
      super.jumpFromGround();
      this.awardStat(Stats.JUMP);
      if (this.isSprinting()) {
         this.causeFoodExhaustion(this.level().spigotConfig.jumpSprintExhaustion, ExhaustionReason.JUMP_SPRINT);
      } else {
         this.causeFoodExhaustion(this.level().spigotConfig.jumpWalkExhaustion, ExhaustionReason.JUMP);
      }
   }

   public void giveExperiencePoints(int i) {
      if (i != 0) {
         super.giveExperiencePoints(i);
         this.lastSentExp = -1;
      }
   }

   public void disconnect() {
      this.disconnected = true;
      this.ejectPassengers();
      if (this.isPassenger() && this.getVehicle() instanceof ServerPlayer) {
         this.stopRiding();
      }

      if (this.isSleeping()) {
         this.stopSleepInBed(true, false);
      }
   }

   public boolean hasDisconnected() {
      return this.disconnected;
   }

   public void resetSentInfo() {
      this.lastSentHealth = -1.0E8F;
      this.lastSentExp = -1;
   }

   public void completeUsingItem() {
      if (!this.useItem.isEmpty() && this.isUsingItem()) {
         this.connection.send(new ClientboundEntityEventPacket(this, (byte)9));
         super.completeUsingItem();
      }
   }

   public void lookAt(Anchor anchor, Vec3 pos) {
      super.lookAt(anchor, pos);
      this.connection.send(new ClientboundPlayerLookAtPacket(anchor, pos.x, pos.y, pos.z));
   }

   public void lookAt(Anchor fromAnchor, Entity entity, Anchor toAnchor) {
      Vec3 pos = toAnchor.apply(entity);
      super.lookAt(fromAnchor, pos);
      this.connection.send(new ClientboundPlayerLookAtPacket(fromAnchor, entity, toAnchor));
   }

   public void restoreFrom(ServerPlayer oldPlayer, boolean restoreAll) {
      this.wardenSpawnTracker = oldPlayer.wardenSpawnTracker;
      this.chatSession = oldPlayer.chatSession;
      this.gameMode.setGameModeForPlayer(oldPlayer.gameMode.getGameModeForPlayer(), oldPlayer.gameMode.getPreviousGameModeForPlayer());
      this.onUpdateAbilities();
      this.getAttributes().assignBaseValues(oldPlayer.getAttributes());
      if (restoreAll) {
         this.setHealth(oldPlayer.getHealth());
         this.foodData = oldPlayer.foodData;

         for (MobEffectInstance var4 : oldPlayer.getActiveEffects()) {
            ;
         }

         this.transferInventoryXpAndScore(oldPlayer);
         this.portalProcess = oldPlayer.portalProcess;
      } else if ((Boolean)this.level().getGameRules().get(GameRules.KEEP_INVENTORY) || oldPlayer.isSpectator()) {
         this.transferInventoryXpAndScore(oldPlayer);
      }

      this.enchantmentSeed = oldPlayer.enchantmentSeed;
      this.enderChestInventory = oldPlayer.enderChestInventory;
      this.getEntityData().set(DATA_PLAYER_MODE_CUSTOMISATION, (Byte)oldPlayer.getEntityData().get(DATA_PLAYER_MODE_CUSTOMISATION));
      this.lastSentExp = -1;
      this.lastSentHealth = -1.0F;
      this.lastSentFood = -1;
      this.seenCredits = oldPlayer.seenCredits;
      this.enteredNetherPosition = oldPlayer.enteredNetherPosition;
      this.currentExplosionImpactPos = oldPlayer.currentExplosionImpactPos;
      this.currentExplosionCause = oldPlayer.currentExplosionCause;
      this.chunkTrackingView = oldPlayer.chunkTrackingView;
      this.requestedDebugSubscriptions = oldPlayer.requestedDebugSubscriptions;
      this.setShoulderEntityLeft(oldPlayer.getShoulderEntityLeft());
      this.setShoulderEntityRight(oldPlayer.getShoulderEntityRight());
      this.setLastDeathLocation(oldPlayer.getLastDeathLocation());
      this.waypointIcon().copyFrom(oldPlayer.waypointIcon());
   }

   private void transferInventoryXpAndScore(Player oldPlayer) {
      this.getInventory().replaceWith(oldPlayer.getInventory());
      this.experienceLevel = oldPlayer.experienceLevel;
      this.totalExperience = oldPlayer.totalExperience;
      this.experienceProgress = oldPlayer.experienceProgress;
      this.setScore(oldPlayer.getScore());
   }

   protected void onEffectAdded(MobEffectInstance effect, @Nullable Entity source) {
      super.onEffectAdded(effect, source);
      this.connection.send(new ClientboundUpdateMobEffectPacket(this.getId(), effect, true));
      if (effect.is(MobEffects.LEVITATION)) {
         this.levitationStartTime = this.tickCount;
         this.levitationStartPos = this.position();
      }

      CriteriaTriggers.EFFECTS_CHANGED.trigger(this, source);
   }

   protected void onEffectUpdated(MobEffectInstance effect, boolean doRefreshAttributes, @Nullable Entity source) {
      super.onEffectUpdated(effect, doRefreshAttributes, source);
      this.connection.send(new ClientboundUpdateMobEffectPacket(this.getId(), effect, false));
      CriteriaTriggers.EFFECTS_CHANGED.trigger(this, source);
   }

   protected void onEffectsRemoved(Collection<MobEffectInstance> effects) {
      super.onEffectsRemoved(effects);

      for (MobEffectInstance effect : effects) {
         this.connection.send(new ClientboundRemoveMobEffectPacket(this.getId(), effect.getEffect()));
         if (effect.is(MobEffects.LEVITATION)) {
            this.levitationStartPos = null;
         }
      }

      CriteriaTriggers.EFFECTS_CHANGED.trigger(this, null);
   }

   public void dismountTo(double x, double y, double z) {
      this.teleportTo(x, y, z, TeleportCause.DISMOUNT);
   }

   public void teleportTo(double x, double y, double z) {
      this.teleportTo(x, y, z, TeleportCause.UNKNOWN);
   }

   public void teleportTo(double x, double y, double z, TeleportCause cause) {
      this.connection
         .teleport(new PositionMoveRotation(new Vec3(x, y, z), Vec3.ZERO, 0.0F, 0.0F), Relative.union(new Set[]{Relative.DELTA, Relative.ROTATION}), cause);
   }

   public void teleportRelative(double dx, double dy, double dz) {
      this.connection.teleport(new PositionMoveRotation(new Vec3(dx, dy, dz), Vec3.ZERO, 0.0F, 0.0F), Relative.ALL);
   }

   public boolean teleportTo(
      ServerLevel level, double x, double y, double z, Set<Relative> relatives, float newYRot, float newXRot, boolean resetCamera, TeleportCause cause
   ) {
      if (this.isSleeping()) {
         this.stopSleepInBed(true, true);
      }

      if (resetCamera) {
         this.setCamera(this);
      }

      boolean success = super.teleportTo(level, x, y, z, relatives, newYRot, newXRot, resetCamera, cause);
      if (success) {
         this.setYHeadRot(relatives.contains(Relative.Y_ROT) ? this.getYHeadRot() + newYRot : newYRot);
         this.connection.resetFlyingTicks();
      }

      return success;
   }

   public void snapTo(double x, double y, double z) {
      super.snapTo(x, y, z);
      this.connection.resetPosition();
   }

   public void crit(Entity entity) {
      this.level().getChunkSource().sendToTrackingPlayersAndSelf(this, new ClientboundAnimatePacket(entity, 4));
   }

   public void magicCrit(Entity entity) {
      this.level().getChunkSource().sendToTrackingPlayersAndSelf(this, new ClientboundAnimatePacket(entity, 5));
   }

   public void onUpdateAbilities() {
      if (this.connection != null) {
         this.connection.send(new ClientboundPlayerAbilitiesPacket(this.getAbilities()));
         this.updateInvisibilityStatus();
      }
   }

   public ServerLevel level() {
      return (ServerLevel)super.level();
   }

   public boolean setGameMode(GameType mode) {
      PlayerGameModeChangeEvent event = this.setGameMode(mode, Cause.UNKNOWN, null);
      return event != null && !event.isCancelled();
   }

   public @Nullable PlayerGameModeChangeEvent setGameMode(GameType mode, Cause cause, @Nullable Component message) {
      boolean wasSpectator = this.isSpectator();
      PlayerGameModeChangeEvent event = this.gameMode.changeGameModeForPlayer(mode, cause, message);
      if (event == null) {
         return null;
      }

      if (event.isCancelled()) {
         return event;
      }

      this.connection.send(new ClientboundGameEventPacket(ClientboundGameEventPacket.CHANGE_GAME_MODE, mode.getId()));
      if (mode == GameType.SPECTATOR) {
         this.removeEntitiesOnShoulder();
         this.stopRiding();
         this.stopUsingItem();
         EnchantmentHelper.stopLocationBasedEffects(this);
      } else {
         this.setCamera(this);
         if (wasSpectator) {
            EnchantmentHelper.runLocationChangedEffects(this.level(), this);
         }
      }

      this.onUpdateAbilities();
      this.updateEffectVisibility();
      return event;
   }

   public GameType gameMode() {
      return this.gameMode.getGameModeForPlayer();
   }

   public CommandSource commandSource() {
      return this.commandSource;
   }

   public CommandSourceStack createCommandSourceStack() {
      return new CommandSourceStack(
         this.commandSource(),
         this.position(),
         this.getRotationVector(),
         this.level(),
         this.permissions(),
         this.getPlainTextName(),
         this.getDisplayName(),
         this.server,
         this
      );
   }

   public void sendSystemMessage(net.minecraft.network.chat.Component message) {
      this.sendSystemMessage(message, false);
   }

   public void sendOverlayMessage(net.minecraft.network.chat.Component message) {
      this.sendSystemMessage(message, true);
   }

   public void sendBuildLimitMessage(boolean isTooHigh, int limit) {
      this.sendOverlayMessage(
         net.minecraft.network.chat.Component.translatable(isTooHigh ? "build.tooHigh" : "build.tooLow", limit).withStyle(ChatFormatting.RED)
      );
   }

   public void sendSpawnProtectionMessage(BlockPos pos) {
      this.sendOverlayMessage(net.minecraft.network.chat.Component.translatable("build.spawn_protection", pos.toShortString()).withStyle(ChatFormatting.RED));
   }

   public void sendSystemMessage(net.minecraft.network.chat.Component message, boolean overlay) {
      if (this.acceptsSystemMessages(overlay)) {
         this.connection
            .send(
               new ClientboundSystemChatPacket(message, overlay),
               PacketSendListener.exceptionallySend(
                  () -> {
                     if (this.acceptsSystemMessages(false)) {
                        int truncatedMessageSize = 256;
                        String contents = message.getString(256);
                        net.minecraft.network.chat.Component contentsMsg = net.minecraft.network.chat.Component.literal(contents)
                           .withStyle(ChatFormatting.YELLOW);
                        return new ClientboundSystemChatPacket(
                           net.minecraft.network.chat.Component.translatable("multiplayer.message_not_delivered", contentsMsg).withStyle(ChatFormatting.RED),
                           false
                        );
                     } else {
                        return null;
                     }
                  }
               )
            );
      }
   }

   public void sendChatMessage(OutgoingChatMessage message, boolean filtered, ChatType.Bound chatType) {
      this.sendChatMessage(message, filtered, chatType, null);
   }

   public void sendChatMessage(OutgoingChatMessage message, boolean filtered, ChatType.Bound chatType, @Nullable Component unsigned) {
      if (this.acceptsChatMessages()) {
         message.sendToPlayer(this, filtered, chatType, unsigned);
      }
   }

   public String getIpAddress() {
      return this.connection.getRemoteAddress() instanceof InetSocketAddress ipSocketAddress
         ? InetAddresses.toAddrString(ipSocketAddress.getAddress())
         : "<unknown>";
   }

   public void updateOptions(ClientInformation information) {
      new PlayerClientOptionsChangeEvent(this.getBukkitEntity(), (Map)Util.make(new IdentityHashMap(), map -> {
         map.put(ClientOption.LOCALE, information.language());
         map.put(ClientOption.VIEW_DISTANCE, information.viewDistance());
         map.put(ClientOption.CHAT_VISIBILITY, ChatVisibility.valueOf(information.chatVisibility().name()));
         map.put(ClientOption.CHAT_COLORS_ENABLED, information.chatColors());
         map.put(ClientOption.SKIN_PARTS, new PaperSkinParts(information.modelCustomisation()));
         map.put(ClientOption.MAIN_HAND, information.mainHand() == HumanoidArm.LEFT ? MainHand.LEFT : MainHand.RIGHT);
         map.put(ClientOption.TEXT_FILTERING_ENABLED, information.textFilteringEnabled());
         map.put(ClientOption.ALLOW_SERVER_LISTINGS, information.allowsListing());
         map.put(ClientOption.PARTICLE_VISIBILITY, ParticleVisibility.valueOf(information.particleStatus().name()));
      })).callEvent();
      if (this.getMainArm() != information.mainHand()) {
         PlayerChangedMainHandEvent event = new PlayerChangedMainHandEvent(
            this.getBukkitEntity(), information.mainHand() == HumanoidArm.LEFT ? MainHand.LEFT : MainHand.RIGHT
         );
         event.callEvent();
      }

      if (this.language == null || !this.language.equals(information.language())) {
         PlayerLocaleChangeEvent event = new PlayerLocaleChangeEvent(this.getBukkitEntity(), information.language());
         event.callEvent();
      }

      this.updateOptionsNoEvents(information);
   }

   public void updateOptionsNoEvents(ClientInformation information) {
      this.language = information.language();
      this.adventure$locale = Objects.requireNonNullElse(Translator.parseLocale(this.language), Locale.US);
      this.requestedViewDistance = information.viewDistance();
      this.chatVisibility = information.chatVisibility();
      this.canChatColor = information.chatColors();
      this.textFilteringEnabled = information.textFilteringEnabled();
      this.allowsListing = information.allowsListing();
      this.particleStatus = information.particleStatus();
      this.getEntityData().set(DATA_PLAYER_MODE_CUSTOMISATION, (byte)information.modelCustomisation());
      this.getEntityData().set(DATA_PLAYER_MAIN_HAND, information.mainHand());
   }

   public ClientInformation clientInformation() {
      int modelCustomization = (Byte)this.getEntityData().get(DATA_PLAYER_MODE_CUSTOMISATION);
      return new ClientInformation(
         this.language,
         this.requestedViewDistance,
         this.chatVisibility,
         this.canChatColor,
         modelCustomization,
         this.getMainArm(),
         this.textFilteringEnabled,
         this.allowsListing,
         this.particleStatus
      );
   }

   public boolean canChatInColor() {
      return this.canChatColor;
   }

   public ChatVisiblity getChatVisibility() {
      return this.chatVisibility;
   }

   private boolean acceptsSystemMessages(boolean overlay) {
      return this.chatVisibility != ChatVisiblity.HIDDEN || overlay;
   }

   private boolean acceptsChatMessages() {
      return this.chatVisibility == ChatVisiblity.FULL;
   }

   public int requestedViewDistance() {
      return this.requestedViewDistance;
   }

   public void sendServerStatus(ServerStatus status) {
      this.connection.send(new ClientboundServerDataPacket(status.description(), status.favicon().map(Favicon::iconBytes)));
   }

   public PermissionSet permissions() {
      return this.server.getProfilePermissions(this.nameAndId());
   }

   public void resetLastActionTime() {
      this.lastActionTime = Util.getMillis();
   }

   public ServerStatsCounter getStats() {
      return this.stats;
   }

   public ServerRecipeBook getRecipeBook() {
      return this.recipeBook;
   }

   protected void updateInvisibilityStatus() {
      if (this.isSpectator()) {
         this.removeEffectParticles();
         this.setInvisible(true);
      } else {
         super.updateInvisibilityStatus();
      }
   }

   public Entity getCamera() {
      return (Entity)(this.camera == null ? this : this.camera);
   }

   public void setCamera(@Nullable Entity newCamera) {
      Entity oldCamera = this.getCamera();
      this.camera = (Entity)(newCamera == null ? this : newCamera);
      if (oldCamera != this.camera) {
         if (this.camera == this) {
            PlayerStopSpectatingEntityEvent spectateEvent = new PlayerStopSpectatingEntityEvent(this.getBukkitEntity(), oldCamera.getBukkitEntity());
            if (!spectateEvent.callEvent()) {
               this.camera = oldCamera;
               return;
            }
         } else {
            PlayerStartSpectatingEntityEvent spectateEvent = new PlayerStartSpectatingEntityEvent(
               this.getBukkitEntity(), oldCamera.getBukkitEntity(), newCamera.getBukkitEntity()
            );
            if (!spectateEvent.callEvent()) {
               this.camera = oldCamera;
               return;
            }
         }

         if (this.camera.level() instanceof ServerLevel level) {
            this.teleportTo(
               level, this.camera.getX(), this.camera.getY(), this.camera.getZ(), Set.of(), this.getYRot(), this.getXRot(), false, TeleportCause.SPECTATE
            );
         }

         if (newCamera != null) {
            this.level().getChunkSource().move(this);
         }

         this.connection.send(new ClientboundSetCameraPacket(this.camera));
         this.connection.resetPosition();
      }
   }

   protected void processPortalCooldown() {
      if (!this.isChangingDimension) {
         super.processPortalCooldown();
      }
   }

   public long getLastActionTime() {
      return this.lastActionTime;
   }

   public @Nullable Component getTabListDisplayName() {
      return this.listName;
   }

   public int getTabListOrder() {
      return this.listOrder;
   }

   public void swing(InteractionHand hand) {
      super.swing(hand);
      this.resetAttackStrengthTicker();
   }

   public boolean isChangingDimension() {
      return this.isChangingDimension;
   }

   public void hasChangedDimension() {
      this.isChangingDimension = false;
   }

   public PlayerAdvancements getAdvancements() {
      return this.advancements;
   }

   public ServerPlayer.@Nullable RespawnConfig getRespawnConfig() {
      return this.respawnConfig;
   }

   public void copyRespawnPosition(ServerPlayer player) {
      this.setRespawnPosition(player.respawnConfig, false);
   }

   public void setRespawnPosition(ServerPlayer.@Nullable RespawnConfig respawnConfig, boolean showMessage) {
      this.setRespawnPosition(respawnConfig, showMessage, com.destroystokyo.paper.event.player.PlayerSetSpawnEvent.Cause.UNKNOWN);
   }

   public boolean setRespawnPosition(
      ServerPlayer.@Nullable RespawnConfig respawnConfig, boolean showMessage, com.destroystokyo.paper.event.player.PlayerSetSpawnEvent.Cause cause
   ) {
      Location spawnLoc = null;
      boolean actuallyDisplayInChat = false;
      if (respawnConfig != null) {
         actuallyDisplayInChat = showMessage && !respawnConfig.isSamePosition(this.respawnConfig);
         spawnLoc = CraftLocation.toBukkit(respawnConfig.respawnData().pos(), this.server.getLevel(respawnConfig.respawnData().dimension()));
         spawnLoc.setYaw(respawnConfig.respawnData().yaw());
         spawnLoc.setPitch(respawnConfig.respawnData().pitch());
      }

      PlayerSpawnChangeEvent dumbEvent = new PlayerSpawnChangeEvent(
         this.getBukkitEntity(),
         spawnLoc,
         respawnConfig != null && respawnConfig.forced(),
         cause == com.destroystokyo.paper.event.player.PlayerSetSpawnEvent.Cause.PLAYER_RESPAWN
            ? org.bukkit.event.player.PlayerSpawnChangeEvent.Cause.RESET
            : org.bukkit.event.player.PlayerSpawnChangeEvent.Cause.valueOf(cause.name())
      );
      dumbEvent.callEvent();
      PlayerSetSpawnEvent event = new PlayerSetSpawnEvent(
         this.getBukkitEntity(),
         cause,
         dumbEvent.getNewSpawn(),
         dumbEvent.isForced(),
         actuallyDisplayInChat,
         actuallyDisplayInChat ? PaperAdventure.asAdventure(SPAWN_SET_MESSAGE) : null
      );
      event.setCancelled(dumbEvent.isCancelled());
      if (!event.callEvent()) {
         return false;
      }

      if (event.getLocation() != null) {
         respawnConfig = new ServerPlayer.RespawnConfig(
            RespawnData.of(
               ((CraftWorld)event.getLocation().getWorld()).getHandle().dimension(),
               CraftLocation.toBlockPos(event.getLocation()),
               event.getLocation().getYaw(),
               event.getLocation().getPitch()
            ),
            event.isForced()
         );
         if (event.willNotifyPlayer() && event.getNotification() != null) {
            this.sendSystemMessage(PaperAdventure.asVanilla(event.getNotification()));
         }
      }

      this.respawnConfig = respawnConfig;
      return true;
   }

   public SectionPos getLastSectionPos() {
      return this.lastSectionPos;
   }

   public void setLastSectionPos(SectionPos lastSectionPos) {
      this.lastSectionPos = lastSectionPos;
   }

   public ChunkTrackingView getChunkTrackingView() {
      return this.chunkTrackingView;
   }

   public void setChunkTrackingView(ChunkTrackingView chunkTrackingView) {
      this.chunkTrackingView = chunkTrackingView;
   }

   public ItemEntity drop(
      net.minecraft.world.item.ItemStack itemStack, boolean randomly, boolean thrownFromHand, boolean callEvent, @Nullable Consumer<Item> entityOperation
   ) {
      ItemEntity entity = super.drop(itemStack, randomly, thrownFromHand, callEvent, entityOperation);
      net.minecraft.world.item.ItemStack droppedItemStack = entity != null ? entity.getItem() : net.minecraft.world.item.ItemStack.EMPTY;
      if (thrownFromHand && !droppedItemStack.isEmpty()) {
         this.awardStat(Stats.ITEM_DROPPED.get(droppedItemStack.getItem()), droppedItemStack.getCount());
         this.awardStat(Stats.DROP);
      }

      if (droppedItemStack.is(Items.FILLED_MAP)) {
         MapItemSavedData mapData = MapItem.getSavedData(droppedItemStack, this.level());
         if (mapData != null) {
            mapData.tickCarriedBy(this, droppedItemStack, null);
         }
      }

      return entity;
   }

   public TextFilter getTextFilter() {
      return this.textFilter;
   }

   public void setServerLevel(ServerLevel level) {
      this.setLevel(level);
      this.gameMode.setLevel(level);
   }

   private static @Nullable GameType readPlayerMode(ValueInput playerInput, String modeTag) {
      return (GameType)playerInput.read(modeTag, GameType.LEGACY_ID_CODEC).orElse(null);
   }

   private GameType calculateGameModeForNewPlayer(@Nullable GameType loadedGameType) {
      GameType forcedGameType = this.server.getForcedGameType();
      if (forcedGameType != null) {
         return forcedGameType;
      } else {
         return loadedGameType != null ? loadedGameType : this.server.getDefaultGameType();
      }
   }

   private void storeGameTypes(ValueOutput playerOutput) {
      playerOutput.store("playerGameType", GameType.LEGACY_ID_CODEC, this.gameMode.getGameModeForPlayer());
      GameType previousGameMode = this.gameMode.getPreviousGameModeForPlayer();
      playerOutput.storeNullable("previousPlayerGameType", GameType.LEGACY_ID_CODEC, previousGameMode);
   }

   public boolean isTextFilteringEnabled() {
      return this.textFilteringEnabled;
   }

   public boolean shouldFilterMessageTo(ServerPlayer serverPlayer) {
      return serverPlayer != this && (this.textFilteringEnabled || serverPlayer.textFilteringEnabled);
   }

   public boolean mayInteract(ServerLevel level, BlockPos pos) {
      return super.mayInteract(level, pos) && level.mayInteract(this, pos);
   }

   protected void updateUsingItem(net.minecraft.world.item.ItemStack useItem) {
      CriteriaTriggers.USING_ITEM.trigger(this, useItem);
      super.updateUsingItem(useItem);
   }

   public boolean drop(boolean all) {
      Inventory inventory = this.getInventory();
      net.minecraft.world.item.ItemStack removed = inventory.removeFromSelected(all);
      this.containerMenu
         .findSlot(inventory, inventory.getSelectedSlot())
         .ifPresent(slotIndex -> this.containerMenu.setRemoteSlot(slotIndex, inventory.getSelectedItem()));
      if (this.useItem.isEmpty()) {
         this.stopUsingItem();
      }

      return this.drop(removed, false, true) != null;
   }

   public void handleExtraItemsCreatedOnUse(net.minecraft.world.item.ItemStack extraItems) {
      if (!this.getInventory().add(extraItems)) {
         this.drop(extraItems, false);
      }
   }

   public boolean allowsListing() {
      return this.allowsListing;
   }

   public Optional<WardenSpawnTracker> getWardenSpawnTracker() {
      return Optional.of(this.wardenSpawnTracker);
   }

   public void setSpawnExtraParticlesOnFall(boolean toggle) {
      this.spawnExtraParticlesOnFall = toggle;
   }

   public void onItemPickup(ItemEntity entity) {
      super.onItemPickup(entity);
      Entity thrower = entity.getOwner();
      if (thrower != null) {
         CriteriaTriggers.THROWN_ITEM_PICKED_UP_BY_PLAYER.trigger(this, entity.getItem(), thrower);
      }
   }

   public void setChatSession(RemoteChatSession chatSession) {
      this.chatSession = chatSession;
   }

   public @Nullable RemoteChatSession getChatSession() {
      return this.chatSession != null && this.chatSession.hasExpired() ? null : this.chatSession;
   }

   public void indicateDamage(double xd, double zd) {
      this.hurtDir = (float)(Mth.atan2(zd, xd) * 180.0 / (float) Math.PI - this.getYRot());
      this.connection.send(new ClientboundHurtAnimationPacket(this));
   }

   public boolean startRiding(Entity entityToRide, boolean force, boolean sendEventAndTriggers) {
      if (super.startRiding(entityToRide, force, sendEventAndTriggers)) {
         entityToRide.positionRider(this);
         this.connection.teleport(new PositionMoveRotation(this.position(), Vec3.ZERO, 0.0F, 0.0F), Relative.ROTATION);
         if (entityToRide instanceof LivingEntity livingEntity) {
            this.server.getPlayerList().sendActiveEffects(livingEntity, this.connection);
         }

         this.connection.send(new ClientboundSetPassengersPacket(entityToRide));
         return true;
      } else {
         return false;
      }
   }

   public void removeVehicle(boolean suppressCancellation) {
      Entity oldVehicle = this.getVehicle();
      super.removeVehicle(suppressCancellation);
      if (oldVehicle instanceof LivingEntity livingEntity) {
         for (MobEffectInstance effect : livingEntity.getActiveEffects()) {
            this.connection.send(new ClientboundRemoveMobEffectPacket(oldVehicle.getId(), effect.getEffect()));
         }
      }

      if (oldVehicle != null) {
         this.connection.send(new ClientboundSetPassengersPacket(oldVehicle));
      }
   }

   public CommonPlayerSpawnInfo createCommonSpawnInfo(ServerLevel level) {
      return new CommonPlayerSpawnInfo(
         level.dimensionTypeRegistration(),
         level.dimension(),
         BiomeManager.obfuscateSeed(level.getSeed()),
         this.gameMode.getGameModeForPlayer(),
         this.gameMode.getPreviousGameModeForPlayer(),
         level.isDebug(),
         level.isFlat(),
         this.getLastDeathLocation(),
         this.getPortalCooldown(),
         level.getSeaLevel()
      );
   }

   public void setRaidOmenPosition(BlockPos raidOmenPosition) {
      this.raidOmenPosition = raidOmenPosition;
   }

   public void clearRaidOmenPosition() {
      this.raidOmenPosition = null;
   }

   public @Nullable BlockPos getRaidOmenPosition() {
      return this.raidOmenPosition;
   }

   public Vec3 getKnownMovement() {
      Entity vehicle = this.getVehicle();
      return vehicle != null && vehicle.getControllingPassenger() != this ? vehicle.getKnownMovement() : this.lastKnownClientMovement;
   }

   public Vec3 getKnownSpeed() {
      Entity vehicle = this.getVehicle();
      return vehicle != null && vehicle.getControllingPassenger() != this ? vehicle.getKnownSpeed() : this.lastKnownClientMovement;
   }

   public void setKnownMovement(Vec3 lastKnownClientMovement) {
      this.lastKnownClientMovement = lastKnownClientMovement;
   }

   protected float getEnchantedDamage(Entity entity, float dmg, DamageSource damageSource) {
      return EnchantmentHelper.modifyDamage(this.level(), this.getWeaponItem(), entity, damageSource, dmg);
   }

   public void onEquippedItemBroken(net.minecraft.world.item.Item brokenItem, EquipmentSlot inSlot) {
      super.onEquippedItemBroken(brokenItem, inSlot);
      this.awardStat(Stats.ITEM_BROKEN.get(brokenItem));
   }

   public Input getLastClientInput() {
      return this.lastClientInput;
   }

   public void setLastClientInput(Input lastClientInput) {
      this.lastClientInput = lastClientInput;
   }

   public Vec3 getLastClientMoveIntent() {
      float leftIntent = this.lastClientInput.left() == this.lastClientInput.right() ? 0.0F : (this.lastClientInput.left() ? 1.0F : -1.0F);
      float forwardIntent = this.lastClientInput.forward() == this.lastClientInput.backward() ? 0.0F : (this.lastClientInput.forward() ? 1.0F : -1.0F);
      return getInputVector(new Vec3(leftIntent, 0.0, forwardIntent), 1.0F, this.getYRot());
   }

   public void registerEnderPearl(ThrownEnderpearl enderPearl) {
      this.enderPearls.add(enderPearl);
   }

   public void deregisterEnderPearl(ThrownEnderpearl enderPearl) {
      this.enderPearls.remove(enderPearl);
   }

   public Set<ThrownEnderpearl> getEnderPearls() {
      return this.enderPearls;
   }

   public CompoundTag getShoulderEntityLeft() {
      return this.shoulderEntityLeft;
   }

   public void setShoulderEntityLeft(CompoundTag tag) {
      this.shoulderEntityLeft = tag;
      this.setShoulderParrotLeft(extractParrotVariant(tag));
   }

   public CompoundTag getShoulderEntityRight() {
      return this.shoulderEntityRight;
   }

   public void setShoulderEntityRight(CompoundTag tag) {
      this.shoulderEntityRight = tag;
      this.setShoulderParrotRight(extractParrotVariant(tag));
   }

   public long registerAndUpdateEnderPearlTicket(ThrownEnderpearl enderpearl) {
      if (enderpearl.level() instanceof ServerLevel enderPearlLevel) {
         ChunkPos chunkPos = enderpearl.chunkPosition();
         this.registerEnderPearl(enderpearl);
         enderPearlLevel.resetEmptyTime();
         return placeEnderPearlTicket(enderPearlLevel, chunkPos) - 1L;
      } else {
         return 0L;
      }
   }

   public static long placeEnderPearlTicket(ServerLevel level, ChunkPos chunk) {
      if (!level.paperConfig().misc.legacyEnderPearlBehavior) {
         level.getChunkSource().addTicketWithRadius(TicketType.ENDER_PEARL, chunk, 2);
      }

      return TicketType.ENDER_PEARL.timeout();
   }

   public void requestDebugSubscriptions(Set<DebugSubscription<?>> subscriptions) {
      this.requestedDebugSubscriptions = Set.copyOf(subscriptions);
   }

   public Set<DebugSubscription<?>> debugSubscriptions() {
      return !this.server.debugSubscribers().hasRequiredPermissions(this) ? Set.of() : this.requestedDebugSubscriptions;
   }

   public long getPlayerTime() {
      return this.relativeTime
         ? this.level().getDefaultClockTime() + this.timeOffset
         : this.level().getDefaultClockTime() - this.level().getDefaultClockTime() % 24000L + this.timeOffset;
   }

   public void setPlayerWeather(WeatherType type, boolean plugin) {
      if (plugin || this.weatherType == null) {
         if (plugin) {
            this.weatherType = type;
         }

         if (type == WeatherType.DOWNFALL) {
            this.connection.send(new ClientboundGameEventPacket(ClientboundGameEventPacket.STOP_RAINING, 0.0F));
         } else {
            this.connection.send(new ClientboundGameEventPacket(ClientboundGameEventPacket.START_RAINING, 0.0F));
         }
      }
   }

   public void updateWeather(float oldRain, float newRain, float oldThunder, float newThunder) {
      if (this.weatherType == null) {
         if (oldRain != newRain) {
            this.connection.send(new ClientboundGameEventPacket(ClientboundGameEventPacket.RAIN_LEVEL_CHANGE, newRain));
         }
      } else if (this.pluginRainPositionPrevious != this.pluginRainPosition) {
         this.connection.send(new ClientboundGameEventPacket(ClientboundGameEventPacket.RAIN_LEVEL_CHANGE, this.pluginRainPosition));
      }

      if (oldThunder != newThunder) {
         if (this.weatherType != WeatherType.DOWNFALL && this.weatherType != null) {
            this.connection.send(new ClientboundGameEventPacket(ClientboundGameEventPacket.THUNDER_LEVEL_CHANGE, 0.0F));
         } else {
            this.connection.send(new ClientboundGameEventPacket(ClientboundGameEventPacket.THUNDER_LEVEL_CHANGE, newThunder));
         }
      }
   }

   public void tickWeather() {
      if (this.weatherType != null) {
         this.pluginRainPositionPrevious = this.pluginRainPosition;
         if (this.weatherType == WeatherType.DOWNFALL) {
            this.pluginRainPosition += 0.01F;
         } else {
            this.pluginRainPosition -= 0.01F;
         }

         this.pluginRainPosition = Mth.clamp(this.pluginRainPosition, 0.0F, 1.0F);
      }
   }

   public void resetPlayerWeather() {
      this.weatherType = null;
      this.setPlayerWeather(this.level().isRaining() ? WeatherType.DOWNFALL : WeatherType.CLEAR, false);
   }

   public String toString() {
      return super.toString() + "(" + this.getScoreboardName() + " at " + this.getX() + "," + this.getY() + "," + this.getZ() + ")";
   }

   public boolean isImmobile() {
      return super.isImmobile() || this.connection != null && this.connection.isDisconnected();
   }

   public void reset() {
      float exp = 0.0F;
      if (this.keepLevel) {
         exp = this.experienceProgress;
         this.newTotalExp = this.totalExperience;
         this.newLevel = this.experienceLevel;
      }

      this.setHealth(this.getMaxHealth());
      this.stopUsingItem();
      this.setAirSupply(this.getMaxAirSupply());
      this.setRemainingFireTicks(0);
      this.fallDistance = 0.0;
      this.foodData = new FoodData();
      this.experienceLevel = this.newLevel;
      this.totalExperience = this.newTotalExp;
      this.experienceProgress = 0.0F;
      this.deathTime = 0;
      this.setStingerCount(0);
      this.removeStingerTime = 0;
      this.setArrowCount(0, true);
      this.removeArrowTime = 0;
      this.removeAllEffects(org.bukkit.event.entity.EntityPotionEffectEvent.Cause.DEATH);
      this.effectsDirty = true;
      this.containerMenu = this.inventoryMenu;
      this.lastHurtByPlayer = null;
      this.lastHurtByMob = null;
      this.combatTracker = new CombatTracker(this);
      this.lastSentExp = -1;
      if (this.keepLevel) {
         this.experienceProgress = exp;
      } else {
         this.giveExperiencePoints(this.newExp);
      }

      this.keepLevel = false;
      this.setDeltaMovement(0.0, 0.0, 0.0);
      this.skipDropExperience = false;
   }

   public CraftPlayer getBukkitEntity() {
      return (CraftPlayer)super.getBukkitEntity();
   }

   public record RespawnConfig(RespawnData respawnData, boolean forced) {
      public static final Codec<ServerPlayer.RespawnConfig> CODEC = RecordCodecBuilder.create(
         i -> i.group(
               RespawnData.MAP_CODEC.forGetter(ServerPlayer.RespawnConfig::respawnData),
               Codec.BOOL.optionalFieldOf("forced", false).forGetter(ServerPlayer.RespawnConfig::forced)
            )
            .apply(i, ServerPlayer.RespawnConfig::new)
      );

      private static ResourceKey<Level> getDimensionOrDefault(ServerPlayer.@Nullable RespawnConfig respawnConfig) {
         return respawnConfig != null ? respawnConfig.respawnData().dimension() : Level.OVERWORLD;
      }

      public boolean isSamePosition(ServerPlayer.@Nullable RespawnConfig other) {
         return other != null && this.respawnData.globalPos().equals(other.respawnData.globalPos());
      }
   }

   public record RespawnPosAngle(Vec3 position, float yaw, float pitch, boolean isBedSpawn, boolean isAnchorSpawn, @Nullable Runnable consumeAnchorCharge) {
      public static ServerPlayer.RespawnPosAngle of(
         Vec3 position, BlockPos lookAtBlockPos, float pitch, boolean isBedSpawn, boolean isAnchorSpawn, @Nullable Runnable consumeAnchorCharge
      ) {
         return new ServerPlayer.RespawnPosAngle(position, calculateLookAtYaw(position, lookAtBlockPos), pitch, isBedSpawn, isAnchorSpawn, consumeAnchorCharge);
      }

      private static float calculateLookAtYaw(Vec3 position, BlockPos lookAtBlockPos) {
         Vec3 lookDirection = Vec3.atBottomCenterOf(lookAtBlockPos).subtract(position).normalize();
         return (float)Mth.wrapDegrees(Mth.atan2(lookDirection.z, lookDirection.x) * 180.0 / (float) Math.PI - 90.0);
      }
   }

   public record RespawnResult(TeleportTransition transition, boolean isBedSpawn, boolean isAnchorSpawn) {
   }

   public record SavedPosition(Optional<ResourceKey<Level>> dimension, Optional<Vec3> position, Optional<Vec2> rotation) {
      public static final MapCodec<ServerPlayer.SavedPosition> MAP_CODEC = RecordCodecBuilder.mapCodec(
         i -> i.group(
               Level.RESOURCE_KEY_CODEC.optionalFieldOf("Dimension").forGetter(ServerPlayer.SavedPosition::dimension),
               Vec3.CODEC.optionalFieldOf("Pos").forGetter(ServerPlayer.SavedPosition::position),
               Vec2.CODEC.optionalFieldOf("Rotation").forGetter(ServerPlayer.SavedPosition::rotation)
            )
            .apply(i, ServerPlayer.SavedPosition::new)
      );
      public static final ServerPlayer.SavedPosition EMPTY = new ServerPlayer.SavedPosition(Optional.empty(), Optional.empty(), Optional.empty());
   }
}
