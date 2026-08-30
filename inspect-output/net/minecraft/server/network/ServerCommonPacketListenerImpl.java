package net.minecraft.server.network;

import com.mojang.authlib.GameProfile;
import com.mojang.logging.LogUtils;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.papermc.paper.adventure.PaperAdventure;
import io.papermc.paper.adventure.providers.ClickCallbackProviderImpl;
import io.papermc.paper.annotation.DoNotUse;
import io.papermc.paper.configuration.GlobalConfiguration;
import io.papermc.paper.connection.DisconnectionReason;
import io.papermc.paper.connection.PaperCommonConnection;
import io.papermc.paper.connection.PlayerCommonConnection;
import io.papermc.paper.connection.PluginMessageBridgeImpl;
import io.papermc.paper.event.player.PaperPlayerCustomClickEvent;
import io.papermc.paper.util.KeepAlive;
import io.papermc.paper.util.KeepAlive.KeepAliveResponse;
import io.papermc.paper.util.KeepAlive.PendingKeepAlive;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.resource.ResourcePackCallback;
import net.kyori.adventure.resource.ResourcePackStatus;
import net.kyori.adventure.text.ComponentLike;
import net.kyori.adventure.text.format.NamedTextColor;
import net.minecraft.CrashReport;
import net.minecraft.CrashReportCategory;
import net.minecraft.ReportedException;
import net.minecraft.nbt.Tag;
import net.minecraft.network.Connection;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.PacketListener;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketUtils;
import net.minecraft.network.protocol.common.ClientboundDisconnectPacket;
import net.minecraft.network.protocol.common.ClientboundKeepAlivePacket;
import net.minecraft.network.protocol.common.ServerCommonPacketListener;
import net.minecraft.network.protocol.common.ServerboundCustomClickActionPacket;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.ServerboundKeepAlivePacket;
import net.minecraft.network.protocol.common.ServerboundPongPacket;
import net.minecraft.network.protocol.common.ServerboundResourcePackPacket;
import net.minecraft.network.protocol.common.ServerboundResourcePackPacket.Action;
import net.minecraft.network.protocol.common.custom.DiscardedPayload;
import net.minecraft.network.protocol.cookie.ServerboundCookieResponsePacket;
import net.minecraft.network.protocol.game.ClientboundSetDefaultSpawnPositionPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;
import net.minecraft.util.Util;
import net.minecraft.util.VisibleForDebug;
import net.minecraft.util.profiling.Profiler;
import org.bukkit.craftbukkit.CraftServer;
import org.bukkit.craftbukkit.util.CraftLocation;
import org.bukkit.craftbukkit.util.Waitable;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.player.PlayerKickEvent.Cause;
import org.bukkit.event.player.PlayerQuitEvent.QuitReason;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

public abstract class ServerCommonPacketListenerImpl implements ServerCommonPacketListener {
   private static final Logger LOGGER = LogUtils.getLogger();
   public static final int LATENCY_CHECK_INTERVAL = 15000;
   private static final int CLOSED_LISTENER_TIMEOUT = 15000;
   private static final Component TIMEOUT_DISCONNECTION_MESSAGE = Component.translatable("disconnect.timeout");
   static final Component DISCONNECT_UNEXPECTED_QUERY = Component.translatable("multiplayer.disconnect.unexpected_query_response");
   protected final MinecraftServer server;
   public final Connection connection;
   private final boolean transferred;
   private long closedListenerTime;
   private boolean closed = false;
   private volatile int latency;
   private final KeepAlive keepAlive;
   private volatile boolean suspendFlushingOnServerThread = false;
   public final CraftServer cserver;
   public final Map<UUID, ResourcePackCallback> packCallbacks = new ConcurrentHashMap<>();
   private static final long KEEPALIVE_LIMIT = Long.getLong("paper.playerconnection.keepalive", 30L) * 1000L;
   protected static final Identifier MINECRAFT_BRAND = Identifier.withDefaultNamespace("brand");
   public @Nullable String clientBrand;
   public final Set<String> pluginMessagerChannels;
   public static final Identifier CUSTOM_REGISTER = Identifier.withDefaultNamespace("register");
   private static final Identifier CUSTOM_UNREGISTER = Identifier.withDefaultNamespace("unregister");

   public ServerCommonPacketListenerImpl(MinecraftServer server, Connection connection, CommonListenerCookie cookie) {
      this.server = server;
      this.connection = connection;
      this.latency = cookie.latency();
      this.transferred = cookie.transferred();
      this.clientBrand = cookie.clientBrand();
      this.cserver = server.server;
      this.pluginMessagerChannels = cookie.channels();
      this.keepAlive = cookie.keepAlive();
   }

   public abstract PlayerCommonConnection getApiConnection();

   public abstract Audience getAudience();

   private void close() {
      if (!this.closed) {
         this.closedListenerTime = Util.getMillis();
         this.closed = true;
      }
   }

   public void onDisconnect(DisconnectionDetails details) {
      if (this.isSingleplayerOwner()) {
         LOGGER.info("Stopping singleplayer server as player logged out");
         this.server.halt(false);
      }
   }

   public void onPacketError(Packet packet, Exception e) throws ReportedException {
      super.onPacketError(packet, e);
      this.server.reportPacketHandlingException(e, packet.type());
   }

   public void handleKeepAlive(ServerboundKeepAlivePacket packet) {
      long now = System.nanoTime();
      PendingKeepAlive pending = (PendingKeepAlive)this.keepAlive.pendingKeepAlives.peek();
      if (pending != null && pending.challengeId() == packet.getId()) {
         this.keepAlive.pendingKeepAlives.remove(pending);
         KeepAliveResponse response = new KeepAliveResponse(pending.txTimeNS(), now);
         this.keepAlive.pingCalculator1m.update(response);
         this.keepAlive.pingCalculator5s.update(response);
         this.latency = this.keepAlive.pingCalculator5s.getAvgLatencyMS();
      } else {
         Iterator<PendingKeepAlive> itr = this.keepAlive.pendingKeepAlives.iterator();

         while (itr.hasNext()) {
            PendingKeepAlive ka = itr.next();
            if (ka.challengeId() == packet.getId()) {
               itr.remove();
               LOGGER.info("Disconnecting {} for sending keepalive response ({}) out-of-order!", this.playerProfile().name(), packet.getId());
               this.disconnectAsync(TIMEOUT_DISCONNECTION_MESSAGE, DisconnectionReason.TIMEOUT);
               return;
            }
         }

         LOGGER.info("Disconnecting {} for sending keepalive response ({}) without matching challenge!", this.playerProfile().name(), packet.getId());
         this.disconnectAsync(TIMEOUT_DISCONNECTION_MESSAGE, DisconnectionReason.TIMEOUT);
      }
   }

   public void handlePong(ServerboundPongPacket serverboundPongPacket) {
   }

   public void handleCustomPayload(ServerboundCustomPayloadPacket packet) {
      if (packet.payload() instanceof DiscardedPayload discardedPayload) {
         PacketUtils.ensureRunningOnSameThread(packet, this, this.server.packetProcessor());
         Identifier var10 = packet.payload().type().id();
         byte[] data = discardedPayload.data();

         try {
            boolean registerChannel = CUSTOM_REGISTER.equals(var10);
            if (registerChannel || CUSTOM_UNREGISTER.equals(var10)) {
               int startIndex = 0;

               for (int i = 0; i < data.length; i++) {
                  byte b = data[i];
                  if (b == 0) {
                     this.readChannelIdentifier(data, startIndex, i, registerChannel);
                     startIndex = i + 1;
                  }
               }

               this.readChannelIdentifier(data, startIndex, data.length, registerChannel);
               return;
            }

            if (var10.equals(MINECRAFT_BRAND)) {
               this.clientBrand = new FriendlyByteBuf(Unpooled.wrappedBuffer(data)).readUtf(256);
            }

            this.cserver.getMessenger().dispatchIncomingMessage(this.paperConnection(), var10.toString(), data);
         } catch (Exception e) {
            LOGGER.error("Couldn't handle custom payload on channel {}", var10, e);
            this.disconnect(Component.literal("Invalid custom payload payload!"), DisconnectionReason.INVALID_PAYLOAD);
         }
      }
   }

   private void readChannelIdentifier(byte[] data, int from, int to, boolean register) {
      PluginMessageBridgeImpl bridge = (PluginMessageBridgeImpl)(switch (this) {
         case ServerGamePacketListenerImpl gamePacketListener -> gamePacketListener.player.getBukkitEntity();
         case ServerConfigurationPacketListenerImpl commonPacketListener -> commonPacketListener.paperConnection;
         default -> null;
      });
      if (bridge != null) {
         int length = to - from;
         if (length != 0) {
            String channel = new String(data, from, length, StandardCharsets.US_ASCII);
            if (register) {
               bridge.addChannel(channel);
            } else {
               bridge.removeChannel(channel);
            }
         }
      }
   }

   public void handleCustomClickAction(ServerboundCustomClickActionPacket packet) {
      PacketUtils.ensureRunningOnSameThread(packet, this, this.server.packetProcessor());
      this.server.handleCustomClickAction(packet.id(), packet.payload());
      PaperPlayerCustomClickEvent event = new PaperPlayerCustomClickEvent(
         PaperAdventure.asAdventure(packet.id()), this.getApiConnection(), (Tag)packet.payload().orElse(null)
      );
      event.callEvent();
      ClickCallbackProviderImpl.DIALOG_CLICK_MANAGER.tryRunCallback(this.getAudience(), packet.id(), packet.payload());
      ClickCallbackProviderImpl.ADVENTURE_CLICK_MANAGER.tryRunCallback(this.getAudience(), packet.id(), packet.payload());
   }

   public void handleResourcePackResponse(ServerboundResourcePackPacket packet) {
      PacketUtils.ensureRunningOnSameThread(packet, (PacketListener)this, ((ServerCommonPacketListenerImpl)this).server.packetProcessor());
      if (packet.action() == Action.DECLINED && ((ServerCommonPacketListenerImpl)this).server.isResourcePackRequired()) {
         LOGGER.info("Disconnecting {} due to resource pack {} rejection", ((ServerCommonPacketListenerImpl)this).playerProfile().name(), packet.id());
         ((ServerCommonPacketListenerImpl)this).disconnect(
            Component.translatable("multiplayer.requiredTexturePrompt.disconnect"), DisconnectionReason.RESOURCE_PACK_REJECTION
         );
      }

      ResourcePackCallback callback;
      if (packet.action().isTerminal()) {
         callback = ((ServerCommonPacketListenerImpl)this).packCallbacks.remove(packet.id());
      } else {
         callback = ((ServerCommonPacketListenerImpl)this).packCallbacks.get(packet.id());
      }

      if (callback != null) {
         Audience audience = (Audience)(switch (this) {
            case ServerGamePacketListenerImpl serverGamePacketListener -> serverGamePacketListener.getCraftPlayer();
            case ServerConfigurationPacketListenerImpl configurationPacketListener -> configurationPacketListener.paperConnection.getAudience();
            default -> throw new IllegalStateException("Unexpected value: " + this);
         });
         callback.packEventReceived(packet.id(), ResourcePackStatus.valueOf(packet.action().name()), audience);
      }
   }

   public void handleCookieResponse(ServerboundCookieResponsePacket packet) {
      if (!this.paperConnection().handleCookieResponse(packet)) {
         this.disconnect(DISCONNECT_UNEXPECTED_QUERY, DisconnectionReason.INVALID_COOKIE);
      }
   }

   protected void keepConnectionAlive() {
      Profiler.get().push("keepAlive");
      long now = Util.getMillis();
      if (this.checkIfClosed(now)) {
         long currTime = System.nanoTime();
         if (currTime - this.keepAlive.lastKeepAliveTx >= TimeUnit.SECONDS.toNanos(1L)) {
            this.keepAlive.lastKeepAliveTx = currTime;
            PendingKeepAlive pka = new PendingKeepAlive(currTime, now);
            this.keepAlive.pendingKeepAlives.add(pka);
            this.send(new ClientboundKeepAlivePacket(pka.challengeId()));
         }

         PendingKeepAlive oldest = (PendingKeepAlive)this.keepAlive.pendingKeepAlives.peek();
         if (oldest != null && currTime - oldest.txTimeNS() > TimeUnit.MILLISECONDS.toNanos(KEEPALIVE_LIMIT)) {
            LOGGER.info("{} was kicked due to keepalive timeout!", this.playerProfile().name());
            this.disconnect(TIMEOUT_DISCONNECTION_MESSAGE, DisconnectionReason.TIMEOUT);
         }
      }

      Profiler.get().pop();
   }

   private boolean checkIfClosed(long now) {
      if (this.closed) {
         if (now - this.closedListenerTime >= 15000L) {
            this.disconnect(TIMEOUT_DISCONNECTION_MESSAGE, DisconnectionReason.TIMEOUT);
         }

         return false;
      } else {
         return true;
      }
   }

   public void suspendFlushing() {
      this.suspendFlushingOnServerThread = true;
   }

   public void resumeFlushing() {
      this.suspendFlushingOnServerThread = false;
      this.connection.flushChannel();
   }

   public void send(Packet<?> packet) {
      this.send(packet, null);
   }

   public void send(Packet<?> packet, @Nullable ChannelFutureListener listener) {
      if (packet != null) {
         if (packet instanceof ClientboundSetDefaultSpawnPositionPacket defaultSpawnPositionPacket
            && this instanceof ServerGamePacketListenerImpl serverGamePacketListener) {
            serverGamePacketListener.player.compassTarget = CraftLocation.toBukkit(
               defaultSpawnPositionPacket.respawnData().pos(), serverGamePacketListener.getPlayer().level()
            );
         }

         if (packet.isTerminal()) {
            this.close();
         }

         boolean flush = !this.suspendFlushingOnServerThread || !this.server.isSameThread();

         try {
            this.connection.send(packet, listener, flush);
         } catch (Throwable t) {
            CrashReport report = CrashReport.forThrowable(t, "Sending packet");
            CrashReportCategory category = report.addCategory("Packet being sent");
            category.setDetail("Packet class", () -> packet.getClass().getCanonicalName());
            throw new ReportedException(report);
         }
      }
   }

   @Deprecated
   public void disconnect(Component reason) {
      this.disconnect(reason, DisconnectionReason.UNKNOWN);
   }

   public void disconnect(Component reason, DisconnectionReason cause) {
      this.disconnect(new DisconnectionDetails(reason, Optional.empty(), Optional.empty(), Optional.empty(), Optional.of(cause)));
   }

   public void disconnect(final DisconnectionDetails details) {
      if (this.connection.isConnected()) {
         if (!this.cserver.isPrimaryThread()) {
            Waitable waitable = new Waitable() {
               protected @Nullable Object evaluate() {
                  ServerCommonPacketListenerImpl.this.disconnect(details);
                  return null;
               }
            };
            this.server.processQueue.add(waitable);

            try {
               waitable.get();
            } catch (InterruptedException e) {
               Thread.currentThread().interrupt();
            } catch (ExecutionException e) {
               throw new RuntimeException(e);
            }
         } else {
            Component reason;
            Component leaveMessage;
            if (this instanceof ServerGamePacketListenerImpl serverGamePacketListener) {
               net.kyori.adventure.text.Component rawLeaveMessage = net.kyori.adventure.text.Component.translatable(
                  "multiplayer.player.left",
                  NamedTextColor.YELLOW,
                  new ComponentLike[]{
                     (ComponentLike)(GlobalConfiguration.get().messages.useDisplayNameInQuitMessage
                        ? serverGamePacketListener.player.getBukkitEntity().displayName()
                        : net.kyori.adventure.text.Component.text(serverGamePacketListener.player.getScoreboardName()))
                  }
               );
               ServerPlayer player = serverGamePacketListener.player;
               Cause cause = ((DisconnectionReason)details.disconnectionReason().orElseThrow()).game().orElse(Cause.UNKNOWN);
               PlayerKickEvent event = new PlayerKickEvent(player.getBukkitEntity(), PaperAdventure.asAdventure(details.reason()), rawLeaveMessage, cause);
               if (this.cserver.getServer().isRunning()) {
                  this.cserver.getPluginManager().callEvent(event);
               }

               if (event.isCancelled()) {
                  return;
               }

               reason = PaperAdventure.asVanilla(event.reason());
               leaveMessage = PaperAdventure.asVanilla(event.leaveMessage());
               serverGamePacketListener.player.quitReason = QuitReason.KICKED;
               switch (cause) {
                  case FLYING_PLAYER:
                     LOGGER.warn("{} was kicked for floating too long!", player.getPlainTextName());
                     break;
                  case FLYING_VEHICLE:
                     LOGGER.warn("{} was kicked for floating a vehicle too long!", player.getPlainTextName());
               }
            } else {
               reason = details.reason();
               leaveMessage = null;
            }

            this.disconnect0(
               new DisconnectionDetails(reason, details.report(), details.bugReportLink(), Optional.ofNullable(leaveMessage), details.disconnectionReason())
            );
         }
      }
   }

   private void disconnect0(DisconnectionDetails details) {
      this.connection.send(new ClientboundDisconnectPacket(details.reason()), PacketSendListener.thenRun(() -> this.connection.disconnect(details)));
      this.connection.setReadOnly();
      this.connection.handleConnectionDisconnectOnNextTick = true;
   }

   public final void disconnectAsync(Component component, DisconnectionReason reason) {
      this.disconnectAsync(new DisconnectionDetails(component, Optional.empty(), Optional.empty(), Optional.empty(), Optional.of(reason)));
   }

   @Deprecated
   @DoNotUse
   public final void disconnectAsync(Component component) {
      this.disconnectAsync(component, DisconnectionReason.UNKNOWN);
   }

   public abstract void disconnectAsync(DisconnectionDetails var1);

   public boolean isTransferred() {
      return this.transferred;
   }

   public abstract PaperCommonConnection<?> paperConnection();

   protected boolean isSingleplayerOwner() {
      return this.server.isSingleplayerOwner(new NameAndId(this.playerProfile()));
   }

   protected abstract GameProfile playerProfile();

   @VisibleForDebug
   public GameProfile getOwner() {
      return this.playerProfile();
   }

   public int latency() {
      return this.latency;
   }

   protected CommonListenerCookie createCookie(ClientInformation clientInformation) {
      return new CommonListenerCookie(
         this.playerProfile(),
         this.latency,
         clientInformation,
         this.transferred,
         this.clientBrand,
         this.pluginMessagerChannels,
         this.keepAlive.copyForListenerHandoff()
      );
   }
}
