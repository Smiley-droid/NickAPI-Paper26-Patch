package net.minecraft.network;

import com.destroystokyo.paper.event.player.PlayerConnectionCloseEvent;
import com.google.common.collect.Queues;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.mojang.logging.LogUtils;
import com.velocitypowered.natives.compression.VelocityCompressor;
import com.velocitypowered.natives.compression.VelocityCompressorFactory;
import com.velocitypowered.natives.encryption.VelocityCipher;
import com.velocitypowered.natives.encryption.VelocityCipherFactory;
import com.velocitypowered.natives.util.Natives;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelException;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandler;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelOutboundHandler;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelPromise;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.local.LocalChannel;
import io.netty.channel.local.LocalServerChannel;
import io.netty.handler.codec.EncoderException;
import io.netty.handler.flow.FlowControlHandler;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.handler.timeout.TimeoutException;
import io.papermc.paper.adventure.PaperAdventure;
import io.papermc.paper.configuration.GlobalConfiguration;
import io.papermc.paper.configuration.GlobalConfiguration.PacketLimiter.PacketLimit;
import io.papermc.paper.network.ConnectionEvent;
import io.papermc.paper.util.IntervalledCounter;
import io.papermc.paper.util.MCUtil;
import io.papermc.paper.util.ServerStopRejectedExecutionException;
import io.papermc.paper.util.TraceUtil;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.channels.ClosedChannelException;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import javax.crypto.SecretKey;
import net.minecraft.SharedConstants;
import net.minecraft.network.PacketEncoder.PacketTooLargeException;
import net.minecraft.network.UnconfiguredPipelineHandler.Inbound;
import net.minecraft.network.UnconfiguredPipelineHandler.InboundConfigurationTask;
import net.minecraft.network.UnconfiguredPipelineHandler.Outbound;
import net.minecraft.network.UnconfiguredPipelineHandler.OutboundConfigurationTask;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.BundlerInfo;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.ClientboundDisconnectPacket;
import net.minecraft.network.protocol.common.ClientboundKeepAlivePacket;
import net.minecraft.network.protocol.game.ClientboundBossEventPacket;
import net.minecraft.network.protocol.game.ClientboundClearTitlesPacket;
import net.minecraft.network.protocol.game.ClientboundCommandSuggestionsPacket;
import net.minecraft.network.protocol.game.ClientboundDisguisedChatPacket;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerChatPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.network.protocol.game.ClientboundSoundEntityPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.network.protocol.game.ClientboundStopSoundPacket;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.network.protocol.handshake.ClientIntent;
import net.minecraft.network.protocol.handshake.ClientIntentionPacket;
import net.minecraft.network.protocol.handshake.HandshakeProtocols;
import net.minecraft.network.protocol.handshake.ServerHandshakePacketListener;
import net.minecraft.network.protocol.login.ClientLoginPacketListener;
import net.minecraft.network.protocol.login.ClientboundLoginDisconnectPacket;
import net.minecraft.network.protocol.login.LoginProtocols;
import net.minecraft.network.protocol.ping.ClientboundPongResponsePacket;
import net.minecraft.network.protocol.status.ClientStatusPacketListener;
import net.minecraft.network.protocol.status.StatusProtocols;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.RunningOnDifferentThreadException;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.EventLoopGroupHolder;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.network.ServerLoginPacketListenerImpl;
import net.minecraft.server.network.ServerLoginPacketListenerImpl.State;
import net.minecraft.util.CryptException;
import net.minecraft.util.Mth;
import net.minecraft.util.Util;
import net.minecraft.util.debugchart.LocalSampleLogger;
import org.bukkit.event.player.PlayerQuitEvent.QuitReason;
import org.bukkit.event.player.PlayerResourcePackStatusEvent.Status;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.Marker;
import org.slf4j.MarkerFactory;

public class Connection extends SimpleChannelInboundHandler<Packet<?>> {
   private static final float AVERAGE_PACKETS_SMOOTHING = 0.75F;
   private static final Logger LOGGER = LogUtils.getLogger();
   public static final Marker ROOT_MARKER = MarkerFactory.getMarker("NETWORK");
   public static final Marker PACKET_MARKER = (Marker)Util.make(MarkerFactory.getMarker("NETWORK_PACKETS"), m -> m.add(ROOT_MARKER));
   public static final Marker PACKET_RECEIVED_MARKER = (Marker)Util.make(MarkerFactory.getMarker("PACKET_RECEIVED"), m -> m.add(PACKET_MARKER));
   public static final Marker PACKET_SENT_MARKER = (Marker)Util.make(MarkerFactory.getMarker("PACKET_SENT"), m -> m.add(PACKET_MARKER));
   private static final ProtocolInfo<ServerHandshakePacketListener> INITIAL_PROTOCOL = HandshakeProtocols.SERVERBOUND;
   private final PacketFlow receiving;
   private volatile boolean sendLoginDisconnect = true;
   private final Queue<Connection.WrappedConsumer> pendingActions = Queues.newConcurrentLinkedQueue();
   public Channel channel;
   public SocketAddress address;
   public UUID spoofedUUID;
   public Property[] spoofedProfile;
   public boolean preparing = true;
   private volatile @Nullable PacketListener disconnectListener;
   private volatile @Nullable PacketListener packetListener;
   private @Nullable DisconnectionDetails disconnectionDetails;
   private boolean disconnectionHandled;
   private int receivedPackets;
   private int sentPackets;
   private float averageReceivedPackets;
   private float averageSentPackets;
   private int tickCount;
   private boolean handlingFault;
   private volatile @Nullable DisconnectionDetails delayedDisconnect;
   private @Nullable BandwidthDebugMonitor bandwidthDebugMonitor;
   private @Nullable UUID intendedProfileId;
   public String hostname = "";
   public boolean handleConnectionDisconnectOnNextTick = false;
   public int protocolVersion;
   public InetSocketAddress virtualHost;
   private static boolean enableExplicitFlush = Boolean.getBoolean("paper.explicit-flush");
   protected final Object PACKET_LIMIT_LOCK = new Object();
   protected final @Nullable IntervalledCounter allPacketCounts = GlobalConfiguration.get().packetLimiter.allPackets.isEnabled()
      ? new IntervalledCounter((long)(GlobalConfiguration.get().packetLimiter.allPackets.interval() * 1.0E9))
      : null;
   protected final Map<Class<? extends Packet<?>>, IntervalledCounter> packetSpecificLimits = new HashMap<>();
   private boolean stopReadingPackets;
   public @Nullable SocketAddress haProxyAddress;
   public @Nullable Optional<Component> legacySavedLoginEventResultOverride;
   public boolean handledLegacyLoginEvent;
   public @Nullable ServerPlayer savedPlayerForLegacyEvents;
   public @Nullable Status resourcePackStatus;
   public boolean isPending = true;
   public boolean queueImmunity;
   private static final int MAX_PER_TICK = GlobalConfiguration.get().misc.maxJoinsPerTick;
   private static int joinAttemptsThisTick;
   private static int currTick;
   private boolean encrypted;

   public final ServerPlayer getPlayer() {
      if (this.packetListener instanceof ServerGamePacketListenerImpl impl) {
         return impl.player;
      } else {
         return this.packetListener instanceof ServerCommonPacketListenerImpl impl ? null : null;
      }
   }

   private void killForPacketSpam() {
      this.sendPacket(
         new ClientboundDisconnectPacket(PaperAdventure.asVanilla(GlobalConfiguration.get().packetLimiter.kickMessage)),
         PacketSendListener.thenRun(() -> this.disconnect(PaperAdventure.asVanilla(GlobalConfiguration.get().packetLimiter.kickMessage))),
         true
      );
      this.setReadOnly();
      this.stopReadingPackets = true;
   }

   public Connection(PacketFlow receiving) {
      this.receiving = receiving;
   }

   public void channelActive(ChannelHandlerContext ctx) throws Exception {
      super.channelActive(ctx);
      this.channel = ctx.channel();
      this.address = this.channel.remoteAddress();
      this.preparing = false;
      if (this.delayedDisconnect != null) {
         this.disconnect(this.delayedDisconnect);
      }
   }

   public void channelInactive(ChannelHandlerContext ctx) {
      this.disconnect(Component.translatable("disconnect.endOfStream"));
   }

   public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
      if (cause instanceof EncoderException && cause.getCause() instanceof PacketTooLargeException packetTooLargeException) {
         Packet<?> packet = packetTooLargeException.getPacket();
         if (packet.packetTooLarge(this)) {
            ProtocolSwapHandler.handleOutboundTerminalPacket(ctx, packet);
            return;
         }

         if (packet.isSkippable()) {
            LOGGER.debug("Skipping packet due to errors", cause.getCause());
            ProtocolSwapHandler.handleOutboundTerminalPacket(ctx, packet);
            return;
         }

         cause = cause.getCause();
      }

      if (cause instanceof SkipPacketException) {
         LOGGER.debug("Skipping packet due to errors", cause.getCause());
      } else {
         boolean isFirstFault = !this.handlingFault;
         this.handlingFault = true;
         if (this.channel.isOpen()) {
            ServerPlayer player = this.getPlayer();
            if (cause instanceof TimeoutException) {
               LOGGER.debug("Timeout", cause);
               if (player != null) {
                  player.quitReason = QuitReason.TIMED_OUT;
               }

               this.disconnect(Component.translatable("disconnect.timeout"));
            } else {
               Component reason = Component.translatable("disconnect.genericReason", "Internal Exception: " + cause);
               PacketListener listener = this.packetListener;
               DisconnectionDetails details;
               if (listener != null) {
                  details = listener.createDisconnectionInfo(reason, cause);
               } else {
                  details = new DisconnectionDetails(reason);
               }

               if (player != null) {
                  player.quitReason = QuitReason.ERRONEOUS_STATE;
               }

               if (isFirstFault) {
                  LOGGER.debug("Failed to sent packet", cause);
                  boolean doesDisconnectExist = this.packetListener.protocol() != ConnectionProtocol.STATUS
                     && this.packetListener.protocol() != ConnectionProtocol.HANDSHAKING;
                  if (this.getSending() == PacketFlow.CLIENTBOUND && doesDisconnectExist) {
                     Packet<?> packet = (Packet<?>)(this.sendLoginDisconnect
                        ? new ClientboundLoginDisconnectPacket(reason)
                        : new ClientboundDisconnectPacket(reason));
                     this.send(packet, PacketSendListener.thenRun(() -> this.disconnect(details)));
                  } else {
                     this.disconnect(details);
                  }

                  this.setReadOnly();
               } else {
                  LOGGER.debug("Double fault", cause);
                  this.disconnect(details);
               }
            }
         }
      }

      if (MinecraftServer.getServer().isDebugging()) {
         TraceUtil.printStackTrace(cause);
      }
   }

   protected void channelRead0(ChannelHandlerContext ctx, Packet<?> packet) {
      if (this.channel.isOpen()) {
         PacketListener packetListener = this.packetListener;
         if (packetListener == null) {
            throw new IllegalStateException("Received a packet before the packet listener was initialized");
         }

         if (this.stopReadingPackets) {
            return;
         }

         if (this.allPacketCounts != null || GlobalConfiguration.get().packetLimiter.overrides.containsKey(packet.getClass())) {
            long time = System.nanoTime();
            synchronized (this.PACKET_LIMIT_LOCK) {
               if (this.allPacketCounts != null) {
                  this.allPacketCounts.updateAndAdd(1L, time);
                  if (this.allPacketCounts.getRate() >= GlobalConfiguration.get().packetLimiter.allPackets.maxPacketRate()) {
                     this.killForPacketSpam();
                     return;
                  }
               }

               for (Class<?> check = packet.getClass(); check != Object.class; check = check.getSuperclass()) {
                  PacketLimit packetSpecificLimit = (PacketLimit)GlobalConfiguration.get().packetLimiter.overrides.get(check);
                  if (packetSpecificLimit != null && packetSpecificLimit.isEnabled()) {
                     IntervalledCounter counter = this.packetSpecificLimits
                        .computeIfAbsent((Class<? extends Packet<?>>)check, clazz -> new IntervalledCounter((long)(packetSpecificLimit.interval() * 1.0E9)));
                     counter.updateAndAdd(1L, time);
                     if (counter.getRate() >= packetSpecificLimit.maxPacketRate()) {
                        switch (packetSpecificLimit.action()) {
                           case DROP:
                              return;
                           case KICK:
                              String deobfedPacketName = check.getName();
                              String playerName;
                              if (this.packetListener instanceof ServerCommonPacketListenerImpl impl) {
                                 playerName = impl.getOwner().name();
                              } else {
                                 playerName = this.getLoggableAddress(MinecraftServer.getServer().logIPs());
                              }

                              LOGGER.warn("{} kicked for packet spamming: {}", playerName, deobfedPacketName.substring(deobfedPacketName.lastIndexOf(".") + 1));
                              this.killForPacketSpam();
                              return;
                        }
                     }
                  }
               }
            }
         }

         if (packetListener.shouldHandleMessage(packet)) {
            try {
               genericsFtw(packet, packetListener);
            } catch (RunningOnDifferentThreadException var15) {
            } catch (ServerStopRejectedExecutionException var16) {
            } catch (RejectedExecutionException ignored) {
               this.disconnect(Component.translatable("multiplayer.disconnect.server_shutdown"));
            } catch (ClassCastException exception) {
               LOGGER.error("Received {} that couldn't be processed", packet.getClass(), exception);
               this.disconnect(Component.translatable("multiplayer.disconnect.invalid_packet"));
            }

            this.receivedPackets++;
         }
      }
   }

   private static <T extends PacketListener> void genericsFtw(Packet<T> packet, PacketListener listener) {
      packet.handle(listener);
   }

   private void validateListener(ProtocolInfo<?> protocol, PacketListener packetListener) {
      Objects.requireNonNull(packetListener, "packetListener");
      PacketFlow listenerFlow = packetListener.flow();
      if (listenerFlow != this.receiving) {
         throw new IllegalStateException("Trying to set listener for wrong side: connection is " + this.receiving + ", but listener is " + listenerFlow);
      }

      ConnectionProtocol listenerProtocol = packetListener.protocol();
      if (protocol.id() != listenerProtocol) {
         throw new IllegalStateException("Listener protocol (" + listenerProtocol + ") does not match requested one " + protocol);
      }
   }

   private static void syncAfterConfigurationChange(ChannelFuture future) {
      try {
         future.syncUninterruptibly();
      } catch (Exception e) {
         if (!(e instanceof ClosedChannelException)) {
            throw e;
         }

         LOGGER.info("Connection closed during protocol change");
      }
   }

   public <T extends PacketListener> void setupInboundProtocol(ProtocolInfo<T> protocol, T packetListener) {
      this.validateListener(protocol, packetListener);
      if (protocol.flow() != this.getReceiving()) {
         throw new IllegalStateException("Invalid inbound protocol: " + protocol.id());
      }

      this.packetListener = packetListener;
      this.disconnectListener = null;
      InboundConfigurationTask configMessage = UnconfiguredPipelineHandler.setupInboundProtocol(protocol);
      BundlerInfo bundlerInfo = protocol.bundlerInfo();
      if (bundlerInfo != null) {
         PacketBundlePacker newBundler = new PacketBundlePacker(bundlerInfo);
         configMessage = configMessage.andThen(ctx -> ctx.pipeline().addAfter("decoder", "bundler", newBundler));
      }

      syncAfterConfigurationChange(this.channel.writeAndFlush(configMessage));
   }

   public void setupOutboundProtocol(ProtocolInfo<?> protocol) {
      if (protocol.flow() != this.getSending()) {
         throw new IllegalStateException("Invalid outbound protocol: " + protocol.id());
      }

      OutboundConfigurationTask configMessage = UnconfiguredPipelineHandler.setupOutboundProtocol(protocol);
      BundlerInfo bundlerInfo = protocol.bundlerInfo();
      if (bundlerInfo != null) {
         PacketBundleUnpacker newUnbundler = new PacketBundleUnpacker(bundlerInfo);
         configMessage = configMessage.andThen(ctx -> ctx.pipeline().addAfter("encoder", "unbundler", newUnbundler));
      }

      boolean isLoginProtocol = protocol.id() == ConnectionProtocol.LOGIN;
      syncAfterConfigurationChange(this.channel.writeAndFlush(configMessage.andThen(ctx -> this.sendLoginDisconnect = isLoginProtocol)));
   }

   public void setListenerForServerboundHandshake(PacketListener packetListener) {
      if (this.packetListener != null) {
         throw new IllegalStateException("Listener already set");
      }

      if (this.receiving == PacketFlow.SERVERBOUND && packetListener.flow() == PacketFlow.SERVERBOUND && packetListener.protocol() == INITIAL_PROTOCOL.id()) {
         this.packetListener = packetListener;
      } else {
         throw new IllegalStateException("Invalid initial listener");
      }
   }

   public void initiateServerboundStatusConnection(String hostName, int port, ClientStatusPacketListener listener) {
      this.initiateServerboundConnection(hostName, port, StatusProtocols.SERVERBOUND, StatusProtocols.CLIENTBOUND, listener, ClientIntent.STATUS);
   }

   public void initiateServerboundPlayConnection(String hostName, int port, ClientLoginPacketListener listener) {
      this.initiateServerboundConnection(hostName, port, LoginProtocols.SERVERBOUND, LoginProtocols.CLIENTBOUND, listener, ClientIntent.LOGIN);
   }

   public <S extends ServerboundPacketListener, C extends ClientboundPacketListener> void initiateServerboundPlayConnection(
      String hostName, int port, ProtocolInfo<S> outbound, ProtocolInfo<C> inbound, C listener, boolean transfer
   ) {
      this.initiateServerboundConnection(hostName, port, outbound, inbound, listener, transfer ? ClientIntent.TRANSFER : ClientIntent.LOGIN);
   }

   private <S extends ServerboundPacketListener, C extends ClientboundPacketListener> void initiateServerboundConnection(
      String hostName, int port, ProtocolInfo<S> outbound, ProtocolInfo<C> inbound, C listener, ClientIntent intent
   ) {
      if (outbound.id() != inbound.id()) {
         throw new IllegalStateException("Mismatched initial protocols");
      }

      this.disconnectListener = listener;
      this.runOnceConnected(connection -> {
         this.setupInboundProtocol(inbound, listener);
         connection.sendPacket(new ClientIntentionPacket(SharedConstants.getCurrentVersion().protocolVersion(), hostName, port, intent), null, true);
         this.setupOutboundProtocol(outbound);
      });
   }

   public void send(Packet<?> packet) {
      this.send(packet, null);
   }

   public void send(Packet<?> packet, @Nullable ChannelFutureListener listener) {
      this.send(packet, listener, true);
   }

   public void send(Packet<?> packet, @Nullable ChannelFutureListener listener, boolean flush) {
      boolean connected = this.isConnected();
      if (connected || this.preparing) {
         packet.onPacketDispatch(this.getPlayer());
         if (!connected
            || !Connection.InnerUtil.canSendImmediate(this, packet)
               && (
                  !MCUtil.isMainThread()
                     || !packet.isReady()
                     || !this.pendingActions.isEmpty()
                     || packet.getExtraPackets() != null && !packet.getExtraPackets().isEmpty()
               )) {
            List<Packet<?>> extraPackets = Connection.InnerUtil.buildExtraPackets(packet);
            boolean hasExtraPackets = extraPackets != null && !extraPackets.isEmpty();
            if (!hasExtraPackets) {
               this.pendingActions.add(new Connection.PacketSendAction(packet, listener, flush));
            } else {
               List<Connection.PacketSendAction> actions = new ArrayList<>(1 + extraPackets.size());
               actions.add(new Connection.PacketSendAction(packet, null, false));
               int i = 0;
               int len = extraPackets.size();

               while (i < len) {
                  Packet<?> extraPacket = extraPackets.get(i);
                  i++;
                  boolean end = i == len;
                  actions.add(new Connection.PacketSendAction(extraPacket, end ? listener : null, end));
               }

               this.pendingActions.addAll(actions);
            }

            this.flushQueue();
         } else {
            this.sendPacket(packet, listener, flush);
         }
      }
   }

   public void runOnceConnected(Consumer<Connection> action) {
      if (this.isConnected()) {
         this.flushQueue();
         action.accept(this);
      } else {
         this.pendingActions.add(new Connection.WrappedConsumer(action));
      }
   }

   private void scheduleNettyTask(Runnable task) {
      boolean connected = this.isConnected();
      if (connected || this.preparing) {
         if (connected && this.pendingActions.isEmpty()) {
            this.channel.eventLoop().execute(task);
         } else {
            this.pendingActions.add(new Connection.WrappedConsumer(connection -> connection.channel.eventLoop().execute(task)));
            this.flushQueue();
         }
      }
   }

   public void sendPacketAndScheduleNettyTask(Packet<?> packet, boolean flush, Runnable after) {
      this.scheduleNettyTask(() -> {
         this.sendPacket(packet, null, flush);
         if (after != null) {
            after.run();
         }
      });
   }

   private void sendPacket(Packet<?> packet, @Nullable ChannelFutureListener listener, boolean flush) {
      this.sentPackets++;
      if (this.channel.eventLoop().inEventLoop()) {
         this.doSendPacket(packet, listener, flush);
      } else {
         this.channel.eventLoop().execute(() -> this.doSendPacket(packet, listener, flush));
      }
   }

   private void doSendPacket(Packet<?> packet, @Nullable ChannelFutureListener listener, boolean flush) {
      ServerPlayer player = this.getPlayer();
      if (!this.isConnected()) {
         packet.onPacketDispatchFinish(player, null);
      } else {
         try {
            ChannelFuture future;
            if (listener != null) {
               future = flush ? this.channel.writeAndFlush(packet) : this.channel.write(packet);
               future.addListener(listener);
            } else if (flush) {
               future = this.channel.writeAndFlush(packet, this.channel.voidPromise());
            } else {
               future = this.channel.write(packet, this.channel.voidPromise());
            }

            if (packet.hasFinishListener()) {
               future.addListener((ChannelFutureListener)f -> packet.onPacketDispatchFinish(player, f));
            }
         } catch (Exception e) {
            LOGGER.error("NetworkException: {}", player, e);
            Component reason = Component.translatable("disconnect.genericReason", "Internal Exception: " + e.getMessage());
            this.send(new ClientboundDisconnectPacket(reason), PacketSendListener.thenRun(() -> this.disconnect(reason)));
            packet.onPacketDispatchFinish(player, null);
         }
      }
   }

   public void flushChannel() {
      if (this.isConnected()) {
         this.flush();
      } else {
         this.pendingActions.add(new Connection.WrappedConsumer(Connection::flush));
      }
   }

   private void flush() {
      if (this.channel.eventLoop().inEventLoop()) {
         this.channel.flush();
      } else {
         this.channel.eventLoop().execute(() -> this.channel.flush());
      }
   }

   private boolean flushQueue() {
      if (!this.isConnected()) {
         return true;
      }

      if (MCUtil.isMainThread()) {
         return this.processQueue();
      }

      if (this.isPending) {
         synchronized (this.pendingActions) {
            return this.processQueue();
         }
      } else {
         return false;
      }
   }

   private boolean processQueue() {
      if (this.pendingActions.isEmpty()) {
         return true;
      }

      Iterator<Connection.WrappedConsumer> iterator = this.pendingActions.iterator();

      while (iterator.hasNext()) {
         Connection.WrappedConsumer queued = iterator.next();
         if (queued == null) {
            return true;
         }

         if (!queued.isConsumed()) {
            if (queued instanceof Connection.PacketSendAction packetSendAction) {
               Packet<?> packet = packetSendAction.packet;
               if (!packet.isReady()) {
                  return false;
               }
            }

            iterator.remove();
            if (queued.tryMarkConsumed()) {
               queued.accept(this);
            }
         }
      }

      return true;
   }

   public void tick() {
      this.flushQueue();
      if (currTick != MinecraftServer.currentTick) {
         currTick = MinecraftServer.currentTick;
         joinAttemptsThisTick = 0;
      }

      if (this.packetListener instanceof TickablePacketListener tickable
         && !(
            this.packetListener instanceof ServerLoginPacketListenerImpl loginPacketListener
               && loginPacketListener.state == State.VERIFYING
               && joinAttemptsThisTick++ >= MAX_PER_TICK
         )) {
         PacketProcessor.packetProcessing.push(this.packetListener);

         try {
            tickable.tick();
         } finally {
            PacketProcessor.packetProcessing.pop();
         }
      }

      if (!this.isConnected() && !this.disconnectionHandled) {
         this.handleDisconnection();
      }

      if (this.channel != null && enableExplicitFlush) {
         this.channel.eventLoop().execute(() -> this.channel.flush());
      }

      if (this.tickCount++ % 20 == 0) {
         this.tickSecond();
      }

      if (this.bandwidthDebugMonitor != null) {
         this.bandwidthDebugMonitor.tick();
      }
   }

   protected void tickSecond() {
      this.averageSentPackets = Mth.lerp(0.75F, this.sentPackets, this.averageSentPackets);
      this.averageReceivedPackets = Mth.lerp(0.75F, this.receivedPackets, this.averageReceivedPackets);
      this.sentPackets = 0;
      this.receivedPackets = 0;
   }

   public SocketAddress getRemoteAddress() {
      return this.address;
   }

   public String getLoggableAddress(boolean logIPs) {
      if (this.address == null) {
         return "local";
      } else {
         return logIPs ? this.address.toString() : "IP hidden";
      }
   }

   public void disconnect(Component reason) {
      this.disconnect(new DisconnectionDetails(reason));
   }

   public void disconnect(DisconnectionDetails details) {
      this.preparing = false;
      this.clearPacketQueue();
      if (this.channel == null) {
         this.delayedDisconnect = details;
      }

      if (this.isConnected()) {
         this.channel.close().awaitUninterruptibly();
         this.disconnectionDetails = details;
      }
   }

   public boolean isMemoryConnection() {
      return this.channel instanceof LocalChannel || this.channel instanceof LocalServerChannel;
   }

   public PacketFlow getReceiving() {
      return this.receiving;
   }

   public PacketFlow getSending() {
      return this.receiving.getOpposite();
   }

   public static Connection connectToServer(InetSocketAddress address, EventLoopGroupHolder eventLoopGroupHolder, @Nullable LocalSampleLogger bandwidthLogger) {
      Connection connection = new Connection(PacketFlow.CLIENTBOUND);
      if (bandwidthLogger != null) {
         connection.setBandwidthLogger(bandwidthLogger);
      }

      ChannelFuture connect = connect(address, eventLoopGroupHolder, connection);
      connect.syncUninterruptibly();
      return connection;
   }

   public static ChannelFuture connect(InetSocketAddress address, EventLoopGroupHolder eventLoopGroupHolder, final Connection connection) {
      return ((Bootstrap)((Bootstrap)((Bootstrap)new Bootstrap().group(eventLoopGroupHolder.eventLoopGroup())).handler(new ChannelInitializer<Channel>() {
         protected void initChannel(Channel channel) {
            try {
               channel.config().setOption(ChannelOption.TCP_NODELAY, true);
            } catch (ChannelException var3) {
            }

            ChannelPipeline pipeline = channel.pipeline().addLast("timeout", new ReadTimeoutHandler(30));
            Connection.configureSerialization(pipeline, PacketFlow.CLIENTBOUND, false, connection.bandwidthDebugMonitor);
            connection.configurePacketHandler(pipeline);
         }
      })).channel(eventLoopGroupHolder.channelCls())).connect(address.getAddress(), address.getPort());
   }

   private static String outboundHandlerName(boolean configureOutbound) {
      return configureOutbound ? "encoder" : "outbound_config";
   }

   private static String inboundHandlerName(boolean configureInbound) {
      return configureInbound ? "decoder" : "inbound_config";
   }

   public void configurePacketHandler(ChannelPipeline pipeline) {
      pipeline.addLast("hackfix", new ChannelOutboundHandlerAdapter() {
         public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
            super.write(ctx, msg, promise);
         }
      }).addLast("packet_handler", this);
   }

   public static void configureSerialization(ChannelPipeline pipeline, PacketFlow inboundDirection, boolean local, @Nullable BandwidthDebugMonitor monitor) {
      PacketFlow outboundDirection = inboundDirection.getOpposite();
      boolean configureInbound = inboundDirection == PacketFlow.SERVERBOUND;
      boolean configureOutbound = outboundDirection == PacketFlow.SERVERBOUND;
      pipeline.addLast("splitter", createFrameDecoder(monitor, local))
         .addLast(new ChannelHandler[]{new FlowControlHandler()})
         .addLast(inboundHandlerName(configureInbound), (ChannelHandler)(configureInbound ? new PacketDecoder(INITIAL_PROTOCOL) : new Inbound()))
         .addLast("prepender", createFrameEncoder(local))
         .addLast(outboundHandlerName(configureOutbound), (ChannelHandler)(configureOutbound ? new PacketEncoder(INITIAL_PROTOCOL) : new Outbound()));
   }

   private static ChannelOutboundHandler createFrameEncoder(boolean local) {
      return (ChannelOutboundHandler)(local ? new LocalFrameEncoder() : new Varint21LengthFieldPrepender());
   }

   private static ChannelInboundHandler createFrameDecoder(@Nullable BandwidthDebugMonitor monitor, boolean local) {
      if (!local) {
         return new Varint21FrameDecoder(monitor);
      } else {
         return (ChannelInboundHandler)(monitor != null ? new MonitoredLocalFrameDecoder(monitor) : new LocalFrameDecoder());
      }
   }

   public static void configureInMemoryPipeline(ChannelPipeline pipeline, PacketFlow packetFlow) {
      configureSerialization(pipeline, packetFlow, true, null);
   }

   public static Connection connectToLocalServer(SocketAddress address) {
      final Connection connection = new Connection(PacketFlow.CLIENTBOUND);
      ((Bootstrap)((Bootstrap)((Bootstrap)new Bootstrap().group(EventLoopGroupHolder.local().eventLoopGroup())).handler(new ChannelInitializer<Channel>() {
         protected void initChannel(Channel channel) {
            ChannelPipeline pipeline = channel.pipeline();
            Connection.configureInMemoryPipeline(pipeline, PacketFlow.CLIENTBOUND);
            connection.configurePacketHandler(pipeline);
         }
      })).channel(EventLoopGroupHolder.local().channelCls())).connect(address).syncUninterruptibly();
      return connection;
   }

   public static Connection fromChannel(Channel channel, PacketFlow flow, @Nullable LocalSampleLogger bandwidthLogger) {
      Connection connection = new Connection(flow);
      if (bandwidthLogger != null) {
         connection.setBandwidthLogger(bandwidthLogger);
      }

      ChannelPipeline pipeline = channel.pipeline().addLast("timeout", new ReadTimeoutHandler(30));
      configureSerialization(pipeline, flow, false, connection.bandwidthDebugMonitor);
      connection.configurePacketHandler(pipeline);
      EventLoopGroupHolder.local().eventLoopGroup().register(channel).syncUninterruptibly();
      return connection;
   }

   public void setEncryptionKey(SecretKey key) throws CryptException {
      if (!this.encrypted) {
         try {
            VelocityCipher decryptionCipher = ((VelocityCipherFactory)Natives.cipher.get()).forDecryption(key);
            VelocityCipher encryptionCipher = ((VelocityCipherFactory)Natives.cipher.get()).forEncryption(key);
            this.encrypted = true;
            this.channel.pipeline().addBefore("splitter", "decrypt", new CipherDecoder(decryptionCipher));
            this.channel.pipeline().addBefore("prepender", "encrypt", new CipherEncoder(encryptionCipher));
         } catch (GeneralSecurityException e) {
            throw new CryptException(e);
         }
      }
   }

   public boolean isConnected() {
      return this.channel != null && this.channel.isOpen();
   }

   public boolean isConnecting() {
      return this.channel == null;
   }

   public @Nullable PacketListener getPacketListener() {
      return this.packetListener;
   }

   public @Nullable DisconnectionDetails getDisconnectionDetails() {
      return this.disconnectionDetails;
   }

   public void setReadOnly() {
      if (this.channel != null) {
         this.channel.config().setAutoRead(false);
      }
   }

   public void enableAutoRead() {
      if (this.channel != null) {
         this.channel.config().setAutoRead(true);
      }
   }

   public void setupCompression(int threshold, boolean validateDecompressed) {
      if (threshold >= 0) {
         VelocityCompressor compressor = ((VelocityCompressorFactory)Natives.compress.get()).create(GlobalConfiguration.get().misc.compressionLevel.or(-1));
         if (this.channel.pipeline().get("decompress") instanceof CompressionDecoder compressionDecoder) {
            compressionDecoder.setThreshold(compressor, threshold, validateDecompressed);
         } else {
            this.channel.pipeline().addAfter("splitter", "decompress", new CompressionDecoder(compressor, threshold, validateDecompressed));
         }

         if (this.channel.pipeline().get("compress") instanceof CompressionEncoder compressionEncoder) {
            compressionEncoder.setThreshold(threshold);
         } else {
            this.channel.pipeline().addAfter("prepender", "compress", new CompressionEncoder(compressor, threshold));
         }

         this.channel.pipeline().fireUserEventTriggered(ConnectionEvent.COMPRESSION_THRESHOLD_SET);
      } else {
         if (this.channel.pipeline().get("decompress") instanceof CompressionDecoder) {
            this.channel.pipeline().remove("decompress");
         }

         if (this.channel.pipeline().get("compress") instanceof CompressionEncoder) {
            this.channel.pipeline().remove("compress");
         }

         this.channel.pipeline().fireUserEventTriggered(ConnectionEvent.COMPRESSION_DISABLED);
      }
   }

   public void handleDisconnection() {
      if (this.channel != null && !this.channel.isOpen() && !this.disconnectionHandled) {
         this.disconnectionHandled = true;
         PacketListener packetListener = this.getPacketListener();
         PacketListener disconnectListener = packetListener != null ? packetListener : this.disconnectListener;
         if (disconnectListener != null) {
            DisconnectionDetails details = Objects.requireNonNullElseGet(
               this.getDisconnectionDetails(), () -> new DisconnectionDetails(Component.translatable("multiplayer.disconnect.generic"))
            );
            disconnectListener.onDisconnect(details);
         }

         this.clearPacketQueue();
         if (packetListener instanceof ServerCommonPacketListenerImpl commonPacketListener) {
            GameProfile profile = commonPacketListener.getOwner();
            new PlayerConnectionCloseEvent(profile.id(), profile.name(), ((InetSocketAddress)this.address).getAddress(), false).callEvent();
         } else if (packetListener instanceof ServerLoginPacketListenerImpl loginListener) {
            switch (loginListener.state) {
               case VERIFYING:
               case WAITING_FOR_DUPE_DISCONNECT:
               case PROTOCOL_SWITCHING:
               case ACCEPTED:
                  GameProfile profile = loginListener.authenticatedProfile;
                  new PlayerConnectionCloseEvent(profile.id(), profile.name(), ((InetSocketAddress)this.address).getAddress(), false).callEvent();
            }
         }
      }
   }

   public float getAverageReceivedPackets() {
      return this.averageReceivedPackets;
   }

   public float getAverageSentPackets() {
      return this.averageSentPackets;
   }

   public void setBandwidthLogger(LocalSampleLogger bandwidthLogger) {
      this.bandwidthDebugMonitor = new BandwidthDebugMonitor(bandwidthLogger);
   }

   public void setIntendedProfileId(UUID profileId) {
      this.intendedProfileId = profileId;
   }

   public @Nullable UUID getIntendedProfileId() {
      return this.intendedProfileId;
   }

   public void clearPacketQueue() {
      ServerPlayer player = this.getPlayer();

      for (Consumer<Connection> queuedAction : this.pendingActions) {
         if (queuedAction instanceof Connection.PacketSendAction packetSendAction) {
            Packet<?> packet = packetSendAction.packet;
            if (packet.hasFinishListener()) {
               packet.onPacketDispatchFinish(player, null);
            }
         }
      }

      this.pendingActions.clear();
   }

   private static final class InnerUtil {
      private static @Nullable List<Packet<?>> buildExtraPackets(Packet<?> packet) {
         List<Packet<?>> extra = packet.getExtraPackets();
         if (extra != null && !extra.isEmpty()) {
            List<Packet<?>> ret = new ArrayList<>(1 + extra.size());
            buildExtraPackets0(extra, ret);
            return ret;
         } else {
            return null;
         }
      }

      private static void buildExtraPackets0(List<Packet<?>> extraPackets, List<Packet<?>> into) {
         for (Packet<?> extra : extraPackets) {
            into.add(extra);
            List<Packet<?>> extraExtra = extra.getExtraPackets();
            if (extraExtra != null && !extraExtra.isEmpty()) {
               buildExtraPackets0(extraExtra, into);
            }
         }
      }

      private static boolean canSendImmediate(Connection connection, Packet<?> packet) {
         return connection.isPending
            || connection.packetListener.protocol() != ConnectionProtocol.PLAY
            || packet instanceof ClientboundKeepAlivePacket
            || packet instanceof ClientboundPlayerChatPacket
            || packet instanceof ClientboundSystemChatPacket
            || packet instanceof ClientboundDisguisedChatPacket
            || packet instanceof ClientboundCommandSuggestionsPacket
            || packet instanceof ClientboundSetTitleTextPacket
            || packet instanceof ClientboundSetSubtitleTextPacket
            || packet instanceof ClientboundSetActionBarTextPacket
            || packet instanceof ClientboundSetTitlesAnimationPacket
            || packet instanceof ClientboundClearTitlesPacket
            || packet instanceof ClientboundSoundPacket
            || packet instanceof ClientboundSoundEntityPacket
            || packet instanceof ClientboundStopSoundPacket
            || packet instanceof ClientboundLevelParticlesPacket
            || packet instanceof ClientboundPlayerInfoUpdatePacket
            || packet instanceof ClientboundPlayerInfoRemovePacket
            || packet instanceof ClientboundBossEventPacket
            || packet instanceof ClientboundPongResponsePacket;
      }
   }

   private static final class PacketSendAction extends Connection.WrappedConsumer {
      private final Packet<?> packet;

      private PacketSendAction(Packet<?> packet, @Nullable ChannelFutureListener channelFutureListener, boolean flush) {
         super(connection -> connection.sendPacket(packet, channelFutureListener, flush));
         this.packet = packet;
      }
   }

   private static class WrappedConsumer implements Consumer<Connection> {
      private final Consumer<Connection> delegate;
      private final AtomicBoolean consumed = new AtomicBoolean(false);

      private WrappedConsumer(Consumer<Connection> delegate) {
         this.delegate = delegate;
      }

      public void accept(Connection connection) {
         this.delegate.accept(connection);
      }

      public boolean tryMarkConsumed() {
         return this.consumed.compareAndSet(false, true);
      }

      public boolean isConsumed() {
         return this.consumed.get();
      }
   }
}
