package net.minecraft.server.network;

import ca.spottedleaf.moonrise.patches.collisions.CollisionUtil;
import com.destroystokyo.paper.event.brigadier.AsyncPlayerSendSuggestionsEvent;
import com.destroystokyo.paper.event.player.PlayerJumpEvent;
import com.destroystokyo.paper.event.player.PlayerRecipeBookClickEvent;
import com.destroystokyo.paper.event.server.AsyncTabCompleteEvent;
import com.destroystokyo.paper.event.server.AsyncTabCompleteEvent.Completion;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.primitives.Floats;
import com.google.common.primitives.Ints;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.mojang.datafixers.util.Pair;
import com.mojang.logging.LogUtils;
import io.papermc.paper.adventure.ChatProcessor;
import io.papermc.paper.adventure.PaperAdventure;
import io.papermc.paper.annotation.DoNotUse;
import io.papermc.paper.brigadier.TagParseCommandSyntaxException;
import io.papermc.paper.configuration.GlobalConfiguration;
import io.papermc.paper.configuration.type.number.IntOr.Disabled;
import io.papermc.paper.connection.DisconnectionReason;
import io.papermc.paper.connection.PaperPlayerGameConnection;
import io.papermc.paper.connection.PlayerCommonConnection;
import io.papermc.paper.event.connection.configuration.PlayerConnectionReconfigureEvent;
import io.papermc.paper.event.packet.ClientTickEndEvent;
import io.papermc.paper.event.packet.UncheckedSignChangeEvent;
import io.papermc.paper.event.player.CartographyItemEvent;
import io.papermc.paper.event.player.PlayerArmSwingEvent;
import io.papermc.paper.event.player.PlayerClientLoadedWorldEvent;
import io.papermc.paper.event.player.PlayerFailMoveEvent;
import io.papermc.paper.event.player.PlayerPickBlockEvent;
import io.papermc.paper.event.player.PlayerPickEntityEvent;
import io.papermc.paper.event.player.PlayerPickItemEvent;
import io.papermc.paper.event.player.PlayerFailMoveEvent.FailReason;
import io.papermc.paper.util.MCUtil;
import io.papermc.paper.util.TraceUtil;
import it.unimi.dsi.fastutil.ints.Int2ObjectMaps;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.translation.Translator;
import net.minecraft.ChatFormatting;
import net.minecraft.DefaultUncaughtExceptionHandlerWithName;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.triggers.CriteriaTriggers;
import net.minecraft.commands.CommandSigningContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.CommandSigningContext.SignedArguments;
import net.minecraft.commands.arguments.ArgumentSignatures.Entry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Registry;
import net.minecraft.core.Vec3i;
import net.minecraft.core.BlockPos.MutableBlockPos;
import net.minecraft.core.Holder.Reference;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestInstance;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.Connection;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.HashedStack;
import net.minecraft.network.TickablePacketListener;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.LastSeenMessages;
import net.minecraft.network.chat.LastSeenMessagesValidator;
import net.minecraft.network.chat.MessageSignature;
import net.minecraft.network.chat.MessageSignatureCache;
import net.minecraft.network.chat.PlayerChatMessage;
import net.minecraft.network.chat.RemoteChatSession;
import net.minecraft.network.chat.SignableCommand;
import net.minecraft.network.chat.SignedMessageBody;
import net.minecraft.network.chat.ChatType.Bound;
import net.minecraft.network.chat.LastSeenMessages.Update;
import net.minecraft.network.chat.LastSeenMessagesValidator.ValidationException;
import net.minecraft.network.chat.RemoteChatSession.Data;
import net.minecraft.network.chat.SignableCommand.Argument;
import net.minecraft.network.chat.SignedMessageChain.DecodeException;
import net.minecraft.network.chat.SignedMessageChain.Decoder;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketUtils;
import net.minecraft.network.protocol.common.ServerboundClientInformationPacket;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.ServerboundResourcePackPacket;
import net.minecraft.network.protocol.configuration.ConfigurationProtocols;
import net.minecraft.network.protocol.game.ClientboundBlockChangedAckPacket;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundCommandSuggestionsPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.network.protocol.game.ClientboundDisguisedChatPacket;
import net.minecraft.network.protocol.game.ClientboundGameRuleValuesPacket;
import net.minecraft.network.protocol.game.ClientboundMoveVehiclePacket;
import net.minecraft.network.protocol.game.ClientboundPlaceGhostRecipePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerChatPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundSetCursorItemPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityLinkPacket;
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket;
import net.minecraft.network.protocol.game.ClientboundSetHeldSlotPacket;
import net.minecraft.network.protocol.game.ClientboundStartConfigurationPacket;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.network.protocol.game.ClientboundTagQueryPacket;
import net.minecraft.network.protocol.game.ClientboundTestInstanceBlockStatus;
import net.minecraft.network.protocol.game.ServerGamePacketListener;
import net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket;
import net.minecraft.network.protocol.game.ServerboundAttackPacket;
import net.minecraft.network.protocol.game.ServerboundBlockEntityTagQueryPacket;
import net.minecraft.network.protocol.game.ServerboundChangeDifficultyPacket;
import net.minecraft.network.protocol.game.ServerboundChangeGameModePacket;
import net.minecraft.network.protocol.game.ServerboundChatAckPacket;
import net.minecraft.network.protocol.game.ServerboundChatCommandPacket;
import net.minecraft.network.protocol.game.ServerboundChatCommandSignedPacket;
import net.minecraft.network.protocol.game.ServerboundChatPacket;
import net.minecraft.network.protocol.game.ServerboundChatSessionUpdatePacket;
import net.minecraft.network.protocol.game.ServerboundChunkBatchReceivedPacket;
import net.minecraft.network.protocol.game.ServerboundClientCommandPacket;
import net.minecraft.network.protocol.game.ServerboundClientTickEndPacket;
import net.minecraft.network.protocol.game.ServerboundCommandSuggestionPacket;
import net.minecraft.network.protocol.game.ServerboundConfigurationAcknowledgedPacket;
import net.minecraft.network.protocol.game.ServerboundContainerButtonClickPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket;
import net.minecraft.network.protocol.game.ServerboundContainerSlotStateChangedPacket;
import net.minecraft.network.protocol.game.ServerboundDebugSubscriptionRequestPacket;
import net.minecraft.network.protocol.game.ServerboundEditBookPacket;
import net.minecraft.network.protocol.game.ServerboundEntityTagQueryPacket;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.network.protocol.game.ServerboundJigsawGeneratePacket;
import net.minecraft.network.protocol.game.ServerboundLockDifficultyPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundMoveVehiclePacket;
import net.minecraft.network.protocol.game.ServerboundPaddleBoatPacket;
import net.minecraft.network.protocol.game.ServerboundPickItemFromBlockPacket;
import net.minecraft.network.protocol.game.ServerboundPickItemFromEntityPacket;
import net.minecraft.network.protocol.game.ServerboundPlaceRecipePacket;
import net.minecraft.network.protocol.game.ServerboundPlayerAbilitiesPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerInputPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket;
import net.minecraft.network.protocol.game.ServerboundRecipeBookChangeSettingsPacket;
import net.minecraft.network.protocol.game.ServerboundRecipeBookSeenRecipePacket;
import net.minecraft.network.protocol.game.ServerboundRenameItemPacket;
import net.minecraft.network.protocol.game.ServerboundSeenAdvancementsPacket;
import net.minecraft.network.protocol.game.ServerboundSelectBundleItemPacket;
import net.minecraft.network.protocol.game.ServerboundSelectTradePacket;
import net.minecraft.network.protocol.game.ServerboundSetBeaconPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.network.protocol.game.ServerboundSetCommandBlockPacket;
import net.minecraft.network.protocol.game.ServerboundSetCommandMinecartPacket;
import net.minecraft.network.protocol.game.ServerboundSetCreativeModeSlotPacket;
import net.minecraft.network.protocol.game.ServerboundSetGameRulePacket;
import net.minecraft.network.protocol.game.ServerboundSetJigsawBlockPacket;
import net.minecraft.network.protocol.game.ServerboundSetStructureBlockPacket;
import net.minecraft.network.protocol.game.ServerboundSetTestBlockPacket;
import net.minecraft.network.protocol.game.ServerboundSignUpdatePacket;
import net.minecraft.network.protocol.game.ServerboundSpectatorActionPacket;
import net.minecraft.network.protocol.game.ServerboundSwingPacket;
import net.minecraft.network.protocol.game.ServerboundTeleportToEntityPacket;
import net.minecraft.network.protocol.game.ServerboundTestInstanceBlockActionPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.network.protocol.game.GameProtocols.Context;
import net.minecraft.network.protocol.game.ServerboundSeenAdvancementsPacket.Action;
import net.minecraft.network.protocol.ping.ClientboundPongResponsePacket;
import net.minecraft.network.protocol.ping.ServerboundPingRequestPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.commands.FetchProfileCommand;
import net.minecraft.server.commands.GameModeCommand;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.server.players.PlayerList;
import net.minecraft.util.FutureChain;
import net.minecraft.util.Mth;
import net.minecraft.util.SignatureValidator;
import net.minecraft.util.StringUtil;
import net.minecraft.util.TickThrottler;
import net.minecraft.util.Util;
import net.minecraft.util.ProblemReporter.ScopedCollector;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResult.Success;
import net.minecraft.world.InteractionResult.SwingSource;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Avatar;
import net.minecraft.world.entity.Bucketable;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.HasCustomInventoryScreen;
import net.minecraft.world.entity.Leashable;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.PlayerRideableJumping;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.entity.Entity.RemovalReason;
import net.minecraft.world.entity.animal.allay.Allay;
import net.minecraft.world.entity.animal.equine.AbstractHorse;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.ChatVisiblity;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.PlayerModelPart;
import net.minecraft.world.entity.player.ProfilePublicKey;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.inventory.BeaconMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.CrafterMenu;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.world.inventory.RecipeBookMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.inventory.RecipeBookMenu.PostPlaceAction;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.BundleItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.BundleContents;
import net.minecraft.world.item.component.PiercingWeapon;
import net.minecraft.world.item.component.WritableBookContent;
import net.minecraft.world.item.component.WrittenBookContent;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager.ServerDisplayInfo;
import net.minecraft.world.level.BaseCommandBlock;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.ClipContext.Block;
import net.minecraft.world.level.ClipContext.Fluid;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CommandBlock;
import net.minecraft.world.level.block.SignBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.CommandBlockEntity;
import net.minecraft.world.level.block.entity.CrafterBlockEntity;
import net.minecraft.world.level.block.entity.JigsawBlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.StructureBlockEntity;
import net.minecraft.world.level.block.entity.TestBlockEntity;
import net.minecraft.world.level.block.entity.TestInstanceBlockEntity;
import net.minecraft.world.level.block.entity.CommandBlockEntity.Mode;
import net.minecraft.world.level.block.entity.StructureBlockEntity.UpdateType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gamerules.GameRule;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.HitResult.Type;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Keyed;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.block.sign.Side;
import org.bukkit.craftbukkit.CraftEquipmentSlot;
import org.bukkit.craftbukkit.CraftInput;
import org.bukkit.craftbukkit.block.CraftBlock;
import org.bukkit.craftbukkit.entity.CraftEntity;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.craftbukkit.event.CraftEventFactory;
import org.bukkit.craftbukkit.inventory.CraftItemStack;
import org.bukkit.craftbukkit.inventory.CraftItemType;
import org.bukkit.craftbukkit.util.CraftLocation;
import org.bukkit.craftbukkit.util.CraftNamespacedKey;
import org.bukkit.craftbukkit.util.CraftVector;
import org.bukkit.craftbukkit.util.LazyPlayerSet;
import org.bukkit.entity.Player;
import org.bukkit.event.Event.Result;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCreativeEvent;
import org.bukkit.event.inventory.SmithItemEvent;
import org.bukkit.event.inventory.TradeSelectEvent;
import org.bukkit.event.inventory.InventoryCloseEvent.Reason;
import org.bukkit.event.inventory.InventoryType.SlotType;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerInputEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerToggleFlightEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.event.player.PlayerToggleSprintEvent;
import org.bukkit.event.player.PlayerKickEvent.Cause;
import org.bukkit.event.player.PlayerResourcePackStatusEvent.Status;
import org.bukkit.event.player.PlayerRespawnEvent.RespawnReason;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;
import org.bukkit.inventory.CartographyInventory;
import org.bukkit.inventory.CraftingInventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.SmithingInventory;
import org.bukkit.util.RayTraceResult;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.spigotmc.AsyncCatcher;
import org.spigotmc.SpigotConfig;

public class ServerGamePacketListenerImpl
   extends ServerCommonPacketListenerImpl
   implements ServerGamePacketListener,
   ServerPlayerConnection,
   TickablePacketListener,
   Context {
   private static final Logger LOGGER = LogUtils.getLogger();
   private static final int NO_BLOCK_UPDATES_TO_ACK = -1;
   private static final int TRACKED_MESSAGE_DISCONNECT_THRESHOLD = 4096;
   private static final int MAXIMUM_FLYING_TICKS = 80;
   private static final int ATTACK_INDICATOR_TOLERANCE_TICKS = 5;
   public static final int CLIENT_LOADED_TIMEOUT_TIME = 60;
   private static final Component CHAT_VALIDATION_FAILED = Component.translatable("multiplayer.disconnect.chat_validation_failed");
   private static final Component INVALID_COMMAND_SIGNATURE = Component.translatable("chat.disabled.invalid_command_signature").withStyle(ChatFormatting.RED);
   private static final int MAX_COMMAND_SUGGESTIONS = 1000;
   public ServerPlayer player;
   public final PlayerChunkSender chunkSender;
   private int tickCount;
   private int ackBlockChangesUpTo = -1;
   private final TickThrottler dropSpamThrottler = new TickThrottler(20, 1480);
   private final TickThrottler chatSpamThrottler;
   private final TickThrottler commandSpamThrottler;
   private final TickThrottler tabSpamThrottler = new TickThrottler(
      GlobalConfiguration.get().spamLimiter.tabSpamIncrement, GlobalConfiguration.get().spamLimiter.tabSpamLimit
   );
   private final TickThrottler recipeSpamPackets = new TickThrottler(
      GlobalConfiguration.get().spamLimiter.recipeSpamIncrement, GlobalConfiguration.get().spamLimiter.recipeSpamLimit
   );
   private double firstGoodX;
   private double firstGoodY;
   private double firstGoodZ;
   private double lastGoodX;
   private double lastGoodY;
   private double lastGoodZ;
   private @Nullable Entity lastVehicle;
   private double vehicleFirstGoodX;
   private double vehicleFirstGoodY;
   private double vehicleFirstGoodZ;
   private double vehicleLastGoodX;
   private double vehicleLastGoodY;
   private double vehicleLastGoodZ;
   private @Nullable Vec3 awaitingPositionFromClient;
   private int awaitingTeleport;
   private int awaitingTeleportTime;
   private boolean clientIsFloating;
   private int aboveGroundTickCount;
   private boolean clientVehicleIsFloating;
   private int aboveGroundVehicleTickCount;
   private int receivedMovePacketCount;
   private int knownMovePacketCount;
   private boolean receivedMovementThisTick;
   private int lastTick = MinecraftServer.currentTick;
   private int allowedPlayerTicks = 1;
   private int lastDropTick = MinecraftServer.currentTick;
   private int lastBookTick = MinecraftServer.currentTick;
   private int dropCount = 0;
   private boolean hasMoved = false;
   private double lastPosX = Double.MAX_VALUE;
   private double lastPosY = Double.MAX_VALUE;
   private double lastPosZ = Double.MAX_VALUE;
   private float lastYRot = Float.MAX_VALUE;
   private float lastXRot = Float.MAX_VALUE;
   private boolean justTeleported = false;
   private @Nullable RemoteChatSession chatSession;
   private boolean hasLoggedExpiry = false;
   private Decoder signedMessageDecoder;
   private final LastSeenMessagesValidator lastSeenMessages = new LastSeenMessagesValidator(20);
   private int nextChatIndex;
   private final MessageSignatureCache messageSignatureCache = MessageSignatureCache.createDefault();
   private final FutureChain chatMessageChain;
   private boolean waitingForSwitchToConfig;
   private boolean waitingForRespawn;
   private int clientLoadedTimeoutTimer;
   private static final int MAX_SIGN_LINE_LENGTH = Integer.getInteger("Paper.maxSignLength", 80);
   private final ClientTickEndEvent tickEndEvent;
   public final PaperPlayerGameConnection playerGameConnection;
   private static final ExecutorService TAB_COMPLETE_EXECUTOR = Executors.newFixedThreadPool(
      4,
      new ThreadFactoryBuilder()
         .setDaemon(true)
         .setNameFormat("Async Tab Complete Thread - #%d")
         .setUncaughtExceptionHandler(new DefaultUncaughtExceptionHandlerWithName(MinecraftServer.LOGGER))
         .build()
   );
   private int limitedPackets;
   private long lastLimitedPacket = -1L;

   public ServerGamePacketListenerImpl(MinecraftServer server, Connection connection, ServerPlayer player, CommonListenerCookie cookie) {
      super(server, connection, cookie);
      this.commandSpamThrottler = new TickThrottler(20, 20 * server.getCommandSpamThresholdSeconds());
      this.chatSpamThrottler = new TickThrottler(20, 20 * server.getChatSpamThresholdSeconds());
      this.restartClientLoadTimerAfterRespawn();
      this.chunkSender = new PlayerChunkSender(connection.isMemoryConnection());
      this.player = player;
      player.connection = this;
      player.getTextFilter().join();
      this.signedMessageDecoder = Decoder.unsigned(player.getUUID(), server::enforceSecureProfile);
      this.chatMessageChain = new FutureChain(server.chatExecutor);
      this.tickEndEvent = new ClientTickEndEvent(player.getBukkitEntity());
      this.playerGameConnection = new PaperPlayerGameConnection(this);
   }

   public PlayerCommonConnection getApiConnection() {
      return this.playerGameConnection;
   }

   public Audience getAudience() {
      return this.getCraftPlayer();
   }

   public void tick() {
      if (this.ackBlockChangesUpTo > -1) {
         this.send(new ClientboundBlockChangedAckPacket(this.ackBlockChangesUpTo));
         this.ackBlockChangesUpTo = -1;
      }

      if (this.server.isPaused() || !this.tickPlayer()) {
         this.keepConnectionAlive();
         this.chatSpamThrottler.tick();
         this.commandSpamThrottler.tick();
         this.dropSpamThrottler.tick();
         this.tabSpamThrottler.tick();
         this.recipeSpamPackets.tick();
         if (this.player.getLastActionTime() > 0L
            && this.server.playerIdleTimeout() > 0
            && Util.getMillis() - this.player.getLastActionTime() > TimeUnit.MINUTES.toMillis(this.server.playerIdleTimeout())
            && !this.player.wonGame) {
            this.disconnect(Component.translatable("multiplayer.disconnect.idling"), Cause.IDLING);
         }
      }
   }

   private boolean tickPlayer() {
      this.resetPosition();
      this.player.xo = this.player.getX();
      this.player.yo = this.player.getY();
      this.player.zo = this.player.getZ();
      this.player.doTick();
      this.player.absSnapTo(this.firstGoodX, this.firstGoodY, this.firstGoodZ, this.player.getYRot(), this.player.getXRot());
      this.tickCount++;
      this.knownMovePacketCount = this.receivedMovePacketCount;
      if (this.clientIsFloating && !this.player.isSleeping() && !this.player.isPassenger() && !this.player.isDeadOrDying()) {
         if (++this.aboveGroundTickCount > this.getMaximumFlyingTicks(this.player)) {
            this.disconnect(GlobalConfiguration.get().messages.kick.flyingPlayer, Cause.FLYING_PLAYER);
            return true;
         }
      } else {
         this.clientIsFloating = false;
         this.aboveGroundTickCount = 0;
      }

      this.lastVehicle = this.player.getRootVehicle();
      if (this.lastVehicle != this.player && this.lastVehicle.getControllingPassenger() == this.player) {
         this.vehicleFirstGoodX = this.lastVehicle.getX();
         this.vehicleFirstGoodY = this.lastVehicle.getY();
         this.vehicleFirstGoodZ = this.lastVehicle.getZ();
         this.vehicleLastGoodX = this.lastVehicle.getX();
         this.vehicleLastGoodY = this.lastVehicle.getY();
         this.vehicleLastGoodZ = this.lastVehicle.getZ();
         if (this.clientVehicleIsFloating && this.lastVehicle.getControllingPassenger() == this.player) {
            if (++this.aboveGroundVehicleTickCount > this.getMaximumFlyingTicks(this.lastVehicle)) {
               this.disconnect(GlobalConfiguration.get().messages.kick.flyingVehicle, Cause.FLYING_VEHICLE);
               return true;
            }
         } else {
            this.clientVehicleIsFloating = false;
            this.aboveGroundVehicleTickCount = 0;
         }
      } else {
         this.lastVehicle = null;
         this.clientVehicleIsFloating = false;
         this.aboveGroundVehicleTickCount = 0;
      }

      if (!this.hasLoggedExpiry && this.chatSession != null && this.chatSession.profilePublicKey().data().hasExpired()) {
         LOGGER.info("Player profile key for {} has expired!", this.player.getPlainTextName());
         this.hasLoggedExpiry = true;
      }

      return false;
   }

   private int getMaximumFlyingTicks(Entity entity) {
      double gravity = entity.getGravity();
      if (gravity < 1.0E-5F) {
         return Integer.MAX_VALUE;
      }

      double gravityModifier = 0.08 / gravity;
      return Mth.ceil(80.0 * Math.max(gravityModifier, 1.0));
   }

   public void resetFlyingTicks() {
      this.aboveGroundTickCount = 0;
      this.aboveGroundVehicleTickCount = 0;
   }

   public void resetPosition() {
      this.firstGoodX = this.player.getX();
      this.firstGoodY = this.player.getY();
      this.firstGoodZ = this.player.getZ();
      this.lastGoodX = this.player.getX();
      this.lastGoodY = this.player.getY();
      this.lastGoodZ = this.player.getZ();
   }

   public boolean isAcceptingMessages() {
      return this.connection.isConnected() && !this.waitingForSwitchToConfig;
   }

   public boolean shouldHandleMessage(Packet<?> packet) {
      return super.shouldHandleMessage(packet)
         || this.waitingForSwitchToConfig && this.connection.isConnected() && packet instanceof ServerboundConfigurationAcknowledgedPacket;
   }

   protected GameProfile playerProfile() {
      return this.player.getGameProfile();
   }

   private <T, R> CompletableFuture<R> filterTextPacket(T message, BiFunction<TextFilter, T, CompletableFuture<R>> action) {
      return action.apply(this.player.getTextFilter(), message).thenApply(result -> {
         if (!this.isAcceptingMessages()) {
            LOGGER.debug("Ignoring packet due to disconnection");
            throw new CancellationException("disconnected");
         } else {
            return (R)result;
         }
      });
   }

   private CompletableFuture<FilteredText> filterTextPacket(String message) {
      return this.filterTextPacket(message, TextFilter::processStreamMessage);
   }

   private CompletableFuture<List<FilteredText>> filterTextPacket(List<String> message) {
      return this.filterTextPacket(message, TextFilter::processMessageBundle);
   }

   public void handlePlayerInput(ServerboundPlayerInputPacket packet) {
      PacketUtils.ensureRunningOnSameThread(packet, this, this.player.level());
      if (!packet.input().equals(this.player.getLastClientInput())) {
         PlayerInputEvent event = new PlayerInputEvent(this.player.getBukkitEntity(), new CraftInput(packet.input()));
         this.cserver.getPluginManager().callEvent(event);
      }

      Input lastInput = this.player.getLastClientInput();
      if (lastInput.shift() != packet.input().shift()) {
         PlayerToggleSneakEvent event = new PlayerToggleSneakEvent(this.getCraftPlayer(), packet.input().shift());
         this.cserver.getPluginManager().callEvent(event);
         if (!event.isCancelled() && this.hasClientLoaded()) {
            this.player.setShiftKeyDown(packet.input().shift());
         }
      }

      this.player.setLastClientInput(packet.input());
      if (this.hasClientLoaded()) {
         this.player.resetLastActionTime();
      }

      if (packet.input().shift() && this.player.level().paperConfig().entities.behavior.parrotsAreUnaffectedByPlayerMovement) {
         this.player.removeEntitiesOnShoulder();
      }
   }

   private static boolean containsInvalidValues(double x, double y, double z, float yRot, float xRot) {
      return Double.isNaN(x) || Double.isNaN(y) || Double.isNaN(z) || !Floats.isFinite(xRot) || !Floats.isFinite(yRot);
   }

   private static double clampHorizontal(double value) {
      return Mth.clamp(value, -3.0E7, 3.0E7);
   }

   private static double clampVertical(double value) {
      return Mth.clamp(value, -2.0E7, 2.0E7);
   }

   public void handleMoveVehicle(ServerboundMoveVehiclePacket packet) {
      PacketUtils.ensureRunningOnSameThread(packet, this, this.player.level());
      if (containsInvalidValues(packet.position().x(), packet.position().y(), packet.position().z(), packet.yRot(), packet.xRot())) {
         this.disconnect(Component.translatable("multiplayer.disconnect.invalid_vehicle_movement"), Cause.INVALID_VEHICLE_MOVEMENT);
      } else if (!this.updateAwaitingTeleport() && this.hasClientLoaded()) {
         Entity vehicle = this.player.getRootVehicle();
         if (this.awaitingPositionFromClient != null || this.player.isImmobile() || vehicle.isRemoved()) {
            return;
         }

         if (vehicle != this.player && vehicle.getControllingPassenger() == this.player && vehicle == this.lastVehicle) {
            ServerLevel level = this.player.level();
            float startYRot = this.player.getYRot();
            float startXRot = this.player.getXRot();
            double startX = this.player.getX();
            double startY = this.player.getY();
            double startZ = this.player.getZ();
            double oldX = vehicle.getX();
            double oldY = vehicle.getY();
            double oldZ = vehicle.getZ();
            double targetX = clampHorizontal(packet.position().x());
            double targetY = clampVertical(packet.position().y());
            double targetZ = clampHorizontal(packet.position().z());
            float targetYRot = Mth.wrapDegrees(packet.yRot());
            float targetXRot = Mth.wrapDegrees(packet.xRot());
            double xDist = targetX - this.vehicleFirstGoodX;
            double yDist = targetY - this.vehicleFirstGoodY;
            double zDist = targetZ - this.vehicleFirstGoodZ;
            double expectedDist = vehicle.getDeltaMovement().lengthSqr();
            double movedDist = xDist * xDist + yDist * yDist + zDist * zDist;
            double currDeltaX = targetX - oldX;
            double currDeltaY = targetY - oldY;
            double currDeltaZ = targetZ - oldZ;
            movedDist = Math.max(movedDist, currDeltaX * currDeltaX + currDeltaY * currDeltaY + currDeltaZ * currDeltaZ - 1.0);
            double otherFieldX = targetX - this.vehicleLastGoodX;
            double otherFieldY = targetY - this.vehicleLastGoodY;
            double otherFieldZ = targetZ - this.vehicleLastGoodZ;
            movedDist = Math.max(movedDist, otherFieldX * otherFieldX + otherFieldY * otherFieldY + otherFieldZ * otherFieldZ - 1.0);
            this.allowedPlayerTicks = (int)(this.allowedPlayerTicks + (System.currentTimeMillis() / 50L - this.lastTick));
            this.allowedPlayerTicks = Math.max(this.allowedPlayerTicks, 1);
            this.lastTick = (int)(System.currentTimeMillis() / 50L);
            this.receivedMovePacketCount++;
            int i = this.receivedMovePacketCount - this.knownMovePacketCount;
            if (i > Math.max(this.allowedPlayerTicks, 5)) {
               LOGGER.debug(this.player.getScoreboardName() + " is sending move packets too frequently (" + i + " packets since last tick)");
               i = 1;
            }

            if (movedDist > 0.0) {
               this.allowedPlayerTicks--;
            } else {
               this.allowedPlayerTicks = 20;
            }

            double speed;
            if (this.player.getAbilities().flying) {
               speed = this.player.getAbilities().getFlyingSpeed() * 20.0F;
            } else {
               speed = this.player.getAbilities().getWalkingSpeed() * 10.0F;
            }

            speed *= 2.0;
            if (this.player.level().paperConfig().chunks.preventMovingIntoUnloadedChunks
               && (
                  !level.areChunksLoadedForMove(
                        this.player.getBoundingBox().expandTowards(new Vec3(targetX, targetY, targetZ).subtract(this.player.position()))
                     )
                     || !level.areChunksLoadedForMove(vehicle.getBoundingBox().expandTowards(new Vec3(targetX, targetY, targetZ).subtract(vehicle.position())))
               )) {
               this.connection.send(ClientboundMoveVehiclePacket.fromEntity(vehicle));
               return;
            }

            if (movedDist - expectedDist > Math.max(100.0, Mth.square(SpigotConfig.movedTooQuicklyMultiplier * i * speed)) && !this.isSingleplayerOwner()) {
               LOGGER.warn(
                  "{} (vehicle of {}) moved too quickly! {},{},{}",
                  new Object[]{vehicle.getPlainTextName(), this.player.getPlainTextName(), xDist, yDist, zDist}
               );
               this.send(ClientboundMoveVehiclePacket.fromEntity(vehicle));
               return;
            }

            AABB oldAABB = vehicle.getBoundingBox();
            xDist = targetX - this.vehicleLastGoodX;
            yDist = targetY - this.vehicleLastGoodY;
            zDist = targetZ - this.vehicleLastGoodZ;
            boolean vehicleRestsOnSomething = vehicle.verticalCollisionBelow;
            if (vehicle instanceof LivingEntity livingVehicle && livingVehicle.onClimbable()) {
               livingVehicle.resetFallDistance();
            }

            vehicle.move(MoverType.PLAYER, new Vec3(xDist, yDist, zDist));
            boolean didCollide = targetX != vehicle.getX() || targetY != vehicle.getY() || targetZ != vehicle.getZ();
            double oyDist = yDist;
            xDist = targetX - vehicle.getX();
            yDist = targetY - vehicle.getY();
            if (yDist > -0.5 || yDist < 0.5) {
               yDist = 0.0;
            }

            zDist = targetZ - vehicle.getZ();
            movedDist = xDist * xDist + yDist * yDist + zDist * zDist;
            boolean fail = false;
            if (movedDist > SpigotConfig.movedWronglyThreshold) {
               fail = true;
               LOGGER.warn(
                  "{} (vehicle of {}) moved wrongly! {}", new Object[]{vehicle.getPlainTextName(), this.player.getPlainTextName(), Math.sqrt(movedDist)}
               );
            }

            boolean teleportBack = fail;
            if (!teleportBack) {
               AABB newBox = vehicle.getBoundingBox();
               if (didCollide || !oldAABB.equals(newBox)) {
                  teleportBack = this.hasNewCollision(level, vehicle, oldAABB, newBox);
               }
            }

            if (teleportBack) {
               vehicle.absSnapTo(oldX, oldY, oldZ, targetYRot, targetXRot);
               this.send(ClientboundMoveVehiclePacket.fromEntity(vehicle));
               vehicle.removeLatestMovementRecording();
               return;
            }

            vehicle.absSnapTo(targetX, targetY, targetZ, targetYRot, targetXRot);
            this.player.absSnapTo(targetX, targetY, targetZ, this.player.getYRot(), this.player.getXRot());
            Player player = this.getCraftPlayer();
            if (!this.hasMoved) {
               this.lastPosX = startX;
               this.lastPosY = startY;
               this.lastPosZ = startZ;
               this.lastYRot = startYRot;
               this.lastXRot = startXRot;
               this.hasMoved = true;
            }

            Location from = new Location(player.getWorld(), this.lastPosX, this.lastPosY, this.lastPosZ, this.lastYRot, this.lastXRot);
            Location to = new Location(player.getWorld(), targetX, targetY, targetZ, targetYRot, targetXRot);
            double delta = Mth.square(this.lastPosX - to.getX()) + Mth.square(this.lastPosY - to.getY()) + Mth.square(this.lastPosZ - to.getZ());
            float deltaAngle = Math.abs(this.lastYRot - to.getYaw()) + Math.abs(this.lastXRot - to.getPitch());
            if ((delta > 0.00390625 || deltaAngle > 10.0F) && !this.player.isImmobile()) {
               this.lastPosX = to.getX();
               this.lastPosY = to.getY();
               this.lastPosZ = to.getZ();
               this.lastYRot = to.getYaw();
               this.lastXRot = to.getPitch();
               Location oldTo = to.clone();
               PlayerMoveEvent event = new PlayerMoveEvent(player, from, to);
               this.cserver.getPluginManager().callEvent(event);
               if (event.isCancelled()) {
                  this.internalTeleport(from);
                  return;
               }

               if (!oldTo.equals(event.getTo()) && !event.isCancelled()) {
                  this.player.getBukkitEntity().teleport(event.getTo(), TeleportCause.PLUGIN);
                  return;
               }

               if (!from.equals(this.getCraftPlayer().getLocation()) && this.justTeleported) {
                  this.justTeleported = false;
                  return;
               }
            }

            this.player.level().getChunkSource().move(this.player);
            Vec3 clientDeltaMovement = new Vec3(vehicle.getX() - oldX, vehicle.getY() - oldY, vehicle.getZ() - oldZ);
            this.handlePlayerKnownMovement(clientDeltaMovement);
            vehicle.setOnGroundWithMovement(packet.onGround(), clientDeltaMovement);
            vehicle.doCheckFallDamage(clientDeltaMovement.x, clientDeltaMovement.y, clientDeltaMovement.z, packet.onGround());
            this.player.checkMovementStatistics(clientDeltaMovement.x, clientDeltaMovement.y, clientDeltaMovement.z);
            this.clientVehicleIsFloating = oyDist >= -0.03125
               && !vehicleRestsOnSomething
               && !this.server.allowFlight()
               && !vehicle.isFlyingVehicle()
               && !vehicle.isNoGravity()
               && this.noBlocksAround(vehicle);
            this.vehicleLastGoodX = vehicle.getX();
            this.vehicleLastGoodY = vehicle.getY();
            this.vehicleLastGoodZ = vehicle.getZ();
         }
      }
   }

   private boolean noBlocksAround(Entity entity) {
      AABB box = entity.getBoundingBox().inflate(0.0625).expandTowards(0.0, -0.55, 0.0);
      int minX = Mth.floor(box.minX);
      int minY = Mth.floor(box.minY);
      int minZ = Mth.floor(box.minZ);
      int maxX = Mth.floor(box.maxX);
      int maxY = Mth.floor(box.maxY);
      int maxZ = Mth.floor(box.maxZ);
      Level level = entity.level();
      MutableBlockPos pos = new MutableBlockPos();

      for (int y = minY; y <= maxY; y++) {
         for (int z = minZ; z <= maxZ; z++) {
            for (int x = minX; x <= maxX; x++) {
               pos.set(x, y, z);
               BlockState blockState = level.getBlockStateIfLoaded(pos);
               if (blockState != null && !blockState.isAir()) {
                  return false;
               }
            }
         }
      }

      return true;
   }

   public void handleAcceptTeleportPacket(ServerboundAcceptTeleportationPacket packet) {
      PacketUtils.ensureRunningOnSameThread(packet, this, this.player.level());
      if (packet.getId() == this.awaitingTeleport) {
         if (this.awaitingPositionFromClient == null) {
            this.disconnect(Component.translatable("multiplayer.disconnect.invalid_player_movement"), Cause.INVALID_PLAYER_MOVEMENT);
            return;
         }

         this.player
            .absSnapTo(
               this.awaitingPositionFromClient.x,
               this.awaitingPositionFromClient.y,
               this.awaitingPositionFromClient.z,
               this.player.getYRot(),
               this.player.getXRot()
            );
         this.lastGoodX = this.awaitingPositionFromClient.x;
         this.lastGoodY = this.awaitingPositionFromClient.y;
         this.lastGoodZ = this.awaitingPositionFromClient.z;
         this.player.hasChangedDimension();
         this.awaitingPositionFromClient = null;
         this.player.level().getChunkSource().move(this.player);
      }
   }

   public void handleAcceptPlayerLoad(ServerboundPlayerLoadedPacket packet) {
      PacketUtils.ensureRunningOnSameThread(packet, this, this.player.level());
      this.markClientLoaded(false);
   }

   public void handleRecipeBookSeenRecipePacket(ServerboundRecipeBookSeenRecipePacket packet) {
      PacketUtils.ensureRunningOnSameThread(packet, this, this.player.level());
      ServerDisplayInfo entry = this.server.getRecipeManager().getRecipeFromDisplay(packet.recipe());
      if (entry != null) {
         this.player.getRecipeBook().removeHighlight(entry.parent().id());
      }
   }

   public void handleBundleItemSelectedPacket(ServerboundSelectBundleItemPacket packet) {
      PacketUtils.ensureRunningOnSameThread(packet, this, this.player.level());
      this.player.containerMenu.setSelectedBundleItemIndex(packet.slotId(), packet.selectedItemIndex());
   }

   public void handleRecipeBookChangeSettingsPacket(ServerboundRecipeBookChangeSettingsPacket packet) {
      PacketUtils.ensureRunningOnSameThread(packet, this, this.player.level());
      CraftEventFactory.callRecipeBookSettingsEvent(this.player, packet.getBookType(), packet.isOpen(), packet.isFiltering());
      this.player.getRecipeBook().setBookSetting(packet.getBookType(), packet.isOpen(), packet.isFiltering());
   }

   public void handleSeenAdvancements(ServerboundSeenAdvancementsPacket packet) {
      PacketUtils.ensureRunningOnSameThread(packet, this, this.player.level());
      if (packet.getAction() == Action.OPENED_TAB) {
         Identifier id = Objects.requireNonNull(packet.getTab());
         AdvancementHolder advancement = this.server.getAdvancements().get(id);
         if (advancement != null) {
            this.player.getAdvancements().setSelectedTab(advancement);
         }
      }
   }

   public void handleCustomCommandSuggestions(ServerboundCommandSuggestionPacket packet) {
      if (!this.tabSpamThrottler.isIncrementAndUnderThreshold()
         && !this.server.getPlayerList().isOp(this.player.nameAndId())
         && !this.server.isSingleplayerOwner(this.player.nameAndId())) {
         this.disconnectAsync(Component.translatable("disconnect.spam"), Cause.SPAM);
      } else if (SpigotConfig.tabComplete >= 0) {
         int index;
         if (packet.getCommand().length() <= 64 || (index = packet.getCommand().indexOf(32)) != -1 && index < 64) {
            TAB_COMPLETE_EXECUTOR.execute(() -> this.handleCustomCommandSuggestions0(packet));
         } else {
            this.disconnectAsync(Component.translatable("disconnect.spam"), Cause.SPAM);
         }
      }
   }

   private void handleCustomCommandSuggestions0(ServerboundCommandSuggestionPacket packet) {
      StringReader command = new StringReader(packet.getCommand());
      if (command.canRead() && command.peek() == '/') {
         command.skip();
      }

      AsyncTabCompleteEvent event = new AsyncTabCompleteEvent(this.getCraftPlayer(), packet.getCommand(), true, null);
      event.callEvent();
      List<Completion> completions = (List<Completion>)(event.isCancelled() ? ImmutableList.of() : event.completions());
      if (!event.isHandled()) {
         if (event.isCancelled()) {
            return;
         }

         this.server.scheduleOnMain(() -> this.sendServerSuggestions(packet, command));
      } else if (!completions.isEmpty()) {
         SuggestionsBuilder builder0 = new SuggestionsBuilder(packet.getCommand(), command.getTotalLength());
         SuggestionsBuilder builder = builder0.createOffset(builder0.getInput().lastIndexOf(32) + 1);

         for (Completion completion : completions) {
            Integer intSuggestion = Ints.tryParse(completion.suggestion());
            if (intSuggestion != null) {
               builder.suggest(intSuggestion, PaperAdventure.asVanilla(completion.tooltip()));
            } else {
               builder.suggest(completion.suggestion(), PaperAdventure.asVanilla(completion.tooltip()));
            }
         }

         Suggestions suggestions = (Suggestions)builder.buildFuture().join();
         AsyncPlayerSendSuggestionsEvent suggestEvent = new AsyncPlayerSendSuggestionsEvent(this.getCraftPlayer(), suggestions, packet.getCommand());
         suggestEvent.setCancelled(suggestions.isEmpty());
         if (suggestEvent.callEvent()) {
            this.connection.send(new ClientboundCommandSuggestionsPacket(packet.getId(), limitTo(suggestEvent.getSuggestions(), 1000)));
         }
      }
   }

   private static Suggestions limitTo(Suggestions suggestions, int size) {
      return suggestions.getList().size() <= size ? suggestions : new Suggestions(suggestions.getRange(), suggestions.getList().subList(0, size));
   }

   private void sendServerSuggestions(ServerboundCommandSuggestionPacket packet, StringReader command) {
      ParseResults<CommandSourceStack> parse = this.server.getCommands().getDispatcher().parse(command, this.player.createCommandSourceStack());
      if (!parse.getExceptions().isEmpty() && parse.getExceptions().values().stream().anyMatch(e -> e instanceof TagParseCommandSyntaxException)) {
         this.disconnect(Component.translatable("disconnect.spam"), Cause.SPAM);
      } else {
         this.server.getCommands().getDispatcher().getCompletionSuggestions(parse).thenAccept(results -> {
            if (!SpigotConfig.sendNamespaced && results.getRange().getStart() <= 1) {
               results.getList().removeIf(suggestion -> suggestion.getText().contains(":"));
            }

            AsyncPlayerSendSuggestionsEvent suggestEvent = new AsyncPlayerSendSuggestionsEvent(this.getCraftPlayer(), results, packet.getCommand());
            suggestEvent.setCancelled(results.isEmpty());
            if (suggestEvent.callEvent()) {
               this.send(new ClientboundCommandSuggestionsPacket(packet.getId(), limitTo(suggestEvent.getSuggestions(), 1000)));
            }
         });
      }
   }

   public void handleSetCommandBlock(ServerboundSetCommandBlockPacket packet) {
      PacketUtils.ensureRunningOnSameThread(packet, this, this.player.level());
      if (this.player.canUseGameMasterBlocks() || this.player.isCreative() && this.player.getBukkitEntity().hasPermission("minecraft.commandblock")) {
         BaseCommandBlock commandBlock = null;
         CommandBlockEntity autoCommandBlock = null;
         BlockPos blockPos = packet.getPos();
         BlockEntity blockEntity = this.player.level().getBlockEntity(blockPos);
         if (blockEntity instanceof CommandBlockEntity commandBlockEntity) {
            autoCommandBlock = commandBlockEntity;
            commandBlock = autoCommandBlock.getCommandBlock();
         }

         String command = packet.getCommand();
         boolean trackOutput = packet.isTrackOutput();
         if (commandBlock != null) {
            Mode oldMode = autoCommandBlock.getMode();
            BlockState currentBlockState = this.player.level().getBlockState(blockPos);
            Direction direction = (Direction)currentBlockState.getValue(CommandBlock.FACING);

            BlockState baseBlockState = switch (packet.getMode()) {
               case SEQUENCE -> Blocks.CHAIN_COMMAND_BLOCK.defaultBlockState();
               case AUTO -> Blocks.REPEATING_COMMAND_BLOCK.defaultBlockState();
               default -> Blocks.COMMAND_BLOCK.defaultBlockState();
            };
            BlockState blockState = (BlockState)((BlockState)baseBlockState.setValue(CommandBlock.FACING, direction))
               .setValue(CommandBlock.CONDITIONAL, packet.isConditional());
            if (blockState != currentBlockState) {
               this.player.level().setBlock(blockPos, blockState, 2);
               blockEntity.setBlockState(blockState);
               this.player.level().getChunkAt(blockPos).setBlockEntity(blockEntity);
            }

            commandBlock.setCommand(command);
            commandBlock.setTrackOutput(trackOutput);
            if (!trackOutput) {
               commandBlock.setLastOutput(null);
            }

            autoCommandBlock.setAutomatic(packet.isAutomatic());
            if (oldMode != packet.getMode()) {
               autoCommandBlock.onModeSwitch();
            }

            if (this.player.level().isCommandBlockEnabled()) {
               commandBlock.onUpdated(this.player.level());
            }

            if (!StringUtil.isNullOrEmpty(command)) {
               this.player
                  .sendSystemMessage(
                     Component.translatable(this.player.level().isCommandBlockEnabled() ? "advMode.setCommand.success" : "advMode.setCommand.disabled", command)
                  );
            }
         }
      } else {
         this.player.sendSystemMessage(Component.translatable("advMode.notAllowed"));
      }
   }

   public void handleSetCommandMinecart(ServerboundSetCommandMinecartPacket packet) {
      PacketUtils.ensureRunningOnSameThread(packet, this, this.player.level());
      if (this.player.canUseGameMasterBlocks() || this.player.isCreative() && this.player.getBukkitEntity().hasPermission("minecraft.commandblock")) {
         BaseCommandBlock commandBlock = packet.getCommandBlock(this.player.level());
         if (commandBlock != null) {
            String command = packet.getCommand();
            commandBlock.setCommand(command);
            commandBlock.setTrackOutput(packet.isTrackOutput());
            if (!packet.isTrackOutput()) {
               commandBlock.setLastOutput(null);
            }

            boolean commandBlockEnabled = this.player.level().isCommandBlockEnabled();
            if (commandBlockEnabled) {
               commandBlock.onUpdated(this.player.level());
            }

            if (!StringUtil.isNullOrEmpty(command)) {
               this.player
                  .sendSystemMessage(Component.translatable(commandBlockEnabled ? "advMode.setCommand.success" : "advMode.setCommand.disabled", command));
            }
         }
      } else {
         this.player.sendSystemMessage(Component.translatable("advMode.notAllowed"));
      }
   }

   public void handlePickItemFromBlock(ServerboundPickItemFromBlockPacket packet) {
      ServerLevel level = this.player.level();
      PacketUtils.ensureRunningOnSameThread(packet, this, level);
      BlockPos pos = packet.pos();
      if (this.player.isWithinBlockInteractionRange(pos, 1.0) && level.isLoaded(pos)) {
         BlockState blockState = level.getBlockState(pos);
         boolean includeData = this.player.hasInfiniteMaterials() && packet.includeData();
         ItemStack itemStack = blockState.getCloneItemStack(level, pos, includeData);
         if (!itemStack.isEmpty()) {
            if (includeData && this.player.getBukkitEntity().hasPermission("minecraft.nbt.copy")) {
               addBlockDataToItem(blockState, level, pos, itemStack);
            }

            this.tryPickItem(itemStack, pos, null, packet.includeData());
         }
      }
   }

   private static void addBlockDataToItem(BlockState blockState, ServerLevel level, BlockPos pos, ItemStack itemStack) {
      BlockEntity blockEntity = blockState.hasBlockEntity() ? level.getBlockEntity(pos) : null;
      if (blockEntity != null) {
         ScopedCollector reporter = new ScopedCollector(blockEntity.problemPath(), LOGGER);

         try {
            TagValueOutput output = TagValueOutput.createWithContext(reporter, level.registryAccess());
            blockEntity.saveCustomOnly(output);
            blockEntity.removeComponentsFromTag(output);
            BlockItem.setBlockEntityData(itemStack, blockEntity.getType(), output);
            itemStack.applyComponents(blockEntity.collectComponents());
         } catch (Throwable var9) {
            try {
               reporter.close();
            } catch (Throwable var8) {
               var9.addSuppressed(var8);
            }

            throw var9;
         }

         reporter.close();
      }
   }

   public void handlePickItemFromEntity(ServerboundPickItemFromEntityPacket packet) {
      ServerLevel level = this.player.level();
      PacketUtils.ensureRunningOnSameThread(packet, this, level);
      Entity entity = level.getEntityOrPart(packet.id());
      if (entity != null && this.player.isWithinEntityInteractionRange(entity, 3.0)) {
         ItemStack itemStack = entity.getPickResult();
         if (itemStack != null && !itemStack.isEmpty()) {
            this.tryPickItem(itemStack, null, entity, packet.includeData());
         }

         if (packet.includeData() && this.player.canUseGameMasterBlocks() && entity instanceof Avatar avatar) {
            FetchProfileCommand.printForAvatar(this.player.createCommandSourceStack(), avatar);
         }
      }
   }

   private void tryPickItem(ItemStack itemStack, @Nullable BlockPos blockPos, @Nullable Entity entity, boolean includeData) {
      if (itemStack.isItemEnabled(this.player.level().enabledFeatures())) {
         Inventory inventory = this.player.getInventory();
         int slotWithExistingItem = inventory.findSlotMatchingItem(itemStack);
         org.bukkit.inventory.ItemStack bukkitItem = itemStack.asBukkitCopy();
         int sourceSlot = slotWithExistingItem;
         int targetSlot = Inventory.isHotbarSlot(sourceSlot) ? sourceSlot : inventory.getSuitableHotbarSlot();
         Player bukkitPlayer = this.player.getBukkitEntity();
         PlayerPickItemEvent event = (PlayerPickItemEvent)(entity != null
            ? new PlayerPickEntityEvent(bukkitPlayer, entity.getBukkitEntity(), bukkitItem, includeData, targetSlot, sourceSlot)
            : new PlayerPickBlockEvent(bukkitPlayer, CraftBlock.at(this.player.level(), blockPos), bukkitItem, includeData, targetSlot, sourceSlot));
         if (!event.callEvent()) {
            return;
         }

         slotWithExistingItem = event.getSourceSlot();
         if (slotWithExistingItem != -1) {
            if (Inventory.isHotbarSlot(slotWithExistingItem) && Inventory.isHotbarSlot(event.getTargetSlot())) {
               inventory.setSelectedSlot(event.getTargetSlot());
            } else {
               inventory.pickSlot(slotWithExistingItem, event.getTargetSlot());
            }
         } else if (this.player.hasInfiniteMaterials()) {
            inventory.addAndPickItem(itemStack, event.getTargetSlot());
         }

         this.send(new ClientboundSetHeldSlotPacket(inventory.getSelectedSlot()));
         this.player.inventoryMenu.broadcastChanges();
         if (GlobalConfiguration.get().unsupportedSettings.updateEquipmentOnPlayerActions) {
            this.player.detectEquipmentUpdates();
         }
      }
   }

   public void handleRenameItem(ServerboundRenameItemPacket packet) {
      PacketUtils.ensureRunningOnSameThread(packet, this, this.player.level());
      if (this.player.containerMenu instanceof AnvilMenu menu) {
         if (!menu.stillValid(this.player)) {
            LOGGER.debug("Player {} interacted with invalid menu {}", this.player, menu);
            return;
         }

         menu.setItemName(packet.getName());
      }
   }

   public void handleSetBeaconPacket(ServerboundSetBeaconPacket packet) {
      PacketUtils.ensureRunningOnSameThread(packet, this, this.player.level());
      if (this.player.containerMenu instanceof BeaconMenu menu) {
         if (!this.player.containerMenu.stillValid(this.player)) {
            LOGGER.debug("Player {} interacted with invalid menu {}", this.player, this.player.containerMenu);
            return;
         }

         if (!menu.updateEffects(packet.primary(), packet.secondary())) {
            this.disconnect(Component.translatable("multiplayer.disconnect.generic"), Cause.ILLEGAL_ACTION);
            LOGGER.warn("Player {} tried to set invalid beacon effects", this.player);
         }
      }
   }

   public void handleSetGameRule(ServerboundSetGameRulePacket packet) {
      PacketUtils.ensureRunningOnSameThread(packet, this, this.player.level());
      if (!this.player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER)
         && !this.player.getBukkitEntity().hasPermission("minecraft.command.gamerule")) {
         LOGGER.warn("Player {} tried to set game rule values without required permissions", this.player.getGameProfile().name());
      } else {
         GameRules gameRules = this.player.level().getGameRules();

         for (net.minecraft.network.protocol.game.ServerboundSetGameRulePacket.Entry entry : packet.entries()) {
            GameRule<?> rule = (GameRule<?>)BuiltInRegistries.GAME_RULE.getValue(entry.gameRuleKey());
            if (rule != null) {
               this.setGameRuleValue(gameRules, rule, entry.value());
            } else {
               LOGGER.warn("Received request to set unknown game rule: {}", entry.gameRuleKey());
            }
         }
      }
   }

   private <T> void setGameRuleValue(GameRules gameRules, GameRule<T> rule, String value) {
      rule.deserialize(value).result().ifPresent(parsedValue -> {
         parsedValue = CraftEventFactory.handleGameRuleSet(rule, parsedValue, this.player.level(), this.player.getBukkitEntity()).value();
         this.broadcastGameRuleChangeToOperators(rule, parsedValue);
      });
   }

   private <T> void broadcastGameRuleChangeToOperators(GameRule<T> rule, T value) {
      Component message = Component.translatable("commands.gamerule.set", rule.id(), rule.serialize(value));
      PlayerList playerList = this.server.getPlayerList();
      playerList.getPlayers().stream().filter(op -> playerList.isOp(op.nameAndId())).forEach(op -> op.sendSystemMessage(message));
   }

   public void handleSetStructureBlock(ServerboundSetStructureBlockPacket packet) {
      PacketUtils.ensureRunningOnSameThread(packet, this, this.player.level());
      if (this.player.canUseGameMasterBlocks()) {
         BlockPos blockPos = packet.getPos();
         BlockState state = this.player.level().getBlockState(blockPos);
         if (this.player.level().getBlockEntity(blockPos) instanceof StructureBlockEntity structure) {
            structure.setMode(packet.getMode());
            structure.setStructureName(packet.getName());
            structure.setStructurePos(packet.getOffset());
            structure.setStructureSize(packet.getSize());
            structure.setMirror(packet.getMirror());
            structure.setRotation(packet.getRotation());
            structure.setMetaData(packet.getData());
            structure.setIgnoreEntities(packet.isIgnoreEntities());
            structure.setStrict(packet.isStrict());
            structure.setShowAir(packet.isShowAir());
            structure.setShowBoundingBox(packet.isShowBoundingBox());
            structure.setIntegrity(packet.getIntegrity());
            structure.setSeed(packet.getSeed());
            if (structure.hasStructureName()) {
               String actualStructureName = structure.getStructureName();
               if (packet.getUpdateType() == UpdateType.SAVE_AREA) {
                  if (structure.saveStructure()) {
                     this.player.sendSystemMessage(Component.translatable("structure_block.save_success", actualStructureName));
                  } else {
                     this.player.sendSystemMessage(Component.translatable("structure_block.save_failure", actualStructureName));
                  }
               } else if (packet.getUpdateType() == UpdateType.LOAD_AREA) {
                  if (!structure.isStructureLoadable()) {
                     this.player.sendSystemMessage(Component.translatable("structure_block.load_not_found", actualStructureName));
                  } else if (structure.placeStructureIfSameSize(this.player.level())) {
                     this.player.sendSystemMessage(Component.translatable("structure_block.load_success", actualStructureName));
                  } else {
                     this.player.sendSystemMessage(Component.translatable("structure_block.load_prepare", actualStructureName));
                  }
               } else if (packet.getUpdateType() == UpdateType.SCAN_AREA) {
                  if (structure.detectSize()) {
                     this.player.sendSystemMessage(Component.translatable("structure_block.size_success", actualStructureName));
                  } else {
                     this.player.sendSystemMessage(Component.translatable("structure_block.size_failure"));
                  }
               }
            } else {
               this.player.sendSystemMessage(Component.translatable("structure_block.invalid_structure_name", packet.getName()));
            }

            structure.setChanged();
            this.player.level().sendBlockUpdated(blockPos, state, state, 3);
         }
      }
   }

   public void handleSetTestBlock(ServerboundSetTestBlockPacket packet) {
      PacketUtils.ensureRunningOnSameThread(packet, this, this.player.level());
      if (this.player.canUseGameMasterBlocks()) {
         BlockPos blockPos = packet.position();
         BlockState initialState = this.player.level().getBlockState(blockPos);
         if (this.player.level().getBlockEntity(blockPos) instanceof TestBlockEntity testBlock) {
            testBlock.setMode(packet.mode());
            testBlock.setMessage(packet.message());
            testBlock.setChanged();
            this.player.level().sendBlockUpdated(blockPos, initialState, testBlock.getBlockState(), 3);
         }
      }
   }

   public void handleTestInstanceBlockAction(ServerboundTestInstanceBlockActionPacket packet) {
      PacketUtils.ensureRunningOnSameThread(packet, this, this.player.level());
      BlockPos pos = packet.pos();
      if (this.player.canUseGameMasterBlocks() && this.player.level().getBlockEntity(pos) instanceof TestInstanceBlockEntity blockEntity) {
         if (packet.action() != net.minecraft.network.protocol.game.ServerboundTestInstanceBlockActionPacket.Action.QUERY
            && packet.action() != net.minecraft.network.protocol.game.ServerboundTestInstanceBlockActionPacket.Action.INIT) {
            blockEntity.set(packet.data());
            if (packet.action() == net.minecraft.network.protocol.game.ServerboundTestInstanceBlockActionPacket.Action.RESET) {
               blockEntity.resetTest(this.player::sendSystemMessage);
            } else if (packet.action() == net.minecraft.network.protocol.game.ServerboundTestInstanceBlockActionPacket.Action.SAVE) {
               blockEntity.saveTest(this.player::sendSystemMessage);
            } else if (packet.action() == net.minecraft.network.protocol.game.ServerboundTestInstanceBlockActionPacket.Action.EXPORT) {
               blockEntity.exportTest(this.player::sendSystemMessage);
            } else if (packet.action() == net.minecraft.network.protocol.game.ServerboundTestInstanceBlockActionPacket.Action.RUN) {
               blockEntity.runTest(this.player::sendSystemMessage);
            }

            BlockState state = this.player.level().getBlockState(pos);
            this.player.level().sendBlockUpdated(pos, Blocks.AIR.defaultBlockState(), state, 3);
         } else {
            Registry<GameTestInstance> registry = this.player.registryAccess().lookupOrThrow(Registries.TEST_INSTANCE);
            Optional<Reference<GameTestInstance>> test = packet.data().test().flatMap(registry::get);
            Component status;
            if (test.isPresent()) {
               status = ((GameTestInstance)test.get().value()).describe();
            } else {
               status = Component.translatable("test_instance.description.no_test").withStyle(ChatFormatting.RED);
            }

            Optional<Vec3i> size;
            if (packet.action() == net.minecraft.network.protocol.game.ServerboundTestInstanceBlockActionPacket.Action.QUERY) {
               size = packet.data().test().flatMap(testKey -> TestInstanceBlockEntity.getStructureSize(this.player.level(), testKey));
            } else {
               size = Optional.empty();
            }

            this.connection.send(new ClientboundTestInstanceBlockStatus(status, size));
         }
      }
   }

   public void handleSetJigsawBlock(ServerboundSetJigsawBlockPacket packet) {
      PacketUtils.ensureRunningOnSameThread(packet, this, this.player.level());
      if (this.player.canUseGameMasterBlocks()) {
         BlockPos blockPos = packet.getPos();
         BlockState state = this.player.level().getBlockState(blockPos);
         if (this.player.level().getBlockEntity(blockPos) instanceof JigsawBlockEntity jigsaw) {
            jigsaw.setName(packet.getName());
            jigsaw.setTarget(packet.getTarget());
            jigsaw.setPool(ResourceKey.create(Registries.TEMPLATE_POOL, packet.getPool()));
            jigsaw.setFinalState(packet.getFinalState());
            jigsaw.setJoint(packet.getJoint());
            jigsaw.setPlacementPriority(packet.getPlacementPriority());
            jigsaw.setSelectionPriority(packet.getSelectionPriority());
            jigsaw.setChanged();
            this.player.level().sendBlockUpdated(blockPos, state, state, 3);
         }
      }
   }

   public void handleJigsawGenerate(ServerboundJigsawGeneratePacket packet) {
      PacketUtils.ensureRunningOnSameThread(packet, this, this.player.level());
      if (this.player.canUseGameMasterBlocks()) {
         BlockPos blockPos = packet.getPos();
         if (this.player.level().getBlockEntity(blockPos) instanceof JigsawBlockEntity jigsaw) {
            jigsaw.generate(this.player.level(), packet.levels(), packet.keepJigsaws());
         }
      }
   }

   public void handleSelectTrade(ServerboundSelectTradePacket packet) {
      PacketUtils.ensureRunningOnSameThread(packet, this, this.player.level());
      int selection = packet.getItem();
      if (this.player.containerMenu instanceof MerchantMenu menu) {
         TradeSelectEvent tradeSelectEvent = CraftEventFactory.callTradeSelectEvent(selection, menu);
         if (tradeSelectEvent.isCancelled()) {
            this.player.containerMenu.sendAllDataToRemote();
            return;
         }

         if (!menu.stillValid(this.player)) {
            LOGGER.debug("Player {} interacted with invalid menu {}", this.player, menu);
            return;
         }

         menu.setSelectionHint(selection);
         menu.tryMoveItems(selection);
      }
   }

   public void handleEditBook(ServerboundEditBookPacket packet) {
      Disabled pageMax = GlobalConfiguration.get().itemValidation.bookSize.pageMax;
      if (!this.cserver.isPrimaryThread() && pageMax.enabled()) {
         List<String> pageList = packet.pages();
         long byteTotal = 0L;
         int maxBookPageSize = pageMax.intValue();
         double multiplier = Math.clamp(GlobalConfiguration.get().itemValidation.bookSize.totalMultiplier, 0.3, 1.0);
         long byteAllowed = maxBookPageSize;

         for (String page : pageList) {
            int byteLength = page.getBytes(StandardCharsets.UTF_8).length;
            byteTotal += byteLength;
            int length = page.length();
            int multiByteCharacters = 0;
            if (byteLength != length) {
               for (char c : page.toCharArray()) {
                  if (c > 127) {
                     multiByteCharacters++;
                  }
               }
            }

            byteAllowed = (long)(byteAllowed + maxBookPageSize * Math.clamp(length / 255.0, 0.1, 1.0) * multiplier);
            if (multiByteCharacters > 1) {
               byteAllowed -= multiByteCharacters;
            }
         }

         if (byteTotal > byteAllowed) {
            LOGGER.warn(
               "{} tried to send a book too large. Book size: {} - Allowed: {} - Pages: {}",
               new Object[]{this.player.getScoreboardName(), byteTotal, byteAllowed, pageList.size()}
            );
            this.disconnectAsync(Component.literal("Book too large!"), Cause.ILLEGAL_ACTION);
            return;
         }
      }

      if (this.lastBookTick + 20 > MinecraftServer.currentTick) {
         this.disconnectAsync(Component.literal("Book edited too quickly!"), Cause.ILLEGAL_ACTION);
      } else {
         this.lastBookTick = MinecraftServer.currentTick;
         int slot = packet.slot();
         if (Inventory.isHotbarSlot(slot) || slot == 40) {
            List<String> contents = Lists.newArrayList();
            Optional<String> title = packet.title();
            title.ifPresent(contents::add);
            contents.addAll(packet.pages());
            Consumer<List<FilteredText>> handler = title.isPresent()
               ? filteredContents -> this.signBook(filteredContents.get(0), filteredContents.subList(1, filteredContents.size()), slot)
               : filteredContents -> this.updateBookContents(filteredContents, slot);
            this.filterTextPacket(contents).thenAcceptAsync(handler, this.server);
         }
      }
   }

   private void updateBookContents(List<FilteredText> contents, int slot) {
      ItemStack handItem = this.player.getInventory().getItem(slot);
      ItemStack carried = handItem.copy();
      if (carried.has(DataComponents.WRITABLE_BOOK_CONTENT)) {
         List<Filterable<String>> pages = contents.stream().map(this::filterableFromOutgoing).toList();
         carried.set(DataComponents.WRITABLE_BOOK_CONTENT, new WritableBookContent(pages));
         this.player.getInventory().setItem(slot, CraftEventFactory.handleEditBookEvent(this.player, slot, handItem, carried));
      }
   }

   private void signBook(FilteredText title, List<FilteredText> contents, int slot) {
      ItemStack carried = this.player.getInventory().getItem(slot);
      if (carried.has(DataComponents.WRITABLE_BOOK_CONTENT)) {
         ItemStack writtenBook = carried.transmuteCopy(Items.WRITTEN_BOOK);
         writtenBook.remove(DataComponents.WRITABLE_BOOK_CONTENT);
         List<Filterable<Component>> pages = contents.stream().map(page -> this.filterableFromOutgoing(page).map(Component::literal)).toList();
         writtenBook.set(
            DataComponents.WRITTEN_BOOK_CONTENT, new WrittenBookContent(this.filterableFromOutgoing(title), this.player.getPlainTextName(), 0, pages, true)
         );
         CraftEventFactory.handleEditBookEvent(this.player, slot, carried, writtenBook);
         this.player.getInventory().setItem(slot, carried);
      }
   }

   private Filterable<String> filterableFromOutgoing(FilteredText text) {
      return this.player.isTextFilteringEnabled() ? Filterable.passThrough(text.filteredOrEmpty()) : Filterable.from(text);
   }

   public void handleEntityTagQuery(ServerboundEntityTagQueryPacket packet) {
      PacketUtils.ensureRunningOnSameThread(packet, this, this.player.level());
      if (this.player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER)) {
         Entity entity = this.player.level().getEntity(packet.getEntityId());
         if (entity != null) {
            ScopedCollector reporter = new ScopedCollector(entity.problemPath(), LOGGER);

            try {
               TagValueOutput output = TagValueOutput.createWithContext(reporter, entity.registryAccess());
               entity.saveWithoutId(output);
               CompoundTag result = output.buildResult();
               this.send(new ClientboundTagQueryPacket(packet.getTransactionId(), result));
            } catch (Throwable var7) {
               try {
                  reporter.close();
               } catch (Throwable var6) {
                  var7.addSuppressed(var6);
               }

               throw var7;
            }

            reporter.close();
         }
      }
   }

   public void handleContainerSlotStateChanged(ServerboundContainerSlotStateChangedPacket packet) {
      PacketUtils.ensureRunningOnSameThread(packet, this, this.player.level());
      if (!this.player.isSpectator()
         && packet.containerId() == this.player.containerMenu.containerId
         && this.player.containerMenu instanceof CrafterMenu crafterMenu
         && crafterMenu.getContainer() instanceof CrafterBlockEntity crafterBlockEntity) {
         crafterBlockEntity.setSlotState(packet.slotId(), packet.newState());
      }
   }

   public void handleBlockEntityTagQuery(ServerboundBlockEntityTagQueryPacket packet) {
      PacketUtils.ensureRunningOnSameThread(packet, this, this.player.level());
      if (this.player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER)) {
         BlockEntity blockEntity = this.player.level().getBlockEntity(packet.getPos());
         CompoundTag tag = blockEntity != null ? blockEntity.saveWithoutMetadata(this.player.registryAccess()) : null;
         this.send(new ClientboundTagQueryPacket(packet.getTransactionId(), tag));
      }
   }

   public void handleMovePlayer(ServerboundMovePlayerPacket packet) {
      PacketUtils.ensureRunningOnSameThread(packet, this, this.player.level());
      if (containsInvalidValues(packet.getX(0.0), packet.getY(0.0), packet.getZ(0.0), packet.getYRot(0.0F), packet.getXRot(0.0F))) {
         this.disconnect(Component.translatable("multiplayer.disconnect.invalid_player_movement"), Cause.INVALID_PLAYER_MOVEMENT);
      } else {
         ServerLevel level = this.player.level();
         if (!this.player.wonGame && !this.player.isImmobile()) {
            if (this.tickCount == 0) {
               this.resetPosition();
            }

            if (this.hasClientLoaded()) {
               float targetYRot = Mth.wrapDegrees(packet.getYRot(this.player.getYRot()));
               float targetXRot = Mth.wrapDegrees(packet.getXRot(this.player.getXRot()));
               if (this.updateAwaitingTeleport()) {
                  this.player.absSnapRotationTo(targetYRot, targetXRot);
               } else {
                  double targetX = clampHorizontal(packet.getX(this.player.getX()));
                  double targetY = clampVertical(packet.getY(this.player.getY()));
                  double targetZ = clampHorizontal(packet.getZ(this.player.getZ()));
                  if (this.player.isPassenger()) {
                     this.player.absSnapTo(this.player.getX(), this.player.getY(), this.player.getZ(), targetYRot, targetXRot);
                     this.player.level().getChunkSource().move(this.player);
                     this.allowedPlayerTicks = 20;
                  } else {
                     float startYRot = this.player.getYRot();
                     float startXRot = this.player.getXRot();
                     double startX = this.player.getX();
                     double startY = this.player.getY();
                     double startZ = this.player.getZ();
                     double xDist = targetX - this.firstGoodX;
                     double yDist = targetY - this.firstGoodY;
                     double zDist = targetZ - this.firstGoodZ;
                     double expectedDist = this.player.getDeltaMovement().lengthSqr();
                     double movedDist = xDist * xDist + yDist * yDist + zDist * zDist;
                     double currDeltaX = targetX - startX;
                     double currDeltaY = targetY - startY;
                     double currDeltaZ = targetZ - startZ;
                     movedDist = Math.max(movedDist, currDeltaX * currDeltaX + currDeltaY * currDeltaY + currDeltaZ * currDeltaZ - 1.0);
                     double otherFieldX = targetX - this.lastGoodX;
                     double otherFieldY = targetY - this.lastGoodY;
                     double otherFieldZ = targetZ - this.lastGoodZ;
                     movedDist = Math.max(movedDist, otherFieldX * otherFieldX + otherFieldY * otherFieldY + otherFieldZ * otherFieldZ - 1.0);
                     if (this.player.isSleeping()) {
                        if (movedDist > 1.0) {
                           this.teleport(this.player.getX(), this.player.getY(), this.player.getZ(), targetYRot, targetXRot);
                        }
                     } else {
                        boolean isFallFlying = this.player.isFallFlying();
                        if (level.tickRateManager().runsNormally()) {
                           this.receivedMovePacketCount++;
                           int deltaPackets = this.receivedMovePacketCount - this.knownMovePacketCount;
                           this.allowedPlayerTicks = (int)(this.allowedPlayerTicks + (System.currentTimeMillis() / 50L - this.lastTick));
                           this.allowedPlayerTicks = Math.max(this.allowedPlayerTicks, 1);
                           this.lastTick = (int)(System.currentTimeMillis() / 50L);
                           if (deltaPackets > Math.max(this.allowedPlayerTicks, 5)) {
                              LOGGER.debug(
                                 "{} is sending move packets too frequently ({} packets since last tick)", this.player.getPlainTextName(), deltaPackets
                              );
                              deltaPackets = 1;
                           }

                           if (!packet.hasRotation() && !(movedDist > 0.0)) {
                              this.allowedPlayerTicks = 20;
                           } else {
                              this.allowedPlayerTicks--;
                           }

                           double speed;
                           if (this.player.getAbilities().flying) {
                              speed = this.player.getAbilities().getFlyingSpeed() * 20.0F;
                           } else {
                              speed = this.player.getAbilities().getWalkingSpeed() * 10.0F;
                           }

                           if (this.player.level().paperConfig().chunks.preventMovingIntoUnloadedChunks
                              && (this.player.getX() != targetX || this.player.getZ() != targetZ)
                              && !level.areChunksLoadedForMove(
                                 this.player.getBoundingBox().expandTowards(new Vec3(targetX, targetY, targetZ).subtract(this.player.position()))
                              )) {
                              PlayerFailMoveEvent event = this.fireFailMove(
                                 FailReason.MOVED_INTO_UNLOADED_CHUNK, targetX, targetY, targetZ, targetYRot, targetXRot, false
                              );
                              if (!event.isAllowed()) {
                                 this.internalTeleport(PositionMoveRotation.of(this.player), Collections.emptySet());
                                 return;
                              }
                           }

                           if (this.shouldCheckPlayerMovement(isFallFlying)) {
                              float metersPerTick = isFallFlying ? 300.0F : 100.0F;
                              if (movedDist - expectedDist > Math.max(metersPerTick, Mth.square(SpigotConfig.movedTooQuicklyMultiplier * deltaPackets * speed))
                                 )
                               {
                                 PlayerFailMoveEvent event = this.fireFailMove(
                                    FailReason.MOVED_TOO_QUICKLY, targetX, targetY, targetZ, targetYRot, targetXRot, true
                                 );
                                 if (!event.isAllowed()) {
                                    if (event.getLogWarning()) {
                                       LOGGER.warn("{} moved too quickly! {},{},{}", new Object[]{this.player.getPlainTextName(), xDist, yDist, zDist});
                                    }

                                    this.teleport(this.player.getX(), this.player.getY(), this.player.getZ(), this.player.getYRot(), this.player.getXRot());
                                    return;
                                 }
                              }
                           }
                        }

                        AABB oldAABB = this.player.getBoundingBox();
                        xDist = targetX - this.lastGoodX;
                        yDist = targetY - this.lastGoodY;
                        zDist = targetZ - this.lastGoodZ;
                        boolean movedUpwards = yDist > 0.0;
                        if (this.player.onGround() && !packet.isOnGround() && movedUpwards) {
                           Player player = this.getCraftPlayer();
                           Location from = new Location(player.getWorld(), this.lastPosX, this.lastPosY, this.lastPosZ, this.lastYRot, this.lastXRot);
                           Location to = new Location(player.getWorld(), targetX, targetY, targetZ, targetYRot, targetXRot);
                           PlayerJumpEvent event = new PlayerJumpEvent(player, from, to);
                           if (!event.callEvent()) {
                              from = event.getFrom();
                              this.internalTeleport(
                                 new PositionMoveRotation(CraftLocation.toVec3(from), Vec3.ZERO, from.getYaw(), from.getPitch()), Collections.emptySet()
                              );
                              return;
                           }

                           this.player.jumpFromGround();
                        }

                        boolean playerStandsOnSomething = this.player.verticalCollisionBelow;
                        this.player.move(MoverType.PLAYER, new Vec3(xDist, yDist, zDist));
                        this.player.onGround = packet.isOnGround();
                        boolean didCollide = targetX != this.player.getX() || targetY != this.player.getY() || targetZ != this.player.getZ();
                        if (this.awaitingPositionFromClient != null) {
                           return;
                        }

                        double oyDist = yDist;
                        xDist = targetX - this.player.getX();
                        yDist = targetY - this.player.getY();
                        if (yDist > -0.5 || yDist < 0.5) {
                           yDist = 0.0;
                        }

                        zDist = targetZ - this.player.getZ();
                        movedDist = xDist * xDist + yDist * yDist + zDist * zDist;
                        boolean movedWrongly = false;
                        if (!this.player.isChangingDimension()
                           && movedDist > SpigotConfig.movedWronglyThreshold
                           && !this.player.isSleeping()
                           && !this.player.isCreative()
                           && !this.player.isSpectator()
                           && !this.player.isInPostImpulseGraceTime()) {
                           PlayerFailMoveEvent event = this.fireFailMove(FailReason.MOVED_WRONGLY, targetX, targetY, targetZ, targetYRot, targetXRot, true);
                           if (!event.isAllowed()) {
                              movedWrongly = true;
                              if (event.getLogWarning()) {
                                 LOGGER.warn("{} moved wrongly!", this.player.getPlainTextName());
                              }
                           }
                        }

                        boolean allowMovement = this.player.noPhysics || this.player.isSleeping() || !movedWrongly;
                        this.player.absSnapTo(targetX, targetY, targetZ, targetYRot, targetXRot);
                        if (!this.player.noPhysics && !this.player.isSleeping() && allowMovement) {
                           AABB newBox = this.player.getBoundingBox();
                           if (didCollide || !oldAABB.equals(newBox)) {
                              allowMovement = !this.hasNewCollision(level, this.player, oldAABB, newBox);
                           }
                        }

                        if (!allowMovement) {
                           PlayerFailMoveEvent event = this.fireFailMove(
                              FailReason.CLIPPED_INTO_BLOCK, targetX, targetY, targetZ, targetYRot, targetXRot, false
                           );
                           if (event.isAllowed()) {
                              allowMovement = true;
                           }
                        }

                        if (allowMovement) {
                           this.player.absSnapTo(startX, startY, startZ, startYRot, startXRot);
                           Player player = this.getCraftPlayer();
                           if (!this.hasMoved) {
                              this.lastPosX = startX;
                              this.lastPosY = startY;
                              this.lastPosZ = startZ;
                              this.lastYRot = startYRot;
                              this.lastXRot = startXRot;
                              this.hasMoved = true;
                           }

                           Location from = new Location(player.getWorld(), this.lastPosX, this.lastPosY, this.lastPosZ, this.lastYRot, this.lastXRot);
                           Location to = new Location(player.getWorld(), targetX, targetY, targetZ, targetYRot, targetXRot);
                           double delta = Mth.square(this.lastPosX - to.getX()) + Mth.square(this.lastPosY - to.getY()) + Mth.square(this.lastPosZ - to.getZ());
                           float deltaAngle = Math.abs(this.lastYRot - to.getYaw()) + Math.abs(this.lastXRot - to.getPitch());
                           if ((delta > 0.00390625 || deltaAngle > 10.0F) && !this.player.isImmobile()) {
                              this.lastPosX = to.getX();
                              this.lastPosY = to.getY();
                              this.lastPosZ = to.getZ();
                              this.lastYRot = to.getYaw();
                              this.lastXRot = to.getPitch();
                              Location oldTo = to.clone();
                              PlayerMoveEvent event = new PlayerMoveEvent(player, from, to);
                              this.cserver.getPluginManager().callEvent(event);
                              if (event.isCancelled()) {
                                 this.internalTeleport(from);
                                 return;
                              }

                              if (!oldTo.equals(event.getTo()) && !event.isCancelled()) {
                                 this.player.getBukkitEntity().teleport(event.getTo(), TeleportCause.PLUGIN);
                                 return;
                              }

                              if (!from.equals(this.getCraftPlayer().getLocation()) && this.justTeleported) {
                                 this.justTeleported = false;
                                 return;
                              }
                           }

                           this.player.absSnapTo(targetX, targetY, targetZ, targetYRot, targetXRot);
                           boolean isAutoSpinAttack = this.player.isAutoSpinAttack();
                           this.clientIsFloating = oyDist >= -0.03125
                              && !playerStandsOnSomething
                              && !this.player.isSpectator()
                              && !this.server.allowFlight()
                              && !this.player.getAbilities().mayfly
                              && !this.player.hasEffect(MobEffects.LEVITATION)
                              && !isFallFlying
                              && !isAutoSpinAttack
                              && this.noBlocksAround(this.player);
                           this.player.level().getChunkSource().move(this.player);
                           Vec3 clientDeltaMovement = new Vec3(this.player.getX() - startX, this.player.getY() - startY, this.player.getZ() - startZ);
                           this.player.setOnGroundWithMovement(packet.isOnGround(), packet.horizontalCollision(), clientDeltaMovement);
                           this.player.doCheckFallDamage(clientDeltaMovement.x, clientDeltaMovement.y, clientDeltaMovement.z, packet.isOnGround());
                           this.handlePlayerKnownMovement(clientDeltaMovement);
                           if (movedUpwards) {
                              this.player.resetFallDistance();
                           }

                           if (packet.isOnGround()
                              || this.player.hasLandedInLiquid()
                              || this.player.onClimbable()
                              || this.player.isSpectator()
                              || isFallFlying
                              || isAutoSpinAttack) {
                              this.player.tryResetCurrentImpulseContext();
                           }

                           this.player.checkMovementStatistics(this.player.getX() - startX, this.player.getY() - startY, this.player.getZ() - startZ);
                           this.lastGoodX = this.player.getX();
                           this.lastGoodY = this.player.getY();
                           this.lastGoodZ = this.player.getZ();
                        } else {
                           this.internalTeleport(startX, startY, startZ, targetYRot, targetXRot);
                           this.player
                              .doCheckFallDamage(this.player.getX() - startX, this.player.getY() - startY, this.player.getZ() - startZ, packet.isOnGround());
                           this.player.removeLatestMovementRecording();
                        }
                     }
                  }
               }
            }
         }
      }
   }

   private boolean shouldCheckPlayerMovement(boolean isFallFlying) {
      if (this.isSingleplayerOwner()) {
         return false;
      }

      if (this.player.isChangingDimension()) {
         return false;
      }

      GameRules gameRules = this.player.level().getGameRules();
      return (Boolean)gameRules.get(GameRules.PLAYER_MOVEMENT_CHECK) && (!isFallFlying || (Boolean)gameRules.get(GameRules.ELYTRA_MOVEMENT_CHECK));
   }

   private boolean updateAwaitingTeleport() {
      if (this.awaitingPositionFromClient != null) {
         this.allowedPlayerTicks = 20;
         return true;
      } else {
         this.awaitingTeleportTime = this.tickCount;
         return false;
      }
   }

   private boolean hasNewCollision(ServerLevel level, Entity entity, AABB oldBox, AABB newBox) {
      List<AABB> collisionsBB = new ArrayList<>();
      List<VoxelShape> collisionsVoxel = new ArrayList<>();
      int flags = 4;
      if (level.paperConfig().chunks.preventMovingIntoUnloadedChunks) {
         flags |= 2;
      }

      CollisionUtil.getCollisions(level, entity, newBox, collisionsVoxel, collisionsBB, flags, null, null);
      int i = 0;

      for (int len = collisionsBB.size(); i < len; i++) {
         AABB box = collisionsBB.get(i);
         if (!CollisionUtil.voxelShapeIntersect(box, oldBox)) {
            return true;
         }
      }

      i = 0;

      for (int len = collisionsVoxel.size(); i < len; i++) {
         VoxelShape voxel = collisionsVoxel.get(i);
         if (!CollisionUtil.voxelShapeIntersectNoEmpty(voxel, oldBox)) {
            return true;
         }
      }

      return false;
   }

   private boolean isEntityCollidingWithAnythingNew(LevelReader level, Entity entity, AABB oldAABB, double newX, double newY, double newZ) {
      AABB newAABB = entity.getBoundingBox().move(newX - entity.getX(), newY - entity.getY(), newZ - entity.getZ());
      Iterable<VoxelShape> newCollisions = level.getPreMoveCollisions(entity, newAABB.deflate(1.0E-5F), oldAABB.getBottomCenter());
      VoxelShape oldShape = Shapes.create(oldAABB.deflate(1.0E-5F));

      for (VoxelShape shape : newCollisions) {
         if (!Shapes.joinIsNotEmpty(shape, oldShape, BooleanOp.AND)) {
            return true;
         }
      }

      return false;
   }

   public void teleport(double x, double y, double z, float yRot, float xRot) {
      this.teleport(x, y, z, yRot, xRot, TeleportCause.UNKNOWN);
   }

   public boolean teleport(double x, double y, double z, float yRot, float xRot, TeleportCause cause) {
      return this.teleport(new PositionMoveRotation(new Vec3(x, y, z), Vec3.ZERO, yRot, xRot), Collections.emptySet(), cause);
   }

   public void teleport(PositionMoveRotation destination, Set<Relative> relatives) {
      this.teleport(destination, relatives, TeleportCause.UNKNOWN);
   }

   public boolean teleport(PositionMoveRotation destination, Set<Relative> relatives, TeleportCause cause) {
      Player player = this.getCraftPlayer();
      Location from = player.getLocation();
      PositionMoveRotation absolutePosition = PositionMoveRotation.calculateAbsolute(PositionMoveRotation.of(this.player), destination, relatives);
      Location to = CraftLocation.toBukkit(absolutePosition.position(), this.player.level(), absolutePosition.yRot(), absolutePosition.xRot());
      if (from.equals(to)) {
         this.internalTeleport(destination, relatives);
         return true;
      }

      Set<io.papermc.paper.entity.TeleportFlag.Relative> relativeFlags = EnumSet.noneOf(io.papermc.paper.entity.TeleportFlag.Relative.class);

      for (Relative relativeArgument : relatives) {
         io.papermc.paper.entity.TeleportFlag.Relative flag = CraftEntity.deltaRelativeToAPI(relativeArgument);
         if (flag != null) {
            relativeFlags.add(flag);
         }
      }

      PlayerTeleportEvent event = new PlayerTeleportEvent(player, from.clone(), to.clone(), cause, Set.copyOf(relativeFlags));
      this.cserver.getPluginManager().callEvent(event);
      if (event.isCancelled() || !to.equals(event.getTo())) {
         relatives = Set.of();
         to = event.isCancelled() ? event.getFrom() : event.getTo();
         destination = new PositionMoveRotation(CraftLocation.toVec3(to), Vec3.ZERO, to.getYaw(), to.getPitch());
      }

      this.internalTeleport(destination, relatives);
      return !event.isCancelled();
   }

   public void internalTeleport(Location dest) {
      this.internalTeleport(dest.getX(), dest.getY(), dest.getZ(), dest.getYaw(), dest.getPitch());
   }

   public void internalTeleport(double x, double y, double z, float yRot, float xRot) {
      this.internalTeleport(new PositionMoveRotation(new Vec3(x, y, z), Vec3.ZERO, yRot, xRot), Collections.emptySet());
   }

   public void internalTeleport(PositionMoveRotation destination, Set<Relative> relatives) {
      AsyncCatcher.catchOp("teleport");
      if (this.player.isRemoved()) {
         LOGGER.info("Attempt to teleport removed player {} restricted", this.player.getScoreboardName());
         if (this.server.isDebugging()) {
            TraceUtil.dumpTraceForThread("Attempt to teleport removed player");
         }
      } else {
         if (Float.isNaN(destination.yRot())) {
            destination = new PositionMoveRotation(destination.position(), destination.deltaMovement(), 0.0F, destination.xRot());
         }

         if (Float.isNaN(destination.xRot())) {
            destination = new PositionMoveRotation(destination.position(), destination.deltaMovement(), destination.yRot(), 0.0F);
         }

         this.justTeleported = true;
         this.awaitingTeleportTime = this.tickCount;
         if (++this.awaitingTeleport == Integer.MAX_VALUE) {
            this.awaitingTeleport = 0;
         }

         this.player.teleportSetPosition(destination, relatives);
         this.awaitingPositionFromClient = this.player.position();
         this.lastPosX = this.awaitingPositionFromClient.x;
         this.lastPosY = this.awaitingPositionFromClient.y;
         this.lastPosZ = this.awaitingPositionFromClient.z;
         this.lastYRot = this.player.getYRot();
         this.lastXRot = this.player.getXRot();
         this.send(ClientboundPlayerPositionPacket.of(this.awaitingTeleport, destination, relatives));
      }
   }

   public void handlePlayerAction(ServerboundPlayerActionPacket packet) {
      PacketUtils.ensureRunningOnSameThread(packet, this, this.player.level());
      if (!this.player.isImmobile()) {
         if (this.hasClientLoaded()) {
            BlockPos pos = packet.getPos();
            this.player.resetLastActionTime();
            net.minecraft.network.protocol.game.ServerboundPlayerActionPacket.Action action = packet.getAction();
            switch (action) {
               case STAB:
                  if (this.player.isSpectator()) {
                     return;
                  } else {
                     ItemStack itemInHand = this.player.getItemInHand(InteractionHand.MAIN_HAND);
                     if (this.player.cannotAttackWithItem(itemInHand, 5)) {
                        return;
                     }

                     PiercingWeapon piercingWeapon = (PiercingWeapon)itemInHand.get(DataComponents.PIERCING_WEAPON);
                     if (piercingWeapon != null) {
                        piercingWeapon.attack(this.player, EquipmentSlot.MAINHAND);
                     }

                     return;
                  }
               case SWAP_ITEM_WITH_OFFHAND:
                  if (!this.player.isSpectator()) {
                     ItemStack swap = this.player.getItemInHand(InteractionHand.OFF_HAND);
                     CraftItemStack mainHand = CraftItemStack.asCraftMirror(swap);
                     CraftItemStack offHand = CraftItemStack.asCraftMirror(this.player.getItemInHand(InteractionHand.MAIN_HAND));
                     PlayerSwapHandItemsEvent swapItemsEvent = new PlayerSwapHandItemsEvent(this.getCraftPlayer(), mainHand.clone(), offHand.clone());
                     this.cserver.getPluginManager().callEvent(swapItemsEvent);
                     if (swapItemsEvent.isCancelled()) {
                        return;
                     }

                     if (swapItemsEvent.getOffHandItem().equals(offHand)) {
                        this.player.setItemInHand(InteractionHand.OFF_HAND, this.player.getItemInHand(InteractionHand.MAIN_HAND));
                     } else {
                        this.player.setItemInHand(InteractionHand.OFF_HAND, CraftItemStack.asNMSCopy(swapItemsEvent.getOffHandItem()));
                     }

                     if (swapItemsEvent.getMainHandItem().equals(mainHand)) {
                        this.player.setItemInHand(InteractionHand.MAIN_HAND, swap);
                     } else {
                        this.player.setItemInHand(InteractionHand.MAIN_HAND, CraftItemStack.asNMSCopy(swapItemsEvent.getMainHandItem()));
                     }

                     this.player.stopUsingItem();
                     if (GlobalConfiguration.get().unsupportedSettings.updateEquipmentOnPlayerActions) {
                        this.player.detectEquipmentUpdates();
                     }
                  }

                  return;
               case DROP_ITEM:
                  if (!this.player.isSpectator()) {
                     if (this.lastDropTick != MinecraftServer.currentTick) {
                        this.dropCount = 0;
                        this.lastDropTick = MinecraftServer.currentTick;
                     } else {
                        this.dropCount++;
                        if (this.dropCount >= 20) {
                           LOGGER.warn(this.player.getScoreboardName() + " dropped their items too quickly!");
                           this.disconnect(Component.literal("You dropped your items too quickly (Hacking?)"), Cause.ILLEGAL_ACTION);
                           return;
                        }
                     }

                     this.player.drop(false);
                     if (GlobalConfiguration.get().unsupportedSettings.updateEquipmentOnPlayerActions) {
                        this.player.detectEquipmentUpdates();
                     }
                  }

                  return;
               case DROP_ALL_ITEMS:
                  if (!this.player.isSpectator()) {
                     this.player.drop(true);
                     if (GlobalConfiguration.get().unsupportedSettings.updateEquipmentOnPlayerActions) {
                        this.player.detectEquipmentUpdates();
                     }
                  }

                  return;
               case RELEASE_USE_ITEM:
                  this.player.releaseUsingItem();
                  if (GlobalConfiguration.get().unsupportedSettings.updateEquipmentOnPlayerActions) {
                     this.player.detectEquipmentUpdates();
                  }

                  return;
               case START_DESTROY_BLOCK:
               case ABORT_DESTROY_BLOCK:
               case STOP_DESTROY_BLOCK:
                  if (this.player.level().getChunkIfLoadedImmediately(pos.getX() >> 4, pos.getZ() >> 4) != null
                     && this.player.isWithinBlockInteractionRange(pos, 1.0)) {
                     this.player.gameMode.capturedBlockEntity = false;
                     this.player.gameMode.captureSentBlockEntities = true;
                     this.player.gameMode.handleBlockBreakAction(pos, action, packet.getDirection(), this.player.level().getMaxY(), packet.getSequence());
                     this.ackBlockChangesUpTo(packet.getSequence());
                     this.player.gameMode.captureSentBlockEntities = false;
                     if (this.player.gameMode.capturedBlockEntity) {
                        this.send(new ClientboundBlockChangedAckPacket(this.ackBlockChangesUpTo));
                        this.player.connection.ackBlockChangesUpTo = -1;
                        this.player.gameMode.capturedBlockEntity = false;
                        BlockEntity blockEntity = this.player.level().getBlockEntity(pos);
                        if (blockEntity != null) {
                           this.player.connection.send(blockEntity.getUpdatePacket());
                        }
                     }

                     if (GlobalConfiguration.get().unsupportedSettings.updateEquipmentOnPlayerActions) {
                        this.player.detectEquipmentUpdates();
                     }

                     return;
                  }

                  this.player.connection.ackBlockChangesUpTo(packet.getSequence());
                  return;
               default:
                  throw new IllegalArgumentException("Invalid player action");
            }
         }
      }
   }

   private static boolean wasBlockPlacementAttempt(ServerPlayer player, ItemStack itemStack) {
      if (itemStack.isEmpty()) {
         return false;
      }

      Item item = itemStack.getItem();
      return (item instanceof BlockItem || item instanceof BucketItem bucket && bucket.getContent() != Fluids.EMPTY)
         && !player.getCooldowns().isOnCooldown(itemStack);
   }

   private boolean checkLimit(long timestamp) {
      if (!GlobalConfiguration.get().spamLimiter.incomingPacketThreshold.enabled()) {
         return true;
      }

      int threshold = GlobalConfiguration.get().spamLimiter.incomingPacketThreshold.intValue();
      if (this.lastLimitedPacket != -1L && timestamp - this.lastLimitedPacket < threshold && this.limitedPackets++ >= 8) {
         return false;
      }

      if (this.lastLimitedPacket != -1L && timestamp - this.lastLimitedPacket < threshold) {
         return true;
      }

      this.lastLimitedPacket = timestamp;
      this.limitedPackets = 0;
      return true;
   }

   public void handleUseItemOn(ServerboundUseItemOnPacket packet) {
      PacketUtils.ensureRunningOnSameThread(packet, this, this.player.level());
      if (!this.player.isImmobile()) {
         if (this.checkLimit(packet.timestamp)) {
            if (this.hasClientLoaded()) {
               this.ackBlockChangesUpTo(packet.getSequence());
               ServerLevel level = this.player.level();
               InteractionHand hand = packet.getHand();
               ItemStack itemStack = this.player.getItemInHand(hand);
               if (itemStack.isItemEnabled(level.enabledFeatures())) {
                  BlockHitResult blockHit = packet.getHitResult();
                  Vec3 location = blockHit.getLocation();
                  if (!location.isFinite()) {
                     return;
                  }

                  BlockPos pos = blockHit.getBlockPos();
                  if (this.player.isWithinBlockInteractionRange(pos, 1.0)) {
                     Vec3 distance = location.subtract(Vec3.atCenterOf(pos));
                     double limit = 1.0000001;
                     if (Math.abs(distance.x()) < 1.0000001 && Math.abs(distance.y()) < 1.0000001 && Math.abs(distance.z()) < 1.0000001) {
                        Direction direction = blockHit.getDirection();
                        this.player.resetLastActionTime();
                        int maxY = level.getMaxY();
                        int minY = level.getMinY();
                        if (pos.getY() > maxY) {
                           this.player.sendBuildLimitMessage(true, maxY);
                        } else if (pos.getY() < minY) {
                           this.player.sendBuildLimitMessage(false, minY);
                        } else {
                           boolean passthroughSignInteraction = level.paperConfig().spawn.allowUsingSignsInsideSpawnProtection
                              && level.getBlockState(pos).getBlock() instanceof SignBlock;
                           if (!passthroughSignInteraction && this.server.isUnderSpawnProtection(level, pos, this.player)) {
                              this.player.sendSpawnProtectionMessage(pos);
                           } else if (this.awaitingPositionFromClient == null && (level.mayInteract(this.player, pos) || passthroughSignInteraction)) {
                              this.player.stopUsingItem();
                              InteractionResult interactionResult = this.player.gameMode.useItemOn(this.player, level, itemStack, hand, blockHit);
                              if (interactionResult.consumesAction()) {
                                 CriteriaTriggers.ANY_BLOCK_USE.trigger(this.player, blockHit.getBlockPos(), itemStack);
                              }

                              if (direction == Direction.UP
                                 && !interactionResult.consumesAction()
                                 && pos.getY() >= maxY
                                 && wasBlockPlacementAttempt(this.player, itemStack)) {
                                 this.player.sendBuildLimitMessage(true, maxY);
                              } else if (interactionResult instanceof Success success
                                 && success.swingSource() == SwingSource.SERVER
                                 && !this.player.gameMode.interactResult) {
                                 this.player.swing(hand, true);
                              }

                              if (!interactionResult.consumesAction() && wasBlockPlacementAttempt(this.player, itemStack)) {
                                 if (direction == Direction.UP && pos.getY() >= maxY) {
                                    this.player.sendBuildLimitMessage(true, maxY);
                                 } else if (direction == Direction.DOWN && pos.getY() <= minY) {
                                    this.player.sendBuildLimitMessage(false, minY);
                                 }
                              } else if (interactionResult instanceof Success success && success.swingSource() == SwingSource.SERVER) {
                                 this.player.swing(hand, true);
                              }
                           } else if (this.server.isUnderSpawnProtection(level, pos, this.player)) {
                              if (MCUtil.clientPredictsInteraction(this.player, level.getBlockState(pos), itemStack)) {
                                 this.player.containerMenu.sendAllDataToRemote();
                              } else {
                                 this.player.containerMenu.forceHeldSlot(hand);
                              }
                           }

                           this.send(new ClientboundBlockUpdatePacket(level, pos));
                           this.send(new ClientboundBlockUpdatePacket(level, pos.relative(direction)));
                        }

                        if (GlobalConfiguration.get().unsupportedSettings.updateEquipmentOnPlayerActions) {
                           this.player.detectEquipmentUpdates();
                        }
                     }
                  }
               }
            }
         }
      }
   }

   public void handleUseItem(ServerboundUseItemPacket packet) {
      PacketUtils.ensureRunningOnSameThread(packet, this, this.player.level());
      if (!this.player.isImmobile()) {
         if (this.checkLimit(packet.timestamp)) {
            if (this.hasClientLoaded()) {
               this.ackBlockChangesUpTo(packet.getSequence());
               ServerLevel level = this.player.level();
               InteractionHand hand = packet.getHand();
               ItemStack itemStack = this.player.getItemInHand(hand);
               this.player.resetLastActionTime();
               if (!itemStack.isEmpty() && itemStack.isItemEnabled(level.enabledFeatures())) {
                  float targetYRot = Mth.wrapDegrees(packet.getYRot());
                  float targetXRot = Mth.wrapDegrees(packet.getXRot());
                  if (targetXRot != this.player.getXRot() || targetYRot != this.player.getYRot()) {
                     this.player.absSnapRotationTo(targetYRot, targetXRot);
                  }

                  Vec3 from = this.player.getEyePosition();
                  Vec3 delta = this.player.getViewVector(1.0F).scale(this.player.blockInteractionRange());
                  Vec3 to = from.add(delta);
                  BlockHitResult hitResult = this.player.level().clip(new ClipContext(from, to, Block.OUTLINE, Fluid.NONE, this.player));
                  boolean cancelled;
                  if (hitResult != null && hitResult.getType() == Type.BLOCK) {
                     if (this.player.gameMode.firedInteract
                        && this.player.gameMode.interactPosition.equals(hitResult.getBlockPos())
                        && this.player.gameMode.interactHand == hand
                        && ItemStack.isSameItemSameComponents(this.player.gameMode.interactItemStack, itemStack)) {
                        cancelled = this.player.gameMode.interactResult;
                     } else {
                        PlayerInteractEvent event = CraftEventFactory.callPlayerInteractEvent(
                           this.player,
                           org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK,
                           hitResult.getBlockPos(),
                           hitResult.getDirection(),
                           itemStack,
                           true,
                           hand,
                           hitResult.getLocation()
                        );
                        cancelled = event.useItemInHand() == Result.DENY;
                     }

                     this.player.gameMode.firedInteract = false;
                  } else {
                     PlayerInteractEvent event = CraftEventFactory.callPlayerInteractEvent(
                        this.player, org.bukkit.event.block.Action.RIGHT_CLICK_AIR, itemStack, hand
                     );
                     cancelled = event.useItemInHand() == Result.DENY;
                  }

                  if (cancelled) {
                     this.player.resyncUsingItem(this.player);
                     if (MCUtil.clientPredictsInteraction(this.player, Blocks.AIR.defaultBlockState(), itemStack)) {
                        this.player.containerMenu.sendAllDataToRemote();
                     } else {
                        this.player.containerMenu.forceHeldSlotAndArmor(hand);
                     }

                     return;
                  }

                  itemStack = this.player.getItemInHand(hand);
                  if (itemStack.isEmpty()) {
                     return;
                  }

                  if (this.player.gameMode.useItem(this.player, level, itemStack, hand) instanceof Success success
                     && success.swingSource() == SwingSource.SERVER) {
                     this.player.swing(hand, true);
                  }
               }
            }
         }
      }
   }

   public void handleTeleportToEntityPacket(ServerboundTeleportToEntityPacket packet) {
      PacketUtils.ensureRunningOnSameThread(packet, this, this.player.level());
      if (this.player.isSpectator()) {
         for (ServerLevel level : this.server.getAllLevels()) {
            Entity entity = packet.getEntity(level);
            if (entity != null) {
               this.player
                  .teleportTo(level, entity.getX(), entity.getY(), entity.getZ(), Set.of(), entity.getYRot(), entity.getXRot(), true, TeleportCause.SPECTATE);
               return;
            }
         }
      }
   }

   public void handlePaddleBoat(ServerboundPaddleBoatPacket packet) {
      PacketUtils.ensureRunningOnSameThread(packet, this, this.player.level());
      if (this.player.getControlledVehicle() instanceof AbstractBoat boat) {
         boat.setPaddleState(packet.getLeft(), packet.getRight());
      }
   }

   public void onDisconnect(DisconnectionDetails details) {
      LOGGER.info("{} lost connection: {}", this.player.getPlainTextName(), details.reason().getString());
      net.kyori.adventure.text.Component quitMessage = details.quitMessage().<net.kyori.adventure.text.Component>map(PaperAdventure::asAdventure).orElse(null);
      this.removePlayerFromWorld(quitMessage);
      super.onDisconnect(details);
   }

   private void removePlayerFromWorld() {
      this.removePlayerFromWorld(null);
   }

   private void removePlayerFromWorld(@Nullable Component quitMessage) {
      this.chatMessageChain.close();
      this.player.disconnect();
      quitMessage = quitMessage == null ? this.server.getPlayerList().remove(this.player) : this.server.getPlayerList().remove(this.player, quitMessage);
      if (quitMessage != null && !quitMessage.equals(net.kyori.adventure.text.Component.empty())) {
         this.server.getPlayerList().broadcastSystemMessage(PaperAdventure.asVanilla(quitMessage), false);
      }

      this.player.getTextFilter().leave();
   }

   public void ackBlockChangesUpTo(int packetSequenceNr) {
      if (packetSequenceNr < 0) {
         this.disconnect(Component.literal("Expected packet sequence nr >= 0"), Cause.ILLEGAL_ACTION);
         throw new IllegalArgumentException("Expected packet sequence nr >= 0");
      }

      this.ackBlockChangesUpTo = Math.max(packetSequenceNr, this.ackBlockChangesUpTo);
   }

   public void handleSetCarriedItem(ServerboundSetCarriedItemPacket packet) {
      PacketUtils.ensureRunningOnSameThread(packet, this, this.player.level());
      if (!this.player.isImmobile()) {
         if (packet.getSlot() >= 0 && packet.getSlot() < Inventory.getSelectionSize()) {
            if (packet.getSlot() == this.player.getInventory().getSelectedSlot()) {
               return;
            }

            PlayerItemHeldEvent event = new PlayerItemHeldEvent(this.getCraftPlayer(), this.player.getInventory().getSelectedSlot(), packet.getSlot());
            this.cserver.getPluginManager().callEvent(event);
            if (event.isCancelled()) {
               this.send(new ClientboundSetHeldSlotPacket(this.player.getInventory().getSelectedSlot()));
               this.player.resetLastActionTime();
               return;
            }

            if (this.player.getInventory().getSelectedSlot() != packet.getSlot() && this.player.getUsedItemHand() == InteractionHand.MAIN_HAND) {
               this.player.stopUsingItem();
            }

            this.player.getInventory().setSelectedSlot(packet.getSlot());
            this.player.resetLastActionTime();
            if (GlobalConfiguration.get().unsupportedSettings.updateEquipmentOnPlayerActions) {
               this.player.detectEquipmentUpdates();
            }
         } else {
            LOGGER.warn("{} tried to set an invalid carried item", this.player.getPlainTextName());
            this.disconnect(Component.literal("Invalid hotbar selection (Hacking?)"), Cause.ILLEGAL_ACTION);
         }
      }
   }

   public void handleChat(ServerboundChatPacket packet) {
      if (!this.server.isStopped()) {
         Optional<LastSeenMessages> unpackedLastSeen = this.unpackAndApplyLastSeen(packet.lastSeenMessages());
         if (!unpackedLastSeen.isEmpty()) {
            this.tryHandleChat(
               packet.message(),
               false,
               () -> {
                  PlayerChatMessage signedMessage;
                  try {
                     signedMessage = this.getSignedMessage(packet, unpackedLastSeen.get());
                  } catch (DecodeException e) {
                     this.handleMessageDecodeFailure(e);
                     return;
                  }

                  CompletableFuture<FilteredText> filteredFuture = this.filterTextPacket(signedMessage.signedContent())
                     .thenApplyAsync(Function.identity(), this.server.chatExecutor);
                  CompletableFuture<Component> decoratedFuture = this.server.getChatDecorator().decorate(this.player, null, signedMessage.decoratedContent());
                  this.chatMessageChain.append(CompletableFuture.allOf(filteredFuture, decoratedFuture), $ -> {
                     PlayerChatMessage filteredMessage = signedMessage.withUnsignedContent(decoratedFuture.join()).filter(filteredFuture.join().mask());
                     this.broadcastChatMessage(filteredMessage);
                  });
               },
               false
            );
         }
      }
   }

   public void handleChatCommand(ServerboundChatCommandPacket packet) {
      this.tryHandleChat(packet.command(), true, () -> {
         if (!this.player.hasDisconnected()) {
            this.performUnsignedChatCommand(packet.command());
            this.detectCommandRateSpam(packet.command());
         }
      }, true);
   }

   private void performUnsignedChatCommand(String command) {
      String prefixedCommand = "/" + command;
      if (SpigotConfig.logCommands) {
         LOGGER.info("{} issued server command: {}", this.player.getScoreboardName(), prefixedCommand);
      }

      PlayerCommandPreprocessEvent event = new PlayerCommandPreprocessEvent(this.getCraftPlayer(), prefixedCommand, new LazyPlayerSet(this.server));
      this.cserver.getPluginManager().callEvent(event);
      if (!event.isCancelled()) {
         command = event.getMessage().substring(1);
         ParseResults<CommandSourceStack> parsed = this.parseCommand(command);
         if (this.server.enforceSecureProfile() && SignableCommand.hasSignableArguments(parsed)) {
            LOGGER.error(
               "Received unsigned command packet from {}, but the command requires signable arguments: {}", this.player.getGameProfile().name(), command
            );
            this.player.sendSystemMessage(INVALID_COMMAND_SIGNATURE);
         } else {
            this.server.getCommands().performCommand(parsed, command);
         }
      }
   }

   public void handleSignedChatCommand(ServerboundChatCommandSignedPacket packet) {
      Optional<LastSeenMessages> unpackedLastSeen = this.unpackAndApplyLastSeen(packet.lastSeenMessages());
      if (!unpackedLastSeen.isEmpty()) {
         this.tryHandleChat(packet.command(), true, () -> {
            if (!this.player.hasDisconnected()) {
               this.performSignedChatCommand(packet, unpackedLastSeen.get());
               this.detectCommandRateSpam(packet.command());
            }
         }, true);
      }
   }

   private void performSignedChatCommand(ServerboundChatCommandSignedPacket packet, LastSeenMessages lastSeenMessages) {
      String commandString = "/" + packet.command();
      if (SpigotConfig.logCommands) {
         LOGGER.info("{} issued server command: {}", this.player.getScoreboardName(), commandString);
      }

      PlayerCommandPreprocessEvent event = new PlayerCommandPreprocessEvent(this.getCraftPlayer(), commandString, new LazyPlayerSet(this.server));
      this.cserver.getPluginManager().callEvent(event);
      commandString = event.getMessage().substring(1);
      ParseResults<CommandSourceStack> command = this.parseCommand(packet.command());

      Map<String, PlayerChatMessage> signedArguments;
      try {
         signedArguments = this.collectSignedArguments(packet, SignableCommand.of(command), lastSeenMessages);
      } catch (DecodeException e) {
         this.handleMessageDecodeFailure(e);
         return;
      }

      if (!event.isCancelled()) {
         if (!commandString.equals(packet.command())) {
            command = this.parseCommand(commandString);
            signedArguments = Collections.emptyMap();
         }

         CommandSigningContext signingContext = new SignedArguments(signedArguments);
         command = Commands.mapSource(command, source -> source.withSigningContext(signingContext, this.chatMessageChain));
         this.server.getCommands().performCommand(command, commandString);
      }
   }

   private void handleMessageDecodeFailure(DecodeException e) {
      LOGGER.warn("Failed to update secure chat state for {}: '{}'", this.player.getGameProfile().name(), e.getComponent().getString());
      this.player.sendSystemMessage(e.getComponent().copy().withStyle(ChatFormatting.RED));
   }

   private <S> Map<String, PlayerChatMessage> collectSignedArguments(
      ServerboundChatCommandSignedPacket packet, SignableCommand<S> command, LastSeenMessages lastSeenMessages
   ) throws DecodeException {
      List<Entry> argumentSignatures = packet.argumentSignatures().entries();
      List<Argument<S>> parsedArguments = command.arguments();
      if (argumentSignatures.isEmpty()) {
         return this.collectUnsignedArguments(parsedArguments);
      }

      Map<String, PlayerChatMessage> signedArguments = new Object2ObjectOpenHashMap();

      for (Entry clientArgument : argumentSignatures) {
         Argument<S> expectedArgument = command.getArgument(clientArgument.name());
         if (expectedArgument == null) {
            this.signedMessageDecoder.setChainBroken();
            throw createSignedArgumentMismatchException(packet.command(), argumentSignatures, parsedArguments);
         }

         SignedMessageBody body = new SignedMessageBody(expectedArgument.value(), packet.timeStamp(), packet.salt(), lastSeenMessages);
         signedArguments.put(expectedArgument.name(), this.signedMessageDecoder.unpack(clientArgument.signature(), body));
      }

      for (Argument<S> expectedArgument : parsedArguments) {
         if (!signedArguments.containsKey(expectedArgument.name())) {
            throw createSignedArgumentMismatchException(packet.command(), argumentSignatures, parsedArguments);
         }
      }

      return signedArguments;
   }

   private <S> Map<String, PlayerChatMessage> collectUnsignedArguments(List<Argument<S>> parsedArguments) throws DecodeException {
      Map<String, PlayerChatMessage> arguments = new HashMap<>();

      for (Argument<S> parsedArgument : parsedArguments) {
         SignedMessageBody body = SignedMessageBody.unsigned(parsedArgument.value());
         arguments.put(parsedArgument.name(), this.signedMessageDecoder.unpack(null, body));
      }

      return arguments;
   }

   private static <S> DecodeException createSignedArgumentMismatchException(String command, List<Entry> clientArguments, List<Argument<S>> expectedArguments) {
      String clientNames = clientArguments.stream().<CharSequence>map(Entry::name).collect(Collectors.joining(", "));
      String expectedNames = expectedArguments.stream().<CharSequence>map(Argument::name).collect(Collectors.joining(", "));
      LOGGER.error(
         "Signed command mismatch between server and client ('{}'): got [{}] from client, but expected [{}]", new Object[]{command, clientNames, expectedNames}
      );
      return new DecodeException(INVALID_COMMAND_SIGNATURE);
   }

   private ParseResults<CommandSourceStack> parseCommand(String command) {
      CommandDispatcher<CommandSourceStack> commands = this.server.getCommands().getDispatcher();
      return commands.parse(command, this.player.createCommandSourceStack());
   }

   private void tryHandleChat(String message, boolean isCommand, Runnable chatHandler, boolean sync) {
      if (isChatMessageIllegal(message)) {
         this.disconnectAsync(Component.translatable("multiplayer.disconnect.illegal_characters"), Cause.ILLEGAL_CHARACTERS);
      } else if (!this.player.isRemoved() && (isCommand || this.player.getChatVisibility() != ChatVisiblity.HIDDEN)) {
         this.player.resetLastActionTime();
         if (sync) {
            this.server.execute(chatHandler);
         } else {
            chatHandler.run();
         }
      } else {
         this.send(new ClientboundSystemChatPacket(Component.translatable("chat.disabled.options").withStyle(ChatFormatting.RED), false));
      }
   }

   private Optional<LastSeenMessages> unpackAndApplyLastSeen(Update update) {
      synchronized (this.lastSeenMessages) {
         Optional var10000;
         try {
            LastSeenMessages result = this.lastSeenMessages.applyUpdate(update);
            var10000 = Optional.of(result);
         } catch (ValidationException e) {
            LOGGER.error("Failed to validate message acknowledgements from {}: {}", this.player.getPlainTextName(), e.getMessage());
            this.disconnectAsync(CHAT_VALIDATION_FAILED, Cause.CHAT_VALIDATION_FAILED);
            return Optional.empty();
         }

         return var10000;
      }
   }

   public static boolean isChatMessageIllegal(String message) {
      for (int i = 0; i < message.length(); i++) {
         if (!StringUtil.isAllowedChatCharacter(message.charAt(i))) {
            return true;
         }
      }

      return false;
   }

   public void chat(String msg, PlayerChatMessage original, boolean async) {
      if (!msg.isEmpty() && this.player.getChatVisibility() != ChatVisiblity.HIDDEN) {
         if (this.player.getChatVisibility() != ChatVisiblity.SYSTEM) {
            if (!async && !Bukkit.isPrimaryThread()) {
               AsyncCatcher.catchOp("Asynchronous player chat is not allowed here");
            }

            new ChatProcessor(this.server, this.player, original, async).process();
         }
      }
   }

   @Deprecated
   public void handleCommand(String command) {
      if (command.startsWith("/")) {
         command = command.substring(1);
      }

      this.performUnsignedChatCommand(command);
   }

   private PlayerChatMessage getSignedMessage(ServerboundChatPacket packet, LastSeenMessages lastSeenMessages) throws DecodeException {
      SignedMessageBody body = new SignedMessageBody(packet.message(), packet.timeStamp(), packet.salt(), lastSeenMessages);
      return this.signedMessageDecoder.unpack(packet.signature(), body);
   }

   private void broadcastChatMessage(PlayerChatMessage message) {
      String rawMessage = message.signedContent();
      if (rawMessage.isEmpty()) {
         LOGGER.warn("{} tried to send an empty message", this.player.getScoreboardName());
      } else if (this.getCraftPlayer().isConversing()) {
         String conversationInput = rawMessage;
         this.server.processQueue.add(() -> this.getCraftPlayer().acceptConversationInput(conversationInput));
      } else if (this.player.getChatVisibility() == ChatVisiblity.SYSTEM) {
         this.send(new ClientboundSystemChatPacket(Component.translatable("chat.cannotSend").withStyle(ChatFormatting.RED), false));
      } else {
         this.chat(rawMessage, message, true);
      }

      this.detectChatRateSpam(rawMessage);
   }

   private void detectRateSpam(TickThrottler throttler, String message) {
      if (SpigotConfig.enableSpamExclusions) {
         for (String exclude : SpigotConfig.spamExclusions) {
            if (exclude != null && message.startsWith(exclude)) {
               return;
            }
         }
      }

      if (!throttler.isIncrementAndUnderThreshold()
         && !this.server.getPlayerList().isOp(this.player.nameAndId())
         && !this.server.isSingleplayerOwner(this.player.nameAndId())) {
         this.disconnectAsync(Component.translatable("disconnect.spam"), Cause.SPAM);
      }
   }

   private void detectCommandRateSpam(String command) {
      this.detectRateSpam(this.commandSpamThrottler, "/" + command);
   }

   private void detectChatRateSpam(String message) {
      this.detectRateSpam(this.chatSpamThrottler, message);
   }

   public void handleChatAck(ServerboundChatAckPacket packet) {
      synchronized (this.lastSeenMessages) {
         try {
            this.lastSeenMessages.applyOffset(packet.offset());
         } catch (ValidationException e) {
            LOGGER.error("Failed to validate message acknowledgement offset from {}: {}", this.player.getPlainTextName(), e.getMessage());
            this.disconnectAsync(CHAT_VALIDATION_FAILED, Cause.CHAT_VALIDATION_FAILED);
         }
      }
   }

   public void handleAnimate(ServerboundSwingPacket packet) {
      PacketUtils.ensureRunningOnSameThread(packet, this, this.player.level());
      if (!this.player.isImmobile()) {
         this.player.resetLastActionTime();
         Location origin = CraftLocation.toBukkit(this.player.getEyePosition(), this.player.level(), this.player.getYRot(), this.player.getXRot());
         double maxRange = Math.max(this.player.blockInteractionRange(), this.player.entityInteractionRange());
         RayTraceResult result = this.player
            .level()
            .getWorld()
            .rayTrace(
               origin,
               origin.getDirection(),
               maxRange,
               FluidCollisionMode.NEVER,
               false,
               0.0,
               entity -> {
                  Entity handle = ((CraftEntity)entity).getHandle();
                  return entity != this.player.getBukkitEntity()
                     && this.player.getBukkitEntity().canSee(entity)
                     && !handle.isSpectator()
                     && handle.isPickable()
                     && !handle.isPassengerOfSameVehicle(this.player);
               }
            );
         if (result == null) {
            CraftEventFactory.callPlayerInteractEvent(
               this.player, org.bukkit.event.block.Action.LEFT_CLICK_AIR, this.player.getInventory().getSelectedItem(), InteractionHand.MAIN_HAND
            );
         } else {
            GameType gameType = this.player.gameMode.getGameModeForPlayer();
            if (gameType == GameType.ADVENTURE && result.getHitBlock() != null) {
               CraftEventFactory.callPlayerInteractEvent(
                  this.player,
                  org.bukkit.event.block.Action.LEFT_CLICK_BLOCK,
                  ((CraftBlock)result.getHitBlock()).getPosition(),
                  CraftBlock.blockFaceToNotch(result.getHitBlockFace()),
                  this.player.getInventory().getSelectedItem(),
                  InteractionHand.MAIN_HAND
               );
            } else if (gameType != GameType.CREATIVE
               && result.getHitEntity() != null
               && origin.toVector().distanceSquared(result.getHitPosition()) > this.player.entityInteractionRange() * this.player.entityInteractionRange()) {
               CraftEventFactory.callPlayerInteractEvent(
                  this.player, org.bukkit.event.block.Action.LEFT_CLICK_AIR, this.player.getInventory().getSelectedItem(), InteractionHand.MAIN_HAND
               );
            }
         }

         PlayerArmSwingEvent event = new PlayerArmSwingEvent(this.getCraftPlayer(), CraftEquipmentSlot.getHand(packet.getHand()));
         this.cserver.getPluginManager().callEvent(event);
         if (!event.isCancelled()) {
            this.player.swing(packet.getHand());
         }
      }
   }

   public void handlePlayerCommand(ServerboundPlayerCommandPacket packet) {
      PacketUtils.ensureRunningOnSameThread(packet, this, this.player.level());
      if (this.hasClientLoaded()) {
         if (!this.player.isRemoved()) {
            switch (packet.getAction()) {
               case START_SPRINTING:
               case STOP_SPRINTING:
                  PlayerToggleSprintEvent event = new PlayerToggleSprintEvent(
                     this.getCraftPlayer(), packet.getAction() == net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket.Action.START_SPRINTING
                  );
                  this.cserver.getPluginManager().callEvent(event);
                  if (event.isCancelled()) {
                     return;
                  }
               default:
                  this.player.resetLastActionTime();
                  switch (packet.getAction()) {
                     case START_SPRINTING:
                        this.player.setSprinting(true);
                        return;
                     case STOP_SPRINTING:
                        this.player.setSprinting(false);
                        return;
                     case STOP_SLEEPING:
                        if (this.player.isSleeping()) {
                           this.player.stopSleepInBed(false, true);
                           this.awaitingPositionFromClient = this.player.position();
                        }

                        return;
                     case START_RIDING_JUMP:
                        if (this.player.getControlledVehicle() instanceof PlayerRideableJumping vehicle) {
                           int data = packet.getData();
                           if (vehicle.canJump() && data > 0) {
                              vehicle.handleStartJump(data);
                              return;
                           }
                        }

                        return;
                     case STOP_RIDING_JUMP:
                        if (this.player.getControlledVehicle() instanceof PlayerRideableJumping vehicle) {
                           vehicle.handleStopJump();
                        }

                        return;
                     case OPEN_INVENTORY:
                        if (this.player.getVehicle() instanceof HasCustomInventoryScreen vehicleWithInventory) {
                           vehicleWithInventory.openCustomInventoryScreen(this.player);
                        }

                        return;
                     case START_FALL_FLYING:
                        if (!this.player.tryToStartFallFlying()) {
                           this.player.stopFallFlying();
                        }

                        return;
                     default:
                        throw new IllegalArgumentException("Invalid client command!");
                  }
            }
         }
      }
   }

   public void sendPlayerChatMessage(PlayerChatMessage message, Bound chatType) {
      if (!this.getCraftPlayer().canSeePlayer(message.link().sender())) {
         this.sendDisguisedChatMessage(message.decoratedContent(), chatType);
      } else {
         synchronized (this.messageSignatureCache) {
            this.send(
               new ClientboundPlayerChatPacket(
                  this.nextChatIndex++,
                  message.link().sender(),
                  message.link().index(),
                  message.signature(),
                  message.signedBody().pack(this.messageSignatureCache),
                  message.unsignedContent(),
                  message.filterMask(),
                  chatType
               )
            );
            MessageSignature signature = message.signature();
            if (signature != null) {
               this.messageSignatureCache.push(message.signedBody(), message.signature());
               int trackedCount;
               synchronized (this.lastSeenMessages) {
                  this.lastSeenMessages.addPending(signature);
                  trackedCount = this.lastSeenMessages.trackedMessagesCount();
               }

               if (trackedCount > 4096) {
                  this.disconnectAsync(Component.translatable("multiplayer.disconnect.too_many_pending_chats"), Cause.TOO_MANY_PENDING_CHATS);
               }
            }
         }
      }
   }

   public void sendDisguisedChatMessage(Component content, Bound chatType) {
      this.send(new ClientboundDisguisedChatPacket(content, chatType));
   }

   public SocketAddress getRemoteAddress() {
      return this.connection.getRemoteAddress();
   }

   public SocketAddress getRawAddress() {
      return this.connection.channel.remoteAddress() == null
         ? new InetSocketAddress(InetAddress.getLoopbackAddress(), 0)
         : this.connection.channel.remoteAddress();
   }

   public void switchToConfig() {
      this.waitingForSwitchToConfig = true;
      this.removePlayerFromWorld();
      this.send(ClientboundStartConfigurationPacket.INSTANCE);
      this.connection.setupOutboundProtocol(ConfigurationProtocols.CLIENTBOUND);
   }

   public void handlePingRequest(ServerboundPingRequestPacket packet) {
      this.connection.send(new ClientboundPongResponsePacket(packet.getTime()));
   }

   public void handleAttack(ServerboundAttackPacket packet) {
      PacketUtils.ensureRunningOnSameThread(packet, this, this.player.level());
      if (!this.player.isImmobile()) {
         if (this.hasClientLoaded() && !this.player.isSpectator()) {
            ServerLevel level = this.player.level();
            Entity target = level.getEntityOrPart(packet.entityId());
            if (target == this.player) {
               this.disconnect(Component.literal("Cannot interact with self!"), Cause.SELF_INTERACTION);
               return;
            }

            this.player.resetLastActionTime();
            if (target != null && level.getWorldBorder().isWithinBounds(target.blockPosition())) {
               AABB targetBounds = target.getBoundingBox();
               ItemStack mainHandItem = this.player.getMainHandItem();
               if (this.player.isWithinAttackRange(mainHandItem, targetBounds, GlobalConfiguration.get().misc.clientInteractionLeniencyDistance.or(3.0))
                  && !mainHandItem.has(DataComponents.PIERCING_WEAPON)) {
                  if (target instanceof ItemEntity
                     || target instanceof ExperienceOrb
                     || target == this.player
                     || target instanceof AbstractArrow abstractArrow && !abstractArrow.isAttackable()) {
                     this.disconnect(Component.translatable("multiplayer.disconnect.invalid_entity_attacked"), Cause.INVALID_ENTITY_ATTACKED);
                     LOGGER.warn("Player {} tried to attack an invalid entity", this.player.getPlainTextName());
                  } else if (mainHandItem.isItemEnabled(level.enabledFeatures()) && !this.player.cannotAttackWithItem(mainHandItem, 5)) {
                     this.player.attack(target);
                  }
               }
            } else if (target == null) {
               CraftEventFactory.callPlayerUseUnknownEntityEvent(this.player, packet.entityId(), true, InteractionHand.MAIN_HAND, null);
            }

            if (GlobalConfiguration.get().unsupportedSettings.updateEquipmentOnPlayerActions) {
               this.player.detectEquipmentUpdates();
            }
         }
      }
   }

   public void handleInteract(ServerboundInteractPacket packet) {
      PacketUtils.ensureRunningOnSameThread(packet, this, this.player.level());
      if (!this.player.isImmobile()) {
         if (this.hasClientLoaded()) {
            ServerLevel level = this.player.level();
            Entity target = level.getEntityOrPart(packet.entityId());
            if (target == this.player && !this.player.isSpectator()) {
               this.disconnect(Component.literal("Cannot interact with self!"), Cause.SELF_INTERACTION);
               return;
            }

            this.player.resetLastActionTime();
            this.player.setShiftKeyDown(packet.usingSecondaryAction());
            if (target != null && level.getWorldBorder().isWithinBounds(target.blockPosition())) {
               AABB targetBounds = target.getBoundingBox();
               if (this.player.isWithinEntityInteractionRange(targetBounds, GlobalConfiguration.get().misc.clientInteractionLeniencyDistance.or(3.0))) {
                  InteractionHand hand = packet.hand();
                  Vec3 location = packet.location();
                  ItemStack tool = this.player.getItemInHand(hand);
                  if (tool.isItemEnabled(level.enabledFeatures())) {
                     ItemStack usedItemStack = tool.copy();
                     Item itemType = tool.getItem();
                     boolean triggerLeashUpdate = usedItemStack.is(Items.LEAD) && target instanceof Leashable;
                     PlayerInteractAtEntityEvent event = new PlayerInteractAtEntityEvent(
                        this.getCraftPlayer(), target.getBukkitEntity(), CraftVector.toBukkit(location), CraftEquipmentSlot.getHand(hand)
                     );
                     this.cserver.getPluginManager().callEvent(event);
                     boolean resendData = event.isCancelled() || !this.player.getItemInHand(hand).is(itemType);
                     if (itemType == Items.WATER_BUCKET && target instanceof Bucketable && target instanceof LivingEntity && resendData) {
                        target.resendPossiblyDesyncedEntityData(this.player);
                     }

                     if (resendData) {
                        if (MCUtil.clientPredictsInteraction(this.player, target, tool)) {
                           this.player.containerMenu.sendAllDataToRemote();
                        } else {
                           this.player.containerMenu.forceHeldSlot(hand);
                        }
                     }

                     if (triggerLeashUpdate && resendData) {
                        this.send(new ClientboundSetEntityLinkPacket(target, ((Leashable)target).getLeashHolder()));
                     }

                     if (resendData) {
                        target.refreshEntityData(this.player);
                        if (!(target instanceof Allay) && !(target instanceof AbstractHorse)) {
                           this.player.containerMenu.forceHeldSlot(hand);
                        } else {
                           this.send(
                              new ClientboundSetEquipmentPacket(
                                 target.getId(),
                                 Arrays.stream(EquipmentSlot.values())
                                    .map(slot -> Pair.of(slot, ((LivingEntity)target).getItemBySlot(slot).copy()))
                                    .collect(Collectors.toList()),
                                 true
                              )
                           );
                           this.player.containerMenu.sendAllDataToRemote();
                        }
                     }

                     if (event.isCancelled()) {
                        return;
                     }

                     if (this.player.interactOn(target, hand, location) instanceof Success success) {
                        ItemStack awardedForStack = success.wasItemInteraction() ? usedItemStack : ItemStack.EMPTY;
                        CriteriaTriggers.PLAYER_INTERACTED_WITH_ENTITY.trigger(this.player, awardedForStack, target);
                        if (success.swingSource() == SwingSource.SERVER) {
                           this.player.swing(hand, true);
                        }
                     }
                  }
               }
            } else if (target == null) {
               CraftEventFactory.callPlayerUseUnknownEntityEvent(this.player, packet.entityId(), false, packet.hand(), packet.location());
            }

            if (GlobalConfiguration.get().unsupportedSettings.updateEquipmentOnPlayerActions) {
               this.player.detectEquipmentUpdates();
            }
         }
      }
   }

   public void handleSpectatorAction(ServerboundSpectatorActionPacket packet) {
      PacketUtils.ensureRunningOnSameThread(packet, this, this.player.level());
      if (this.hasClientLoaded() && this.player.isSpectator()) {
         this.player.resetLastActionTime();
         if (packet.spectateEntityId().isPresent()) {
            ServerLevel level = this.player.level();
            Entity target = level.getEntityOrPart(packet.spectateEntityId().getAsInt());
            if (target != null
               && level.getWorldBorder().isWithinBounds(target.blockPosition())
               && this.player.isWithinEntityInteractionRange(target.getBoundingBox(), GlobalConfiguration.get().misc.clientInteractionLeniencyDistance.or(3.0))
               && target.isPickable()) {
               this.player.setCamera(target);
            }
         }
      }
   }

   public void handleClientCommand(ServerboundClientCommandPacket packet) {
      PacketUtils.ensureRunningOnSameThread(packet, this, this.player.level());
      this.player.resetLastActionTime();
      net.minecraft.network.protocol.game.ServerboundClientCommandPacket.Action action = packet.getAction();
      switch (action) {
         case PERFORM_RESPAWN:
            if (this.player.wonGame) {
               this.player.wonGame = false;
               this.player = this.server.getPlayerList().respawn(this.player, true, RemovalReason.CHANGED_DIMENSION, RespawnReason.END_PORTAL);
               this.resetPosition();
               this.restartClientLoadTimerAfterRespawn();
               CriteriaTriggers.CHANGED_DIMENSION.trigger(this.player, Level.END, Level.OVERWORLD);
            } else {
               if (this.player.getHealth() > 0.0F) {
                  return;
               }

               this.player = this.server.getPlayerList().respawn(this.player, false, RemovalReason.KILLED, RespawnReason.DEATH);
               this.resetPosition();
               this.restartClientLoadTimerAfterRespawn();
               if (this.server.isHardcore()) {
                  this.player.setGameMode(GameType.SPECTATOR, org.bukkit.event.player.PlayerGameModeChangeEvent.Cause.HARDCORE_DEATH, null);
               }
            }
            break;
         case REQUEST_STATS:
            this.player.getStats().sendStats(this.player);
            break;
         case REQUEST_GAMERULE_VALUES:
            this.sendGameRuleValues();
      }
   }

   private void sendGameRuleValues() {
      if (!this.player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER)
         && !this.player.getBukkitEntity().hasPermission("minecraft.command.gamerule")) {
         LOGGER.warn("Player {} tried to request game rule values without required permissions", this.player.getGameProfile().name());
      } else {
         GameRules gameRules = this.player.level().getGameRules();
         Map<ResourceKey<GameRule<?>>, String> values = new HashMap<>();
         gameRules.availableRules().forEach(rule -> addGameRuleValue(gameRules, values, rule));
         this.send(new ClientboundGameRuleValuesPacket(values));
      }
   }

   private static <T> void addGameRuleValue(GameRules gameRules, Map<ResourceKey<GameRule<?>>, String> values, GameRule<T> rule) {
      BuiltInRegistries.GAME_RULE.getResourceKey(rule).ifPresent(key -> values.put((ResourceKey<GameRule<?>>)key, rule.serialize(gameRules.get(rule))));
   }

   public void handleContainerClose(ServerboundContainerClosePacket packet) {
      this.handleContainerClose(packet, Reason.PLAYER);
   }

   public void handleContainerClose(ServerboundContainerClosePacket packet, Reason reason) {
      PacketUtils.ensureRunningOnSameThread(packet, this, this.player.level());
      if (!this.player.isImmobile()) {
         CraftEventFactory.handleInventoryCloseEvent(this.player, reason);
         this.player.doCloseContainer();
      }
   }

   public void handleContainerClick(ServerboundContainerClickPacket packet) {
      PacketUtils.ensureRunningOnSameThread(packet, this, this.player.level());
      if (!this.player.isImmobile()) {
         this.player.resetLastActionTime();
         if (this.player.containerMenu.containerId == packet.containerId() && this.player.containerMenu.stillValid(this.player)) {
            boolean cancelled = this.player.isSpectator();
            if (this.player.isDeadOrDying()) {
               this.player.containerMenu.sendAllDataToRemote();
            } else if (!this.player.containerMenu.stillValid(this.player)) {
               LOGGER.debug("Player {} interacted with invalid menu {}", this.player, this.player.containerMenu);
            } else {
               int slotIndex = packet.slotNum();
               if (!this.player.containerMenu.isValidSlotIndex(slotIndex)) {
                  LOGGER.debug(
                     "Player {} clicked invalid slot index: {}, available slots: {}",
                     new Object[]{this.player.getPlainTextName(), slotIndex, this.player.containerMenu.slots.size()}
                  );
               } else {
                  boolean fullResyncNeeded = packet.stateId() != this.player.containerMenu.getStateId();
                  this.player.containerMenu.suppressRemoteUpdates();
                  if (slotIndex < -1 && slotIndex != -999) {
                     return;
                  }

                  InventoryView inventory = this.player.containerMenu.getBukkitView();
                  SlotType type = inventory.getSlotType(slotIndex);
                  ClickType click = ClickType.UNKNOWN;
                  InventoryAction action = InventoryAction.UNKNOWN;
                  switch (packet.containerInput()) {
                     case PICKUP:
                        if (packet.buttonNum() == 0) {
                           click = ClickType.LEFT;
                        } else if (packet.buttonNum() == 1) {
                           click = ClickType.RIGHT;
                        }

                        if (packet.buttonNum() == 0 || packet.buttonNum() == 1) {
                           action = InventoryAction.NOTHING;
                           if (slotIndex == -999) {
                              if (!this.player.containerMenu.getCarried().isEmpty()) {
                                 action = packet.buttonNum() == 0 ? InventoryAction.DROP_ALL_CURSOR : InventoryAction.DROP_ONE_CURSOR;
                              }
                           } else if (slotIndex < 0) {
                              action = InventoryAction.NOTHING;
                           } else {
                              Slot slot = this.player.containerMenu.getSlot(slotIndex);
                              if (slot != null) {
                                 ItemStack clickedItem = slot.getItem();
                                 ItemStack cursor = this.player.containerMenu.getCarried();
                                 if (clickedItem.isEmpty()) {
                                    if (!cursor.isEmpty()) {
                                       if (cursor.getItem() instanceof BundleItem && cursor.has(DataComponents.BUNDLE_CONTENTS) && packet.buttonNum() != 0) {
                                          action = ((BundleContents)cursor.get(DataComponents.BUNDLE_CONTENTS)).isEmpty()
                                             ? InventoryAction.NOTHING
                                             : InventoryAction.PLACE_FROM_BUNDLE;
                                       } else {
                                          action = packet.buttonNum() == 0 ? InventoryAction.PLACE_ALL : InventoryAction.PLACE_ONE;
                                       }
                                    }
                                 } else if (slot.mayPickup(this.player)) {
                                    if (cursor.isEmpty()) {
                                       if (slot.getItem().getItem() instanceof BundleItem
                                          && slot.getItem().has(DataComponents.BUNDLE_CONTENTS)
                                          && packet.buttonNum() != 0) {
                                          action = ((BundleContents)slot.getItem().get(DataComponents.BUNDLE_CONTENTS)).isEmpty()
                                             ? InventoryAction.NOTHING
                                             : InventoryAction.PICKUP_FROM_BUNDLE;
                                       } else {
                                          action = packet.buttonNum() == 0 ? InventoryAction.PICKUP_ALL : InventoryAction.PICKUP_HALF;
                                       }
                                    } else if (slot.mayPlace(cursor)) {
                                       if (ItemStack.isSameItemSameComponents(clickedItem, cursor)) {
                                          int toPlace = packet.buttonNum() == 0 ? cursor.getCount() : 1;
                                          toPlace = Math.min(toPlace, clickedItem.getMaxStackSize() - clickedItem.getCount());
                                          toPlace = Math.min(toPlace, slot.container.getMaxStackSize() - clickedItem.getCount());
                                          if (toPlace == 1) {
                                             action = InventoryAction.PLACE_ONE;
                                          } else if (toPlace == cursor.getCount()) {
                                             action = InventoryAction.PLACE_ALL;
                                          } else if (toPlace < 0) {
                                             action = toPlace != -1 ? InventoryAction.PICKUP_SOME : InventoryAction.PICKUP_ONE;
                                          } else if (toPlace != 0) {
                                             action = InventoryAction.PLACE_SOME;
                                          }
                                       } else if (cursor.getCount() <= slot.getMaxStackSize()) {
                                          if (cursor.getItem() instanceof BundleItem && cursor.has(DataComponents.BUNDLE_CONTENTS) && packet.buttonNum() == 0) {
                                             int toPickup = ((BundleContents)cursor.get(DataComponents.BUNDLE_CONTENTS)).getMaxAmountToAdd(slot.getItem());
                                             if (toPickup >= slot.getItem().getCount()) {
                                                action = InventoryAction.PICKUP_ALL_INTO_BUNDLE;
                                             } else if (toPickup == 0) {
                                                action = InventoryAction.NOTHING;
                                             } else {
                                                action = InventoryAction.PICKUP_SOME_INTO_BUNDLE;
                                             }
                                          } else if (slot.getItem().getItem() instanceof BundleItem
                                             && slot.getItem().has(DataComponents.BUNDLE_CONTENTS)
                                             && packet.buttonNum() == 0) {
                                             int toPickup = ((BundleContents)slot.getItem().get(DataComponents.BUNDLE_CONTENTS)).getMaxAmountToAdd(cursor);
                                             if (toPickup >= cursor.getCount()) {
                                                action = InventoryAction.PLACE_ALL_INTO_BUNDLE;
                                             } else if (toPickup == 0) {
                                                action = InventoryAction.NOTHING;
                                             } else {
                                                action = InventoryAction.PLACE_SOME_INTO_BUNDLE;
                                             }
                                          } else {
                                             action = InventoryAction.SWAP_WITH_CURSOR;
                                          }
                                       }
                                    } else if (ItemStack.isSameItemSameComponents(cursor, clickedItem)
                                       && clickedItem.getCount() >= 0
                                       && clickedItem.getCount() + cursor.getCount() <= cursor.getMaxStackSize()) {
                                       action = InventoryAction.PICKUP_ALL;
                                    }
                                 }
                              }
                           }
                        }
                        break;
                     case QUICK_MOVE:
                        if (packet.buttonNum() == 0) {
                           click = ClickType.SHIFT_LEFT;
                        } else if (packet.buttonNum() == 1) {
                           click = ClickType.SHIFT_RIGHT;
                        }

                        if (packet.buttonNum() == 0 || packet.buttonNum() == 1) {
                           if (slotIndex < 0) {
                              action = InventoryAction.NOTHING;
                           } else {
                              Slot slot = this.player.containerMenu.getSlot(slotIndex);
                              if (slot != null && slot.mayPickup(this.player) && slot.hasItem()) {
                                 action = InventoryAction.MOVE_TO_OTHER_INVENTORY;
                              } else {
                                 action = InventoryAction.NOTHING;
                              }
                           }
                        }
                        break;
                     case SWAP:
                        if (packet.buttonNum() >= 0 && packet.buttonNum() < 9 || packet.buttonNum() == 40) {
                           if (slotIndex < 0) {
                              action = InventoryAction.NOTHING;
                           } else {
                              click = packet.buttonNum() == 40 ? ClickType.SWAP_OFFHAND : ClickType.NUMBER_KEY;
                              Slot clickedSlot = this.player.containerMenu.getSlot(slotIndex);
                              if (clickedSlot.mayPickup(this.player)) {
                                 ItemStack hotbar = this.player.getInventory().getItem(packet.buttonNum());
                                 if ((hotbar.isEmpty() || !clickedSlot.mayPlace(hotbar)) && (!hotbar.isEmpty() || !clickedSlot.hasItem())) {
                                    action = InventoryAction.NOTHING;
                                 } else {
                                    action = InventoryAction.HOTBAR_SWAP;
                                 }
                              } else {
                                 action = InventoryAction.NOTHING;
                              }
                           }
                        }
                        break;
                     case CLONE:
                        if (packet.buttonNum() == 2) {
                           click = ClickType.MIDDLE;
                           if (slotIndex < 0) {
                              action = InventoryAction.NOTHING;
                           } else {
                              Slot slot = this.player.containerMenu.getSlot(slotIndex);
                              if (slot != null && slot.hasItem() && this.player.getAbilities().instabuild && this.player.containerMenu.getCarried().isEmpty()) {
                                 action = InventoryAction.CLONE_STACK;
                              } else {
                                 action = InventoryAction.NOTHING;
                              }
                           }
                        } else {
                           click = ClickType.UNKNOWN;
                           action = InventoryAction.UNKNOWN;
                        }
                        break;
                     case THROW:
                        if (slotIndex >= 0) {
                           if (packet.buttonNum() == 0) {
                              click = ClickType.DROP;
                              Slot slot = this.player.containerMenu.getSlot(slotIndex);
                              if (slot != null
                                 && slot.hasItem()
                                 && slot.mayPickup(this.player)
                                 && !slot.getItem().isEmpty()
                                 && slot.getItem().getItem() != Items.AIR) {
                                 action = InventoryAction.DROP_ONE_SLOT;
                              } else {
                                 action = InventoryAction.NOTHING;
                              }
                           } else if (packet.buttonNum() == 1) {
                              click = ClickType.CONTROL_DROP;
                              Slot slot = this.player.containerMenu.getSlot(slotIndex);
                              if (slot != null
                                 && slot.hasItem()
                                 && slot.mayPickup(this.player)
                                 && !slot.getItem().isEmpty()
                                 && slot.getItem().getItem() != Items.AIR) {
                                 action = InventoryAction.DROP_ALL_SLOT;
                              } else {
                                 action = InventoryAction.NOTHING;
                              }
                           }
                        } else {
                           click = ClickType.LEFT;
                           if (packet.buttonNum() == 1) {
                              click = ClickType.RIGHT;
                           }

                           action = InventoryAction.NOTHING;
                        }
                        break;
                     case QUICK_CRAFT:
                        AbstractContainerMenu containerMenu = this.player.containerMenu;
                        int currentStatus = this.player.containerMenu.quickcraftStatus;
                        int newStatus = AbstractContainerMenu.getQuickcraftHeader(packet.buttonNum());
                        if (currentStatus == 1
                           && (newStatus == 2 || currentStatus == newStatus)
                           && !containerMenu.getCarried().isEmpty()
                           && newStatus != 0
                           && newStatus != 1
                           && newStatus == 2
                           && !this.player.containerMenu.quickcraftSlots.isEmpty()
                           && this.player.containerMenu.quickcraftSlots.size() == 1) {
                           int index = ((Slot)containerMenu.quickcraftSlots.iterator().next()).index;
                           containerMenu.resetQuickCraft();
                           this.handleContainerClick(
                              new ServerboundContainerClickPacket(
                                 packet.containerId(),
                                 packet.stateId(),
                                 (short)index,
                                 (byte)containerMenu.quickcraftType,
                                 ContainerInput.PICKUP,
                                 packet.changedSlots(),
                                 packet.carriedItem()
                              )
                           );
                           return;
                        }

                        this.player.containerMenu.clicked(slotIndex, packet.buttonNum(), packet.containerInput(), this.player);
                        break;
                     case PICKUP_ALL:
                        click = ClickType.DOUBLE_CLICK;
                        action = InventoryAction.NOTHING;
                        if (slotIndex >= 0 && !this.player.containerMenu.getCarried().isEmpty()) {
                           ItemStack cursor = this.player.containerMenu.getCarried();
                           action = InventoryAction.NOTHING;
                           if (inventory.getTopInventory().contains(CraftItemType.minecraftToBukkit(cursor.getItem()))
                              || inventory.getBottomInventory().contains(CraftItemType.minecraftToBukkit(cursor.getItem()))) {
                              action = InventoryAction.COLLECT_TO_CURSOR;
                           }
                        }
                  }

                  if (packet.containerInput() != ContainerInput.QUICK_CRAFT) {
                     if (this.player.containerMenu.quickcraftStatus != 0) {
                        action = InventoryAction.NOTHING;
                     }

                     InventoryClickEvent event;
                     if (click == ClickType.NUMBER_KEY) {
                        event = new InventoryClickEvent(inventory, type, slotIndex, click, action, packet.buttonNum());
                     } else {
                        event = new InventoryClickEvent(inventory, type, slotIndex, click, action);
                     }

                     org.bukkit.inventory.Inventory top = inventory.getTopInventory();
                     if (slotIndex == 0 && top instanceof CraftingInventory craftingInv) {
                        Recipe recipe = craftingInv.getRecipe();
                        if (recipe != null) {
                           if (click == ClickType.NUMBER_KEY) {
                              event = new CraftItemEvent(recipe, inventory, type, slotIndex, click, action, packet.buttonNum());
                           } else {
                              event = new CraftItemEvent(recipe, inventory, type, slotIndex, click, action);
                           }
                        }
                     }

                     if (slotIndex == 3 && top instanceof SmithingInventory smithingInv) {
                        org.bukkit.inventory.ItemStack result = smithingInv.getResult();
                        if (result != null) {
                           if (click == ClickType.NUMBER_KEY) {
                              event = new SmithItemEvent(inventory, type, slotIndex, click, action, packet.buttonNum());
                           } else {
                              event = new SmithItemEvent(inventory, type, slotIndex, click, action);
                           }
                        }
                     }

                     if (slotIndex == 2 && top instanceof CartographyInventory cartographyInventory) {
                        org.bukkit.inventory.ItemStack result = cartographyInventory.getResult();
                        if (result != null && !result.isEmpty()) {
                           if (click == ClickType.NUMBER_KEY) {
                              event = new CartographyItemEvent(inventory, type, slotIndex, click, action, packet.buttonNum());
                           } else {
                              event = new CartographyItemEvent(inventory, type, slotIndex, click, action);
                           }
                        }
                     }

                     event.setCancelled(cancelled);
                     AbstractContainerMenu oldContainer = this.player.containerMenu;
                     this.cserver.getPluginManager().callEvent(event);
                     if (this.player.containerMenu != oldContainer) {
                        this.player.containerMenu.resumeRemoteUpdates();
                        this.player.containerMenu.broadcastFullState();
                        return;
                     }

                     if (event.getResult() != Result.DENY) {
                        this.player.containerMenu.clicked(slotIndex, packet.buttonNum(), packet.containerInput(), this.player);
                     }

                     if (event instanceof CraftItemEvent || event instanceof SmithItemEvent) {
                        this.player.containerMenu.sendAllDataToRemote();
                     }
                  }

                  ObjectIterator var21 = Int2ObjectMaps.fastIterable(packet.changedSlots()).iterator();

                  while (var21.hasNext()) {
                     it.unimi.dsi.fastutil.ints.Int2ObjectMap.Entry<HashedStack> e = (it.unimi.dsi.fastutil.ints.Int2ObjectMap.Entry<HashedStack>)var21.next();
                     this.player.containerMenu.setRemoteSlotUnsafe(e.getIntKey(), (HashedStack)e.getValue());
                  }

                  this.player.containerMenu.setRemoteCarried(packet.carriedItem());
                  this.player.containerMenu.resumeRemoteUpdates();
                  if (fullResyncNeeded) {
                     this.player.containerMenu.broadcastFullState();
                  } else {
                     this.player.containerMenu.broadcastChanges();
                  }

                  if (packet.buttonNum() == 40 && this.player.containerMenu != this.player.inventoryMenu) {
                     this.player.containerSynchronizer.sendOffHandSlotChange();
                  }

                  if (GlobalConfiguration.get().unsupportedSettings.updateEquipmentOnPlayerActions) {
                     this.player.detectEquipmentUpdates();
                  }
               }
            }
         }
      }
   }

   public void handlePlaceRecipe(ServerboundPlaceRecipePacket packet) {
      if (!Bukkit.isPrimaryThread() && !this.recipeSpamPackets.isIncrementAndUnderThreshold()) {
         this.disconnectAsync(Component.translatable("disconnect.spam"), Cause.SPAM);
      } else {
         PacketUtils.ensureRunningOnSameThread(packet, this, this.player.level());
         this.player.resetLastActionTime();
         if (!this.player.isSpectator() && this.player.containerMenu.containerId == packet.containerId()) {
            if (!this.player.containerMenu.stillValid(this.player)) {
               LOGGER.debug("Player {} interacted with invalid menu {}", this.player, this.player.containerMenu);
            } else {
               ServerDisplayInfo displayInfo = this.server.getRecipeManager().getRecipeFromDisplay(packet.recipe());
               if (displayInfo != null) {
                  RecipeHolder<?> recipe = displayInfo.parent();
                  if (this.player.getRecipeBook().contains(recipe.id()) && this.player.containerMenu instanceof RecipeBookMenu recipeBookMenu) {
                     if (recipe.value().placementInfo().isImpossibleToPlace()) {
                        LOGGER.debug("Player {} tried to place impossible recipe {}", this.player, recipe.id().identifier());
                        return;
                     }

                     NamespacedKey recipeName = CraftNamespacedKey.fromMinecraft(recipe.id().identifier());
                     boolean makeAll = packet.useMaxItems();
                     PlayerRecipeBookClickEvent paperEvent = new PlayerRecipeBookClickEvent(this.player.getBukkitEntity(), recipeName, makeAll);
                     if (!paperEvent.callEvent()) {
                        return;
                     }

                     recipeName = paperEvent.getRecipe();
                     makeAll = paperEvent.isMakeAll();
                     if (org.bukkit.event.player.PlayerRecipeBookClickEvent.getHandlerList().getRegisteredListeners().length > 0) {
                        Recipe bukkitRecipe = this.cserver.getRecipe(recipeName);
                        if (bukkitRecipe == null) {
                           return;
                        }

                        org.bukkit.event.player.PlayerRecipeBookClickEvent event = CraftEventFactory.callRecipeBookClickEvent(
                           this.player, bukkitRecipe, makeAll
                        );
                        recipeName = ((Keyed)event.getRecipe()).getKey();
                        makeAll = event.isShiftClick();
                     }

                     if (!(this.player.containerMenu instanceof RecipeBookMenu)) {
                        return;
                     }

                     recipe = (RecipeHolder<?>)this.server
                        .getRecipeManager()
                        .byKey(ResourceKey.create(Registries.RECIPE, CraftNamespacedKey.toMinecraft(recipeName)))
                        .orElse(null);
                     if (recipe == null) {
                        return;
                     }

                     PostPlaceAction postPlaceAction = recipeBookMenu.handlePlacement(
                        makeAll, this.player.isCreative(), recipe, this.player.level(), this.player.getInventory()
                     );
                     if (postPlaceAction == PostPlaceAction.PLACE_GHOST_RECIPE) {
                        this.send(new ClientboundPlaceGhostRecipePacket(this.player.containerMenu.containerId, displayInfo.display().display()));
                     }
                  }
               }
            }
         }
      }
   }

   public void handleContainerButtonClick(ServerboundContainerButtonClickPacket packet) {
      PacketUtils.ensureRunningOnSameThread(packet, this, this.player.level());
      if (!this.player.isImmobile()) {
         this.player.resetLastActionTime();
         if (this.player.containerMenu.containerId == packet.containerId() && !this.player.isSpectator()) {
            if (!this.player.containerMenu.stillValid(this.player)) {
               LOGGER.debug("Player {} interacted with invalid menu {}", this.player, this.player.containerMenu);
            } else {
               boolean clickAccepted = this.player.containerMenu.clickMenuButton(this.player, packet.buttonId());
               if (clickAccepted) {
                  this.player.containerMenu.broadcastChanges();
               }

               if (GlobalConfiguration.get().unsupportedSettings.updateEquipmentOnPlayerActions) {
                  this.player.detectEquipmentUpdates();
               }
            }
         }
      }
   }

   public void handleSetCreativeModeSlot(ServerboundSetCreativeModeSlotPacket packet) {
      PacketUtils.ensureRunningOnSameThread(packet, this, this.player.level());
      if (this.player.hasInfiniteMaterials()) {
         boolean drop = packet.slotNum() < 0;
         ItemStack itemStack = packet.itemStack();
         if (!itemStack.isItemEnabled(this.player.level().enabledFeatures())) {
            return;
         }

         boolean validSlot = packet.slotNum() >= 1 && packet.slotNum() <= 45;
         boolean validData = itemStack.isEmpty() || itemStack.getCount() <= itemStack.getMaxStackSize();
         if (drop || validSlot && !ItemStack.matches(this.player.inventoryMenu.getSlot(packet.slotNum()).getItem(), packet.itemStack())) {
            InventoryView inventory = this.player.inventoryMenu.getBukkitView();
            org.bukkit.inventory.ItemStack item = CraftItemStack.asBukkitCopy(packet.itemStack());
            SlotType type = SlotType.QUICKBAR;
            if (drop) {
               type = SlotType.OUTSIDE;
            } else if (packet.slotNum() < 36) {
               if (packet.slotNum() >= 5 && packet.slotNum() < 9) {
                  type = SlotType.ARMOR;
               } else {
                  type = SlotType.CONTAINER;
               }
            }

            InventoryCreativeEvent event = new InventoryCreativeEvent(inventory, type, drop ? -999 : packet.slotNum(), item);
            this.cserver.getPluginManager().callEvent(event);
            itemStack = CraftItemStack.asNMSCopy(event.getCursor());
            switch (event.getResult()) {
               case ALLOW:
                  validData = true;
               case DEFAULT:
               default:
                  break;
               case DENY:
                  if (packet.slotNum() >= 0) {
                     this.player
                        .connection
                        .send(
                           new ClientboundContainerSetSlotPacket(
                              this.player.inventoryMenu.containerId,
                              this.player.inventoryMenu.incrementStateId(),
                              packet.slotNum(),
                              this.player.inventoryMenu.getSlot(packet.slotNum()).getItem()
                           )
                        );
                     this.player.connection.send(new ClientboundSetCursorItemPacket(ItemStack.EMPTY.copy()));
                  }

                  return;
            }
         }

         if (validSlot && validData) {
            this.player.inventoryMenu.getSlot(packet.slotNum()).setByPlayer(itemStack);
            this.player.inventoryMenu.setRemoteSlot(packet.slotNum(), itemStack);
            this.player.inventoryMenu.broadcastChanges();
            if (GlobalConfiguration.get().unsupportedSettings.updateEquipmentOnPlayerActions) {
               this.player.detectEquipmentUpdates();
            }
         } else if (drop && validData) {
            if (this.dropSpamThrottler.isUnderThreshold()) {
               this.dropSpamThrottler.increment();
               this.player.drop(itemStack, true);
            } else {
               LOGGER.warn("Player {} was dropping items too fast in creative mode, ignoring.", this.player.getPlainTextName());
            }
         }
      }
   }

   public void handleSignUpdate(ServerboundSignUpdatePacket packet) {
      String[] linesArray = packet.getLines();

      for (int i = 0; i < linesArray.length; i++) {
         if (MAX_SIGN_LINE_LENGTH > 0 && linesArray[i].length() > MAX_SIGN_LINE_LENGTH) {
            int offset = linesArray[i].codePoints().limit(MAX_SIGN_LINE_LENGTH).map(Character::charCount).sum();
            if (offset < linesArray[i].length()) {
               linesArray[i] = linesArray[i].substring(0, offset);
            }
         }
      }

      List<String> lines = Stream.of(linesArray).<String>map(ChatFormatting::stripFormatting).collect(Collectors.toList());
      this.filterTextPacket(lines).thenAcceptAsync(filteredLines -> this.updateSignText(packet, (List<FilteredText>)filteredLines), this.server);
   }

   private void updateSignText(ServerboundSignUpdatePacket packet, List<FilteredText> lines) {
      if (!this.player.isImmobile()) {
         this.player.resetLastActionTime();
         ServerLevel level = this.player.level();
         BlockPos pos = packet.getPos();
         if (level.hasChunkAt(pos)) {
            if (!new UncheckedSignChangeEvent(
                  this.player.getBukkitEntity(),
                  MCUtil.toPosition(pos),
                  packet.isFrontText() ? Side.FRONT : Side.BACK,
                  lines.stream().map(line -> net.kyori.adventure.text.Component.text(line.raw())).toList()
               )
               .callEvent()) {
               return;
            }

            if (!(level.getBlockEntity(pos) instanceof SignBlockEntity sign)) {
               return;
            }

            sign.updateSignText(this.player, packet.isFrontText(), lines);
         }
      }
   }

   public void handlePlayerAbilities(ServerboundPlayerAbilitiesPacket packet) {
      PacketUtils.ensureRunningOnSameThread(packet, this, this.player.level());
      if (this.player.getAbilities().mayfly && this.player.getAbilities().flying != packet.isFlying()) {
         PlayerToggleFlightEvent event = new PlayerToggleFlightEvent(this.player.getBukkitEntity(), packet.isFlying());
         this.cserver.getPluginManager().callEvent(event);
         if (!event.isCancelled()) {
            this.player.getAbilities().flying = packet.isFlying();
         } else {
            this.player.onUpdateAbilities();
         }
      }
   }

   public void handleClientInformation(ServerboundClientInformationPacket packet) {
      PacketUtils.ensureRunningOnSameThread(packet, this, this.player.level());
      if (packet.information().viewDistance() < 0) {
         LOGGER.warn("Disconnecting {} for invalid view distance: {}", this.player.getScoreboardName(), packet.information().viewDistance());
         this.disconnect(Component.literal("Invalid client settings"), Cause.ILLEGAL_ACTION);
      } else {
         boolean wasHatShown = this.player.isModelPartShown(PlayerModelPart.HAT);
         this.player.updateOptions(packet.information());
         this.connection.channel.attr(PaperAdventure.LOCALE_ATTRIBUTE).set(Translator.parseLocale(packet.information().language()));
         if (this.player.isModelPartShown(PlayerModelPart.HAT) != wasHatShown) {
            this.server.getPlayerList().broadcastAll(new ClientboundPlayerInfoUpdatePacket(ClientboundPlayerInfoUpdatePacket.Action.UPDATE_HAT, this.player));
         }
      }
   }

   public void handleChangeDifficulty(ServerboundChangeDifficultyPacket packet) {
      PacketUtils.ensureRunningOnSameThread(packet, this, this.player.level());
      if (!this.player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER) && !this.isSingleplayerOwner()) {
         LOGGER.warn(
            "Player {} tried to change difficulty to {} without required permissions",
            this.player.getGameProfile().name(),
            packet.difficulty().getDisplayName()
         );
      }
   }

   public void handleChangeGameMode(ServerboundChangeGameModePacket packet) {
      PacketUtils.ensureRunningOnSameThread(packet, this, this.player.level());
      if (!GameModeCommand.PERMISSION_CHECK.check(this.player.permissions()) && !this.player.getBukkitEntity().hasPermission("minecraft.command.gamemode")) {
         LOGGER.warn(
            "Player {} tried to change game mode to {} without required permissions",
            this.player.getGameProfile().name(),
            packet.mode().getShortDisplayName().getString()
         );
      } else {
         GameModeCommand.setGameMode(
            this.player.createCommandSourceStack(), this.player, packet.mode(), org.bukkit.event.player.PlayerGameModeChangeEvent.Cause.GAMEMODE_SWITCHER
         );
      }
   }

   public void handleLockDifficulty(ServerboundLockDifficultyPacket packet) {
      PacketUtils.ensureRunningOnSameThread(packet, this, this.player.level());
      if (this.player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER) || this.isSingleplayerOwner()) {
         this.server.setDifficultyLocked(packet.isLocked());
      }
   }

   public void handleChatSessionUpdate(ServerboundChatSessionUpdatePacket packet) {
      PacketUtils.ensureRunningOnSameThread(packet, this, this.player.level());
      Data newChatSession = packet.chatSession();
      net.minecraft.world.entity.player.ProfilePublicKey.Data oldProfileKey = this.chatSession != null ? this.chatSession.profilePublicKey().data() : null;
      net.minecraft.world.entity.player.ProfilePublicKey.Data newProfileKey = newChatSession.profilePublicKey();
      if (!Objects.equals(oldProfileKey, newProfileKey)) {
         if (oldProfileKey != null && newProfileKey.expiresAt().isBefore(oldProfileKey.expiresAt())) {
            this.disconnect(ProfilePublicKey.EXPIRED_PROFILE_PUBLIC_KEY, Cause.EXPIRED_PROFILE_PUBLIC_KEY);
         } else {
            try {
               SignatureValidator profileKeySignatureValidator = this.server.services().profileKeySignatureValidator();
               if (profileKeySignatureValidator == null) {
                  LOGGER.warn("Ignoring chat session from {} due to missing Services public key", this.player.getGameProfile().name());
                  return;
               }

               this.resetPlayerChatState(newChatSession.validate(this.player.getGameProfile(), profileKeySignatureValidator));
            } catch (net.minecraft.world.entity.player.ProfilePublicKey.ValidationException e) {
               this.disconnect(e.getComponent(), e.kickCause);
            }
         }
      }
   }

   public void handleConfigurationAcknowledged(ServerboundConfigurationAcknowledgedPacket packet) {
      if (!this.waitingForSwitchToConfig) {
         throw new IllegalStateException("Client acknowledged config, but none was requested");
      }

      ServerConfigurationPacketListenerImpl listener = new ServerConfigurationPacketListenerImpl(
         this.server, this.connection, this.createCookie(this.player.clientInformation())
      );
      this.connection.setupInboundProtocol(ConfigurationProtocols.SERVERBOUND, listener);
      new PlayerConnectionReconfigureEvent(listener.paperConnection).callEvent();
   }

   public void handleChunkBatchReceived(ServerboundChunkBatchReceivedPacket packet) {
      PacketUtils.ensureRunningOnSameThread(packet, this, this.player.level());
      this.chunkSender.onChunkBatchReceivedByClient(packet.desiredChunksPerTick());
   }

   public void handleDebugSubscriptionRequest(ServerboundDebugSubscriptionRequestPacket packet) {
      PacketUtils.ensureRunningOnSameThread(packet, this, this.player.level());
      this.player.requestDebugSubscriptions(packet.subscriptions());
   }

   private void resetPlayerChatState(RemoteChatSession chatSession) {
      this.chatSession = chatSession;
      this.hasLoggedExpiry = false;
      this.signedMessageDecoder = chatSession.createMessageDecoder(this.player.getUUID());
      this.chatMessageChain
         .append(
            () -> this.server
               .executeBlocking(
                  () -> {
                     this.player.setChatSession(chatSession);
                     this.server
                        .getPlayerList()
                        .broadcastAll(
                           new ClientboundPlayerInfoUpdatePacket(
                              EnumSet.of(ClientboundPlayerInfoUpdatePacket.Action.INITIALIZE_CHAT), (Collection<ServerPlayer>)List.of(this.player)
                           ),
                           this.player
                        );
                  }
               )
         );
   }

   public void handleCustomPayload(ServerboundCustomPayloadPacket packet) {
      super.handleCustomPayload(packet);
   }

   public void handleClientTickEnd(ServerboundClientTickEndPacket packet) {
      PacketUtils.ensureRunningOnSameThread(packet, this, this.player.level());
      this.tickEndEvent.callEvent();
      if (!this.receivedMovementThisTick) {
         this.player.setKnownMovement(Vec3.ZERO);
      }

      this.receivedMovementThisTick = false;
   }

   private void handlePlayerKnownMovement(Vec3 movement) {
      if (movement.lengthSqr() > 1.0E-5F) {
         this.player.resetLastActionTime();
      }

      this.player.setKnownMovement(movement);
      this.receivedMovementThisTick = true;
   }

   public boolean hasInfiniteMaterials() {
      return this.player.hasInfiniteMaterials();
   }

   public ServerPlayer getPlayer() {
      return this.player;
   }

   public boolean hasClientLoaded() {
      return !this.waitingForRespawn && this.clientLoadedTimeoutTimer <= 0;
   }

   public void tickClientLoadTimeout() {
      if (this.clientLoadedTimeoutTimer > 1) {
         this.clientLoadedTimeoutTimer--;
      } else if (this.clientLoadedTimeoutTimer == 1) {
         this.markClientLoaded(true);
      }
   }

   @Deprecated
   @DoNotUse
   private void markClientLoaded() {
      this.markClientLoaded(false);
   }

   private void markClientLoaded(boolean timeout) {
      if (!this.hasClientLoaded()) {
         PlayerClientLoadedWorldEvent event = new PlayerClientLoadedWorldEvent(this.player.getBukkitEntity(), timeout);
         event.callEvent();
      }

      this.clientLoadedTimeoutTimer = 0;
   }

   public void markClientUnloadedAfterDeath() {
      this.waitingForRespawn = true;
   }

   public void restartClientLoadTimerAfterRespawn() {
      this.waitingForRespawn = false;
      this.clientLoadedTimeoutTimer = 60;
   }

   private PlayerFailMoveEvent fireFailMove(
      FailReason failReason, double targetX, double targetY, double targetZ, float targetYRot, float targetXRot, boolean logWarning
   ) {
      Player player = this.getCraftPlayer();
      Location from = new Location(player.getWorld(), this.lastPosX, this.lastPosY, this.lastPosZ, this.lastYRot, this.lastXRot);
      Location to = new Location(player.getWorld(), targetX, targetY, targetZ, targetYRot, targetXRot);
      PlayerFailMoveEvent event = new PlayerFailMoveEvent(player, failReason, false, logWarning, from, to);
      event.callEvent();
      return event;
   }

   public CraftPlayer getCraftPlayer() {
      return this.player == null ? null : this.player.getBukkitEntity();
   }

   public void disconnectAsync(DisconnectionDetails disconnectionInfo) {
      if (this.cserver.isPrimaryThread()) {
         this.disconnect(disconnectionInfo);
      } else {
         this.connection.setReadOnly();
         this.server.scheduleOnMain(() -> {
            this.disconnect(disconnectionInfo);
            if (this.player.quitReason == null) {
               this.connection.enableAutoRead();
            }
         });
      }
   }

   public final boolean isDisconnected() {
      return !this.player.joining && !this.connection.isConnected();
   }

   public void handleResourcePackResponse(ServerboundResourcePackPacket packet) {
      super.handleResourcePackResponse(packet);
      Status packStatus = Status.values()[packet.action().ordinal()];
      this.connection.resourcePackStatus = packStatus;
      this.cserver.getPluginManager().callEvent(new PlayerResourcePackStatusEvent(this.getCraftPlayer(), packet.id(), packStatus));
   }

   public void disconnect(net.kyori.adventure.text.Component reason) {
      this.disconnect(reason, Cause.UNKNOWN);
   }

   public void disconnect(net.kyori.adventure.text.Component reason, Cause cause) {
      this.disconnect(PaperAdventure.asVanilla(reason), DisconnectionReason.game(cause));
   }

   public void disconnect(Component reason, Cause cause) {
      this.disconnect(reason, DisconnectionReason.game(cause));
   }

   public void disconnectAsync(net.kyori.adventure.text.Component reason, Cause cause) {
      this.disconnectAsync(PaperAdventure.asVanilla(reason), cause);
   }

   public void disconnectAsync(Component reason, Cause cause) {
      this.disconnectAsync(reason, DisconnectionReason.game(cause));
   }

   public PaperPlayerGameConnection paperConnection() {
      return this.playerGameConnection;
   }
}
