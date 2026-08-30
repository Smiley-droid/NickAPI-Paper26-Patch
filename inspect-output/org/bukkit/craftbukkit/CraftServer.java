package org.bukkit.craftbukkit;

import ca.spottedleaf.common.time.TickData.MSPTData;
import ca.spottedleaf.moonrise.common.util.TickThread;
import com.destroystokyo.paper.entity.ai.MobGoals;
import com.destroystokyo.paper.entity.ai.PaperMobGoals;
import com.destroystokyo.paper.event.server.ServerExceptionEvent;
import com.destroystokyo.paper.exception.ServerCommandException;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.collect.Iterators;
import com.google.common.collect.Lists;
import com.google.common.collect.MapMaker;
import com.google.gson.JsonElement;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.serialization.JsonOps;
import io.papermc.paper.FeatureHooks;
import io.papermc.paper.ServerBuildInfo;
import io.papermc.paper.SparksFly;
import io.papermc.paper.ServerBuildInfo.StringRepresentation;
import io.papermc.paper.adventure.PaperAdventure;
import io.papermc.paper.ban.BanListType;
import io.papermc.paper.command.PaperCommands;
import io.papermc.paper.commands.FeedbackForwardingSender;
import io.papermc.paper.configuration.GlobalConfiguration;
import io.papermc.paper.configuration.PaperServerConfiguration;
import io.papermc.paper.configuration.ServerConfiguration;
import io.papermc.paper.datapack.PaperDatapackManager;
import io.papermc.paper.logging.SysoutCatcher;
import io.papermc.paper.math.Position;
import io.papermc.paper.plugin.PluginInitializerManager;
import io.papermc.paper.plugin.entrypoint.Entrypoint;
import io.papermc.paper.plugin.entrypoint.LaunchEntryPointHandler;
import io.papermc.paper.plugin.lifecycle.event.LifecycleEventRunner;
import io.papermc.paper.plugin.lifecycle.event.registrar.ReloadableRegistrarEvent.Cause;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import io.papermc.paper.plugin.manager.PaperPluginManagerImpl;
import io.papermc.paper.potion.PaperPotionBrewer;
import io.papermc.paper.threadedregions.scheduler.AsyncScheduler;
import io.papermc.paper.threadedregions.scheduler.FallbackRegionScheduler;
import io.papermc.paper.threadedregions.scheduler.FoliaAsyncScheduler;
import io.papermc.paper.threadedregions.scheduler.FoliaGlobalRegionScheduler;
import io.papermc.paper.threadedregions.scheduler.RegionScheduler;
import io.papermc.paper.util.MCUtil;
import io.papermc.paper.util.TraceUtil;
import io.papermc.paper.world.PaperWorldLoader;
import io.papermc.paper.world.PaperWorldLoader.LoadedWorldData;
import io.papermc.paper.world.migration.WorldFolderMigration;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import javax.imageio.ImageIO;
import javax.management.MBeanServer;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.sound.Sound.Emitter;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.minecraft.Optionull;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.protocol.Packet;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.MinecraftServer.ServerResourcePackInfo;
import net.minecraft.server.ReloadableServerRegistries.Holder;
import net.minecraft.server.bossevents.CustomBossEvent;
import net.minecraft.server.bossevents.CustomBossEvents;
import net.minecraft.server.commands.ReloadCommand;
import net.minecraft.server.dedicated.DedicatedPlayerList;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.server.dedicated.DedicatedServerProperties;
import net.minecraft.server.dedicated.DedicatedServerSettings;
import net.minecraft.server.dedicated.DedicatedServerProperties.WorldDimensionData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.server.players.NameAndId;
import net.minecraft.server.players.PlayerList;
import net.minecraft.server.players.ServerOpListEntry;
import net.minecraft.server.players.StoredUserEntry;
import net.minecraft.server.players.UserBanListEntry;
import net.minecraft.server.players.UserWhiteListEntry;
import net.minecraft.tags.TagKey;
import net.minecraft.util.GsonHelper;
import net.minecraft.util.RandomSource;
import net.minecraft.util.Util;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.village.VillageSiege;
import net.minecraft.world.entity.npc.CatSpawner;
import net.minecraft.world.entity.npc.wanderingtrader.WanderingTraderSpawner;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.ResultContainer;
import net.minecraft.world.inventory.TransientCraftingContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.MapItem;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.RepairItemRecipe;
import net.minecraft.world.item.crafting.CraftingInput.Positioned;
import net.minecraft.world.level.CustomSpawner;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.border.WorldBorder.Settings;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.levelgen.PatrolSpawner;
import net.minecraft.world.level.levelgen.PhantomSpawner;
import net.minecraft.world.level.levelgen.WorldDimensions;
import net.minecraft.world.level.levelgen.WorldGenSettings;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.WorldDimensions.Complete;
import net.minecraft.world.level.levelgen.flat.FlatLevelGeneratorSettings;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.storage.PlayerDataStorage;
import net.minecraft.world.level.storage.PrimaryLevelData;
import net.minecraft.world.level.storage.SavedDataStorage;
import org.apache.commons.lang3.StringUtils;
import org.bukkit.BanList;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Keyed;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.Server;
import org.bukkit.ServerLinks;
import org.bukkit.ServerTickManager;
import org.bukkit.StructureType;
import org.bukkit.Tag;
import org.bukkit.UnsafeValues;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.WorldCreator;
import org.bukkit.Server.Spigot;
import org.bukkit.Warning.WarningState;
import org.bukkit.World.Environment;
import org.bukkit.advancement.Advancement;
import org.bukkit.block.BlockType;
import org.bukkit.block.data.BlockData;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarFlag;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.boss.KeyedBossBar;
import org.bukkit.command.BlockCommandSender;
import org.bukkit.command.Command;
import org.bukkit.command.CommandException;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.SimpleCommandMap;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.configuration.serialization.ConfigurationSerialization;
import org.bukkit.craftbukkit.CraftServer.1;
import org.bukkit.craftbukkit.CraftServer.2;
import org.bukkit.craftbukkit.CraftServer.3;
import org.bukkit.craftbukkit.ban.CraftIpBanList;
import org.bukkit.craftbukkit.ban.CraftProfileBanList;
import org.bukkit.craftbukkit.block.data.CraftBlockData;
import org.bukkit.craftbukkit.boss.CraftBossBar;
import org.bukkit.craftbukkit.command.CraftCommandMap;
import org.bukkit.craftbukkit.command.VanillaCommandWrapper;
import org.bukkit.craftbukkit.entity.CraftEntity;
import org.bukkit.craftbukkit.entity.CraftEntityFactory;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.craftbukkit.event.CraftEventFactory;
import org.bukkit.craftbukkit.generator.CraftWorldInfo;
import org.bukkit.craftbukkit.generator.OldCraftChunkData;
import org.bukkit.craftbukkit.help.SimpleHelpMap;
import org.bukkit.craftbukkit.inventory.CraftBlastingRecipe;
import org.bukkit.craftbukkit.inventory.CraftCampfireRecipe;
import org.bukkit.craftbukkit.inventory.CraftFurnaceRecipe;
import org.bukkit.craftbukkit.inventory.CraftItemCraftResult;
import org.bukkit.craftbukkit.inventory.CraftItemFactory;
import org.bukkit.craftbukkit.inventory.CraftItemStack;
import org.bukkit.craftbukkit.inventory.CraftMerchantCustom;
import org.bukkit.craftbukkit.inventory.CraftRecipe;
import org.bukkit.craftbukkit.inventory.CraftShapedRecipe;
import org.bukkit.craftbukkit.inventory.CraftShapelessRecipe;
import org.bukkit.craftbukkit.inventory.CraftSmithingTransformRecipe;
import org.bukkit.craftbukkit.inventory.CraftSmithingTrimRecipe;
import org.bukkit.craftbukkit.inventory.CraftSmokingRecipe;
import org.bukkit.craftbukkit.inventory.CraftStonecuttingRecipe;
import org.bukkit.craftbukkit.inventory.CraftTransmuteRecipe;
import org.bukkit.craftbukkit.inventory.RecipeIterator;
import org.bukkit.craftbukkit.inventory.util.CraftInventoryCreator;
import org.bukkit.craftbukkit.map.CraftMapColorCache;
import org.bukkit.craftbukkit.map.CraftMapView;
import org.bukkit.craftbukkit.map.CraftMapCursor.CraftType;
import org.bukkit.craftbukkit.metadata.EntityMetadataStore;
import org.bukkit.craftbukkit.metadata.PlayerMetadataStore;
import org.bukkit.craftbukkit.metadata.WorldMetadataStore;
import org.bukkit.craftbukkit.packs.CraftResourcePack;
import org.bukkit.craftbukkit.profile.CraftPlayerProfile;
import org.bukkit.craftbukkit.scheduler.CraftScheduler;
import org.bukkit.craftbukkit.scoreboard.CraftCriteria;
import org.bukkit.craftbukkit.scoreboard.CraftScoreboardManager;
import org.bukkit.craftbukkit.structure.CraftStructureManager;
import org.bukkit.craftbukkit.tag.CraftBlockTag;
import org.bukkit.craftbukkit.tag.CraftDamageTag;
import org.bukkit.craftbukkit.tag.CraftEntityTag;
import org.bukkit.craftbukkit.tag.CraftFluidTag;
import org.bukkit.craftbukkit.tag.CraftGameEventTag;
import org.bukkit.craftbukkit.tag.CraftItemTag;
import org.bukkit.craftbukkit.util.ApiVersion;
import org.bukkit.craftbukkit.util.CraftChatMessage;
import org.bukkit.craftbukkit.util.CraftIconCache;
import org.bukkit.craftbukkit.util.CraftLocation;
import org.bukkit.craftbukkit.util.CraftMagicNumbers;
import org.bukkit.craftbukkit.util.CraftNamespacedKey;
import org.bukkit.craftbukkit.util.CraftSpawnCategory;
import org.bukkit.craftbukkit.util.Versioning;
import org.bukkit.craftbukkit.util.permissions.CraftDefaultPermissions;
import org.bukkit.entity.Player;
import org.bukkit.entity.SpawnCategory;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.server.BroadcastMessageEvent;
import org.bukkit.event.server.ServerLoadEvent;
import org.bukkit.event.server.ServerLoadEvent.LoadType;
import org.bukkit.event.world.WorldUnloadEvent;
import org.bukkit.generator.BiomeProvider;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.generator.WorldInfo;
import org.bukkit.generator.ChunkGenerator.ChunkData;
import org.bukkit.help.HelpMap;
import org.bukkit.inventory.BlastingRecipe;
import org.bukkit.inventory.CampfireRecipe;
import org.bukkit.inventory.ComplexRecipe;
import org.bukkit.inventory.FurnaceRecipe;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemCraftResult;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Merchant;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.ShapelessRecipe;
import org.bukkit.inventory.SmithingTransformRecipe;
import org.bukkit.inventory.SmithingTrimRecipe;
import org.bukkit.inventory.SmokingRecipe;
import org.bukkit.inventory.StonecuttingRecipe;
import org.bukkit.inventory.TransmuteRecipe;
import org.bukkit.loot.LootTable;
import org.bukkit.map.MapPalette;
import org.bukkit.map.MapCursor.Type;
import org.bukkit.map.MapView.Scale;
import org.bukkit.packs.ResourcePack;
import org.bukkit.permissions.Permissible;
import org.bukkit.permissions.Permission;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginLoadOrder;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.ServicesManager;
import org.bukkit.plugin.SimplePluginManager;
import org.bukkit.plugin.SimpleServicesManager;
import org.bukkit.plugin.messaging.Messenger;
import org.bukkit.plugin.messaging.StandardMessenger;
import org.bukkit.profile.PlayerProfile;
import org.bukkit.scheduler.BukkitWorker;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.structure.StructureManager;
import org.bukkit.util.StructureSearchResult;
import org.bukkit.util.permissions.DefaultPermissions;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.spigotmc.AsyncCatcher;
import org.spigotmc.RestartCommand;
import org.spigotmc.SpigotConfig;
import org.spigotmc.WatchdogThread;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.MarkedYAMLException;

public final class CraftServer implements Server {
   private final String serverName = ServerBuildInfo.buildInfo().brandName();
   private final String serverVersion;
   private final String bukkitVersion = Versioning.getBukkitVersion();
   private final Logger logger = Logger.getLogger("Minecraft");
   private final ServicesManager servicesManager = new SimpleServicesManager();
   private final CraftScheduler scheduler = new CraftScheduler();
   private final CraftCommandMap commandMap;
   private final SimpleHelpMap helpMap = new SimpleHelpMap(this);
   private final StandardMessenger messenger = new StandardMessenger();
   private final SimplePluginManager pluginManager;
   public final PaperPluginManagerImpl paperPluginManager;
   private final StructureManager structureManager;
   final DedicatedServer console;
   private final DedicatedPlayerList playerList;
   private final Map<String, World> worlds = new LinkedHashMap<>();
   private YamlConfiguration configuration;
   private YamlConfiguration commandsConfiguration;
   private final Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
   private final Map<UUID, OfflinePlayer> offlinePlayers = new MapMaker().weakValues().makeMap();
   private final EntityMetadataStore entityMetadata = new EntityMetadataStore();
   private final PlayerMetadataStore playerMetadata = new PlayerMetadataStore();
   private final WorldMetadataStore worldMetadata = new WorldMetadataStore();
   private final Object2IntOpenHashMap<SpawnCategory> spawnCategoryLimit = new Object2IntOpenHashMap();
   private File container;
   private WarningState warningState = WarningState.DEFAULT;
   public ApiVersion minimumAPI;
   public CraftScoreboardManager scoreboardManager;
   private final CraftServerTickManager serverTickManager;
   private final CraftServerLinks serverLinks;
   private boolean printSaveWarning;
   private CraftIconCache icon;
   private boolean overrideAllCommandBlockCommands = false;
   public boolean ignoreVanillaPermissions = false;
   private final List<CraftPlayer> playerView;
   public int reloadCount;
   public Set<String> activeCompatibilities = Collections.emptySet();
   private final PaperDatapackManager datapackManager;
   public static Exception excessiveVelEx;
   private final SysoutCatcher sysoutCatcher = new SysoutCatcher();
   private final PaperPotionBrewer potionBrewer;
   public final SparksFly spark;
   private final ServerConfiguration serverConfig = new PaperServerConfiguration();
   private final FallbackRegionScheduler regionizedScheduler = new FallbackRegionScheduler();
   private final FoliaAsyncScheduler asyncScheduler = new FoliaAsyncScheduler();
   private final FoliaGlobalRegionScheduler globalRegionScheduler = new FoliaGlobalRegionScheduler();
   private final Spigot spigot = new 2(this);
   private Iterable<? extends Audience> adventure$audiences;
   private MobGoals mobGoals = new PaperMobGoals();

   public final RegionScheduler getRegionScheduler() {
      return this.regionizedScheduler;
   }

   public final AsyncScheduler getAsyncScheduler() {
      return this.asyncScheduler;
   }

   public final FoliaGlobalRegionScheduler getGlobalRegionScheduler() {
      return this.globalRegionScheduler;
   }

   public final boolean isOwnedByCurrentRegion(World world, Position position) {
      return TickThread.isTickThreadFor(((CraftWorld)world).getHandle(), position.blockX() >> 4, position.blockZ() >> 4);
   }

   public final boolean isOwnedByCurrentRegion(World world, Position position, int squareRadiusChunks) {
      return TickThread.isTickThreadFor(((CraftWorld)world).getHandle(), position.blockX() >> 4, position.blockZ() >> 4, squareRadiusChunks);
   }

   public final boolean isOwnedByCurrentRegion(Location location) {
      World world = location.getWorld();
      return TickThread.isTickThreadFor(((CraftWorld)world).getHandle(), location.getBlockX() >> 4, location.getBlockZ() >> 4);
   }

   public final boolean isOwnedByCurrentRegion(Location location, int squareRadiusChunks) {
      World world = location.getWorld();
      return TickThread.isTickThreadFor(((CraftWorld)world).getHandle(), location.getBlockX() >> 4, location.getBlockZ() >> 4, squareRadiusChunks);
   }

   public final boolean isOwnedByCurrentRegion(World world, int chunkX, int chunkZ) {
      return TickThread.isTickThreadFor(((CraftWorld)world).getHandle(), chunkX, chunkZ);
   }

   public final boolean isOwnedByCurrentRegion(World world, int chunkX, int chunkZ, int squareRadiusChunks) {
      return TickThread.isTickThreadFor(((CraftWorld)world).getHandle(), chunkX, chunkZ, squareRadiusChunks);
   }

   public final boolean isOwnedByCurrentRegion(World world, int minChunkX, int minChunkZ, int maxChunkX, int maxChunkZ) {
      return TickThread.isTickThreadFor(((CraftWorld)world).getHandle(), minChunkX, minChunkZ, maxChunkX, maxChunkZ);
   }

   public final boolean isOwnedByCurrentRegion(org.bukkit.entity.Entity entity) {
      return TickThread.isTickThreadFor(((CraftEntity)entity).getHandleRaw());
   }

   public final boolean isGlobalTickThread() {
      return TickThread.isTickThread();
   }

   public CraftServer(DedicatedServer console, PlayerList playerList) {
      this.console = console;
      this.playerList = (DedicatedPlayerList)playerList;
      this.playerView = MCUtil.transformUnmodifiable(playerList.getPlayers(), ServerPlayer::getBukkitEntity);
      this.serverVersion = ServerBuildInfo.buildInfo().asString(StringRepresentation.VERSION_SIMPLE);
      this.structureManager = new CraftStructureManager(console.getStructureManager(), console.registryAccess());
      this.serverTickManager = new CraftServerTickManager(console.tickRateManager());
      this.serverLinks = new CraftServerLinks(console);
      Bukkit.setServer(this);
      this.commandMap = new CraftCommandMap(this);
      this.pluginManager = new SimplePluginManager(this, this.commandMap);
      this.paperPluginManager = new PaperPluginManagerImpl(this, this.commandMap, this.pluginManager);
      this.pluginManager.paperPluginManager = this.paperPluginManager;
      CraftRegistry.setMinecraftRegistry(console.registryAccess());
      if (!Main.useConsole) {
         this.getLogger().info("Console input is disabled due to --noconsole command argument");
      }

      this.configuration = YamlConfiguration.loadConfiguration(this.getConfigFile());
      this.configuration.options().copyDefaults(true);
      YamlConfiguration configurationDefaults = YamlConfiguration.loadConfiguration(
         new InputStreamReader(this.getClass().getClassLoader().getResourceAsStream("configurations/bukkit.yml"), StandardCharsets.UTF_8)
      );
      this.configuration.setDefaults(configurationDefaults);
      this.configuration.options().setHeader(configurationDefaults.options().getHeader());
      ConfigurationSection legacyAlias = null;
      if (!this.configuration.isString("aliases")) {
         legacyAlias = this.configuration.getConfigurationSection("aliases");
         this.configuration.set("aliases", "now-in-commands.yml");
      }

      this.saveConfig();
      if (this.getCommandsConfigFile().isFile()) {
         legacyAlias = null;
      }

      this.commandsConfiguration = YamlConfiguration.loadConfiguration(this.getCommandsConfigFile());
      this.commandsConfiguration.options().copyDefaults(true);
      YamlConfiguration commandsDefaults = YamlConfiguration.loadConfiguration(
         new InputStreamReader(this.getClass().getClassLoader().getResourceAsStream("configurations/commands.yml"), StandardCharsets.UTF_8)
      );
      if (this.commandsConfiguration.contains("aliases")) {
         commandsDefaults.set("aliases", null);
      }

      this.commandsConfiguration.setDefaults(commandsDefaults);
      this.commandsConfiguration.options().setHeader(commandsDefaults.options().getHeader());
      this.saveCommandsConfig();
      if (legacyAlias != null) {
         ConfigurationSection aliases = this.commandsConfiguration.createSection("aliases");

         for (String key : legacyAlias.getKeys(false)) {
            List<String> commands = new ArrayList<>();
            if (legacyAlias.isList(key)) {
               for (String command : legacyAlias.getStringList(key)) {
                  commands.add(command + " $1-");
               }
            } else {
               commands.add(legacyAlias.getString(key) + " $1-");
            }

            aliases.set(key, commands);
         }
      }

      this.saveCommandsConfig();
      this.overrideAllCommandBlockCommands = this.commandsConfiguration.getStringList("command-block-overrides").contains("*");
      this.ignoreVanillaPermissions = this.commandsConfiguration.getBoolean("ignore-vanilla-permissions");
      this.overrideSpawnLimits();
      console.autosavePeriod = this.configuration.getInt("ticks-per.autosave");
      this.warningState = WarningState.value(this.configuration.getString("settings.deprecated-verbose"));
      TicketType.PLUGIN_TYPE_TIMEOUT = this.configuration.getInt("chunk-gc.period-in-ticks");
      this.minimumAPI = ApiVersion.getOrCreateVersion(this.configuration.getString("settings.minimum-api"));
      this.loadIcon();
      this.loadCompatibilities();
      CraftMagicNumbers.INSTANCE.getCommodore().updateReroute(this.activeCompatibilities::contains);
      if (this.configuration.getBoolean("settings.use-map-color-cache")) {
         MapPalette.setMapColorCache(new CraftMapColorCache(this.logger));
      }

      this.potionBrewer = new PaperPotionBrewer(console);
      this.datapackManager = new PaperDatapackManager(console.getPackRepository());
      this.spark = new SparksFly(this);
   }

   public boolean getCommandBlockOverride(String command) {
      return this.overrideAllCommandBlockCommands || this.commandsConfiguration.getStringList("command-block-overrides").contains(command);
   }

   private File getConfigFile() {
      return (File)this.console.options.valueOf("bukkit-settings");
   }

   private File getCommandsConfigFile() {
      return (File)this.console.options.valueOf("commands-settings");
   }

   private void overrideSpawnLimits() {
      for (SpawnCategory spawnCategory : SpawnCategory.values()) {
         if (CraftSpawnCategory.isValidForLimits(spawnCategory)) {
            this.spawnCategoryLimit.put(spawnCategory, this.configuration.getInt(CraftSpawnCategory.getConfigNameSpawnLimit(spawnCategory)));
         }
      }
   }

   private void saveConfig() {
      try {
         this.configuration.save(this.getConfigFile());
      } catch (IOException ex) {
         Logger.getLogger(CraftServer.class.getName()).log(java.util.logging.Level.SEVERE, "Could not save " + this.getConfigFile(), ex);
      }
   }

   private void saveCommandsConfig() {
      try {
         this.commandsConfiguration.save(this.getCommandsConfigFile());
      } catch (IOException ex) {
         Logger.getLogger(CraftServer.class.getName()).log(java.util.logging.Level.SEVERE, "Could not save " + this.getCommandsConfigFile(), ex);
      }
   }

   private void loadCompatibilities() {
   }

   public void loadPlugins() {
      LaunchEntryPointHandler.INSTANCE.enter(Entrypoint.PLUGIN);
   }

   public File getPluginsFolder() {
      return this.console.getPluginsFolder();
   }

   private List<File> extraPluginJars() {
      List<File> jars = this.console.options.valuesOf("add-plugin");
      List<File> list = new ArrayList<>();

      for (File file : jars) {
         if (!file.exists()) {
            MinecraftServer.LOGGER
               .warn("File '{}' specified through 'add-plugin' argument does not exist, cannot load a plugin from it!", file.getAbsolutePath());
         } else if (!file.isFile()) {
            MinecraftServer.LOGGER
               .warn("File '{}' specified through 'add-plugin' argument is not a file, cannot load a plugin from it!", file.getAbsolutePath());
         } else if (!file.getName().endsWith(".jar")) {
            MinecraftServer.LOGGER
               .warn("File '{}' specified through 'add-plugin' argument is not a jar file, cannot load a plugin from it!", file.getAbsolutePath());
         } else {
            list.add(file);
         }
      }

      return list;
   }

   public void enablePlugins(PluginLoadOrder type) {
      if (type == PluginLoadOrder.STARTUP) {
         this.helpMap.clear();
         this.helpMap.initializeGeneralTopics();
         if (GlobalConfiguration.get().misc.loadPermissionsYmlBeforePlugins) {
            this.loadCustomPermissions();
         }
      }

      Plugin[] plugins = this.pluginManager.getPlugins();

      for (Plugin plugin : plugins) {
         if (!plugin.isEnabled() && plugin.getDescription().getLoad() == type) {
            this.enablePlugin(plugin);
         }
      }

      if (type == PluginLoadOrder.POSTWORLD) {
         this.commandMap.setFallbackCommands();
         DefaultPermissions.registerCorePermissions();
         CraftDefaultPermissions.registerCorePermissions();
         if (!GlobalConfiguration.get().misc.loadPermissionsYmlBeforePlugins) {
            this.loadCustomPermissions();
         }

         this.syncCommands();
      }
   }

   public void disablePlugins() {
      this.pluginManager.disablePlugins();
   }

   public void syncCommands() {
      Commands dispatcher = this.getHandle().getServer().getCommands();

      for (ServerPlayer player : this.getHandle().getPlayers()) {
         dispatcher.sendCommands(player);
      }
   }

   private void enablePlugin(Plugin plugin) {
      try {
         List<Permission> perms = plugin.getDescription().getPermissions();
         List<Permission> permsToLoad = new ArrayList<>();

         for (Permission perm : perms) {
            if (this.paperPluginManager.getPermission(perm.getName()) == null) {
               permsToLoad.add(perm);
            } else {
               this.getLogger()
                  .log(
                     java.util.logging.Level.WARNING,
                     "Plugin " + plugin.getDescription().getFullName() + " tried to register permission '" + perm.getName() + "' but it's already registered"
                  );
            }
         }

         this.paperPluginManager.addPermissions(permsToLoad);
         this.pluginManager.enablePlugin(plugin);
      } catch (Throwable ex) {
         Logger.getLogger(CraftServer.class.getName())
            .log(java.util.logging.Level.SEVERE, ex.getMessage() + " loading " + plugin.getDescription().getFullName() + " (Is it up to date?)", ex);
      }
   }

   public String getName() {
      return this.serverName;
   }

   public String getVersion() {
      return this.serverVersion + " (MC: " + this.console.getServerVersion() + ")";
   }

   public String getBukkitVersion() {
      return this.bukkitVersion;
   }

   public String getMinecraftVersion() {
      return this.console.getServerVersion();
   }

   public List<CraftPlayer> getOnlinePlayers() {
      return this.playerView;
   }

   @Deprecated
   public Player getPlayer(String name) {
      Preconditions.checkArgument(name != null, "name cannot be null");
      Player found = this.getPlayerExact(name);
      if (found != null) {
         return found;
      }

      String lowerName = name.toLowerCase(Locale.ROOT);
      int delta = Integer.MAX_VALUE;

      for (Player player : this.getOnlinePlayers()) {
         if (player.getName().toLowerCase(Locale.ROOT).startsWith(lowerName)) {
            int curDelta = Math.abs(player.getName().length() - lowerName.length());
            if (curDelta < delta) {
               found = player;
               delta = curDelta;
            }

            if (curDelta == 0) {
               break;
            }
         }
      }

      return found;
   }

   public Player getPlayerExact(String name) {
      Preconditions.checkArgument(name != null, "name cannot be null");
      ServerPlayer player = this.playerList.getPlayerByName(name);
      return player != null ? player.getBukkitEntity() : null;
   }

   public Player getPlayer(UUID id) {
      Preconditions.checkArgument(id != null, "UUID id cannot be null");
      ServerPlayer player = this.playerList.getPlayer(id);
      return player != null ? player.getBukkitEntity() : null;
   }

   @Deprecated
   public List<Player> matchPlayer(String partialName) {
      Preconditions.checkArgument(partialName != null, "partialName cannot be null");
      List<Player> matchedPlayers = new ArrayList<>();

      for (Player iterPlayer : this.getOnlinePlayers()) {
         String iterPlayerName = iterPlayer.getName();
         if (partialName.equalsIgnoreCase(iterPlayerName)) {
            matchedPlayers.clear();
            matchedPlayers.add(iterPlayer);
            break;
         }

         if (iterPlayerName.toLowerCase(Locale.ROOT).contains(partialName.toLowerCase(Locale.ROOT))) {
            matchedPlayers.add(iterPlayer);
         }
      }

      return matchedPlayers;
   }

   public int getMaxPlayers() {
      return this.playerList.getMaxPlayers();
   }

   public void setMaxPlayers(int maxPlayers) {
      Preconditions.checkArgument(maxPlayers >= 0, "maxPlayers must be >= 0");
      this.console.setMaxPlayers(maxPlayers);
   }

   public int getPort() {
      return this.getServer().getPort();
   }

   public int getViewDistance() {
      return this.console.viewDistance();
   }

   public int getSimulationDistance() {
      return this.console.simulationDistance();
   }

   public String getIp() {
      return this.getServer().getLocalIp();
   }

   public String getWorldType() {
      return this.getProperties().properties.getProperty("level-type");
   }

   public boolean getGenerateStructures() {
      return this.getServer().getWorldGenSettings().options().generateStructures();
   }

   public int getMaxWorldSize() {
      return this.getProperties().maxWorldSize;
   }

   public boolean getAllowEnd() {
      return this.configuration.getBoolean("settings.allow-end");
   }

   public boolean getAllowNether() {
      return GlobalConfiguration.get().misc.enableNether;
   }

   public boolean isLoggingIPs() {
      return this.getServer().logIPs();
   }

   public boolean getWarnOnOverload() {
      return this.configuration.getBoolean("settings.warn-on-overload");
   }

   public boolean getQueryPlugins() {
      return this.configuration.getBoolean("settings.query-plugins");
   }

   public List<String> getInitialEnabledPacks() {
      return Collections.unmodifiableList(this.getProperties().initialDataPackConfiguration.getEnabled());
   }

   public List<String> getInitialDisabledPacks() {
      return Collections.unmodifiableList(this.getProperties().initialDataPackConfiguration.getDisabled());
   }

   public ServerTickManager getServerTickManager() {
      return this.serverTickManager;
   }

   public ResourcePack getServerResourcePack() {
      return this.getServer().getServerResourcePack().<ResourcePack>map(CraftResourcePack::new).orElse(null);
   }

   public String getResourcePack() {
      return this.getServer().getServerResourcePack().<String>map(ServerResourcePackInfo::url).orElse("");
   }

   public String getResourcePackHash() {
      return this.getServer().getServerResourcePack().<String>map(ServerResourcePackInfo::hash).orElse("").toUpperCase(Locale.ROOT);
   }

   public String getResourcePackPrompt() {
      return this.getServer().getServerResourcePack().map(ServerResourcePackInfo::prompt).<String>map(CraftChatMessage::fromComponent).orElse("");
   }

   public boolean isResourcePackRequired() {
      return this.getServer().isResourcePackRequired();
   }

   public boolean hasWhitelist() {
      return this.playerList.isUsingWhitelist();
   }

   private DedicatedServerProperties getProperties() {
      return this.console.getProperties();
   }

   public String getUpdateFolder() {
      return this.configuration.getString("settings.update-folder", "update");
   }

   public File getUpdateFolderFile() {
      return new File((File)this.console.options.valueOf("plugins"), this.configuration.getString("settings.update-folder", "update"));
   }

   public long getConnectionThrottle() {
      return !SpigotConfig.bungee && !GlobalConfiguration.get().proxies.velocity.enabled ? this.configuration.getInt("settings.connection-throttle") : -1L;
   }

   public int getTicksPerSpawns(SpawnCategory spawnCategory) {
      Preconditions.checkArgument(spawnCategory != null, "SpawnCategory cannot be null");
      Preconditions.checkArgument(CraftSpawnCategory.isValidForLimits(spawnCategory), "SpawnCategory.%s are not supported", spawnCategory);
      return this.configuration.getInt(CraftSpawnCategory.getConfigNameTicksPerSpawn(spawnCategory));
   }

   public PluginManager getPluginManager() {
      return this.pluginManager;
   }

   public CraftScheduler getScheduler() {
      return this.scheduler;
   }

   public ServicesManager getServicesManager() {
      return this.servicesManager;
   }

   public List<World> getWorlds() {
      return new ArrayList<>(this.worlds.values());
   }

   public boolean isTickingWorlds() {
      return this.console.isIteratingOverLevels;
   }

   public DedicatedPlayerList getHandle() {
      return this.playerList;
   }

   public boolean dispatchCommand(CommandSender rawSender, String commandLine) {
      Preconditions.checkArgument(rawSender != null, "sender cannot be null");
      Preconditions.checkArgument(commandLine != null, "commandLine cannot be null");
      AsyncCatcher.catchOp("Command Dispatched Async: " + commandLine);
      CommandSourceStack sourceStack = VanillaCommandWrapper.getListener(rawSender);
      String command = StringUtils.normalizeSpace(commandLine.trim());
      Commands commands = this.getHandle().getServer().getCommands();
      CommandDispatcher<CommandSourceStack> dispatcher = commands.getDispatcher();
      ParseResults<CommandSourceStack> results = dispatcher.parse(command, sourceStack);
      CommandSender sender = sourceStack.getBukkitSender();
      String[] args = StringUtils.split(command, ' ');
      Command target = this.commandMap.getCommand(args[0].toLowerCase(Locale.ENGLISH));

      try {
         if (results.getContext().getNodes().isEmpty()) {
            return false;
         }

         Commands.validateParseResults(results);
         commands.performCommand(results, command, true);
         return true;
      } catch (CommandException ex) {
         new ServerExceptionEvent(new ServerCommandException(ex, target, sender, args)).callEvent();
         throw ex;
      } catch (Throwable ex) {
         String msg = "Unhandled exception executing '" + command + "' in " + target;
         new ServerExceptionEvent(new ServerCommandException(ex, target, sender, args)).callEvent();
         throw new CommandException(msg, ex);
      }
   }

   public void reload() {
      if (LifecycleEventRunner.INSTANCE.blocksPluginReloading()) {
         throw new IllegalStateException("A lifecycle event handler has been registered which makes reloading plugins not possible");
      }

      WatchdogThread.hasStarted = false;
      this.reloadCount++;
      this.configuration = YamlConfiguration.loadConfiguration(this.getConfigFile());
      this.commandsConfiguration = YamlConfiguration.loadConfiguration(this.getCommandsConfigFile());
      this.console.settings = new DedicatedServerSettings(this.console.options);
      DedicatedServerProperties config = this.console.settings.getProperties();
      this.console.setMotd((String)config.motd.get());
      this.overrideSpawnLimits();
      this.warningState = WarningState.value(this.configuration.getString("settings.deprecated-verbose"));
      TicketType.PLUGIN_TYPE_TIMEOUT = this.configuration.getInt("chunk-gc.period-in-ticks");
      this.minimumAPI = ApiVersion.getOrCreateVersion(this.configuration.getString("settings.minimum-api"));
      this.printSaveWarning = false;
      this.console.autosavePeriod = this.configuration.getInt("ticks-per.autosave");
      this.loadIcon();
      this.loadCompatibilities();
      CraftMagicNumbers.INSTANCE.getCommodore().updateReroute(this.activeCompatibilities::contains);

      try {
         this.playerList.getIpBans().load();
      } catch (IOException ex) {
         this.logger.log(java.util.logging.Level.WARNING, "Failed to load banned-ips.json, " + ex.getMessage());
      }

      try {
         this.playerList.getBans().load();
      } catch (IOException ex) {
         this.logger.log(java.util.logging.Level.WARNING, "Failed to load banned-players.json, " + ex.getMessage());
      }

      SpigotConfig.init((File)this.console.options.valueOf("spigot-settings"));
      this.console.paperConfigurations.reloadConfigs(this.console);

      for (ServerLevel world : this.console.getAllLevels()) {
         world.setSpawnSettings(world.isSpawningMonsters());

         for (SpawnCategory spawnCategory : SpawnCategory.values()) {
            if (CraftSpawnCategory.isValidForLimits(spawnCategory)) {
               long ticksPerCategorySpawn = this.getTicksPerSpawns(spawnCategory);
               if (ticksPerCategorySpawn < 0L) {
                  world.ticksPerSpawnCategory.put(spawnCategory, CraftSpawnCategory.getDefaultTicksPerSpawn(spawnCategory));
               } else {
                  world.ticksPerSpawnCategory.put(spawnCategory, ticksPerCategorySpawn);
               }
            }
         }

         world.spigotConfig.init();
      }

      Plugin[] pluginClone = (Plugin[])this.pluginManager.getPlugins().clone();
      this.commandMap.clearCommands();
      this.pluginManager.clearPlugins();

      for (Plugin plugin : pluginClone) {
         this.entityMetadata.removeAll(plugin);
         this.worldMetadata.removeAll(plugin);
         this.playerMetadata.removeAll(plugin);
      }

      this.reloadData();
      SpigotConfig.registerCommands();
      PaperCommands.registerCommands(this.console);
      this.spark.registerCommandBeforePlugins(this);
      this.overrideAllCommandBlockCommands = this.commandsConfiguration.getStringList("command-block-overrides").contains("*");
      this.ignoreVanillaPermissions = this.commandsConfiguration.getBoolean("ignore-vanilla-permissions");

      for (int pollCount = 0; pollCount < 50 && this.getScheduler().getActiveWorkers().size() > 0; pollCount++) {
         try {
            Thread.sleep(50L);
         } catch (InterruptedException var10) {
         }
      }

      for (BukkitWorker worker : this.getScheduler().getActiveWorkers()) {
         Plugin plugin = worker.getOwner();
         this.getLogger()
            .log(
               java.util.logging.Level.SEVERE,
               String.format(
                  "Nag author(s): '%s' of '%s' about the following: %s",
                  plugin.getDescription().getAuthors(),
                  plugin.getDescription().getFullName(),
                  "This plugin is not properly shutting down its async tasks when it is being reloaded.  This may cause conflicts with the newly loaded version of the plugin"
               )
            );
         if (this.console.isDebugging()) {
            TraceUtil.dumpTraceForThread(worker.getThread(), "still running");
         }
      }

      PluginInitializerManager.reload(this.console);
      this.loadPlugins();
      this.enablePlugins(PluginLoadOrder.STARTUP);
      this.enablePlugins(PluginLoadOrder.POSTWORLD);
      this.spark.registerCommandAfterPlugins(this);
      io.papermc.paper.command.brigadier.PaperCommands.INSTANCE.setValid();
      LifecycleEventRunner.INSTANCE
         .callReloadableRegistrarEvent(LifecycleEvents.COMMANDS, io.papermc.paper.command.brigadier.PaperCommands.INSTANCE, Plugin.class, Cause.RELOAD);
      this.helpMap.initializeCommands();
      this.syncCommands();
      new ServerLoadEvent(LoadType.RELOAD).callEvent();
      WatchdogThread.hasStarted = true;
   }

   public void waitForAsyncTasksShutdown() {
      for (int pollCount = 0; pollCount < 50 && this.getScheduler().getActiveWorkers().size() > 0; pollCount++) {
         try {
            Thread.sleep(100L);
         } catch (InterruptedException var6) {
         }
      }

      for (BukkitWorker worker : this.getScheduler().getActiveWorkers()) {
         Plugin plugin = worker.getOwner();
         this.getLogger()
            .log(
               java.util.logging.Level.SEVERE,
               String.format(
                  "Nag author(s): '%s' of '%s' about the following: %s",
                  plugin.getPluginMeta().getAuthors(),
                  plugin.getPluginMeta().getDisplayName(),
                  "This plugin is not properly shutting down its async tasks when it is being shut down. This task may throw errors during the final shutdown logs and might not complete before process dies."
               )
            );
         if (this.console.isDebugging()) {
            TraceUtil.dumpTraceForThread(worker.getThread(), "still running");
         }
      }
   }

   public void reloadData() {
      ReloadCommand.reload(this.console);
   }

   public void updateResources() {
      this.playerList.reloadResources();
   }

   public void updateRecipes() {
      this.playerList.reloadRecipes();
   }

   private void loadIcon() {
      this.icon = new CraftIconCache(null);

      try {
         File file = new File(new File("."), "server-icon.png");
         if (file.isFile()) {
            this.icon = loadServerIcon0(file);
         }
      } catch (Exception ex) {
         this.getLogger().log(java.util.logging.Level.WARNING, "Couldn't load server icon", ex);
      }
   }

   private void loadCustomPermissions() {
      File file = new File(this.configuration.getString("settings.permissions-file"));
      if (file.isFile()) {
         FileInputStream stream;
         try {
            stream = new FileInputStream(file);
         } catch (FileNotFoundException ex) {
            return;
         }

         Map<String, Map<String, Object>> perms;
         label108: {
            try {
               perms = (Map<String, Map<String, Object>>)this.yaml.load(stream);
               break label108;
            } catch (MarkedYAMLException ex) {
               this.getLogger().log(java.util.logging.Level.WARNING, "Server permissions file " + file + " is not valid YAML: " + ex.toString());
               return;
            } catch (Throwable ex) {
               this.getLogger().log(java.util.logging.Level.WARNING, "Server permissions file " + file + " is not valid YAML.", ex);
            } finally {
               try {
                  stream.close();
               } catch (IOException var17) {
               }
            }

            return;
         }

         if (perms == null) {
            this.getLogger().log(java.util.logging.Level.INFO, "Server permissions file " + file + " is empty, ignoring it");
         } else {
            for (Permission perm : Permission.loadPermissions(perms, "Permission node '%s' in " + file + " is invalid", Permission.DEFAULT_PERMISSION)) {
               try {
                  this.pluginManager.addPermission(perm);
               } catch (IllegalArgumentException ex) {
                  this.getLogger().log(java.util.logging.Level.SEVERE, "Permission in " + file + " was already defined", ex);
               }
            }
         }
      }
   }

   @Override
   public String toString() {
      return "CraftServer{serverName="
         + this.serverName
         + ",serverVersion="
         + this.serverVersion
         + ",minecraftVersion="
         + this.console.getServerVersion()
         + "}";
   }

   // $VF: Unable to simplify switch on enum
   // Please report this to the Vineflower issue tracker, at https://github.com/Vineflower/vineflower/issues with a copy of the class file (if you have the rights to distribute it!)
   public World createWorld(WorldCreator creator) {
      Preconditions.checkState(this.console.getAllLevels().iterator().hasNext(), "Cannot create additional worlds on STARTUP");
      Preconditions.checkArgument(creator != null, "WorldCreator cannot be null");
      String name = creator.name();
      ChunkGenerator chunkGenerator = creator.generator();
      BiomeProvider biomeProvider = creator.biomeProvider();
      World world = this.getWorld(name);
      World worldByKey = this.getWorld(creator.key());
      if (world == null && worldByKey == null) {
         if (chunkGenerator == null) {
            chunkGenerator = this.getGenerator(name);
         }

         if (biomeProvider == null) {
            biomeProvider = this.getBiomeProvider(name);
         }
         ResourceKey<LevelStem> actualDimension = switch (3.$SwitchMap$org$bukkit$World$Environment[creator.environment().ordinal()]) {
            case 1 -> LevelStem.OVERWORLD;
            case 2 -> LevelStem.NETHER;
            case 3 -> LevelStem.END;
            default -> throw new IllegalArgumentException("Illegal dimension (" + creator.environment() + ")");
         };
         RegistryAccess registryAccess = this.console.registryAccess();
         ResourceKey<Level> dimensionKey = CraftNamespacedKey.toResourceKey(Registries.DIMENSION, creator.key());
         Registry<LevelStem> levelStemRegistry = registryAccess.lookupOrThrow(Registries.LEVEL_STEM);
         LevelStem configuredStem = (LevelStem)levelStemRegistry.getValue(actualDimension);
         if (configuredStem == null) {
            throw new IllegalStateException("Missing configured level stem " + actualDimension);
         }

         try {
            WorldFolderMigration.migrateApiWorld(this.console.storageSource, registryAccess, name, actualDimension, dimensionKey);
         } catch (IOException ex) {
            throw new RuntimeException("Failed to migrate legacy world " + name, ex);
         }

         LoadedWorldData loadedWorldData = PaperWorldLoader.loadWorldData(this.console, dimensionKey, name);
         PrimaryLevelData primaryLevelData = (PrimaryLevelData)this.console.getWorldData();
         WorldGenSettings worldGenSettings = (WorldGenSettings)LevelStorageSource.readExistingSavedData(
               this.console.storageSource, dimensionKey, registryAccess, WorldGenSettings.TYPE
            )
            .result()
            .orElse(null);
         RegistryAccess contextRegistryAccess = registryAccess;
         if (worldGenSettings == null) {
            WorldOptions worldOptions = new WorldOptions(creator.seed(), creator.generateStructures(), creator.bonusChest());
            String flatGenSettings = creator.generatorSettings();
            if (flatGenSettings.isEmpty()) {
               flatGenSettings = ((JsonElement)FlatLevelGeneratorSettings.CODEC
                     .encodeStart(
                        registryAccess.createSerializationContext(JsonOps.INSTANCE),
                        FlatLevelGeneratorSettings.getDefault(
                           registryAccess.lookupOrThrow(Registries.BIOME),
                           registryAccess.lookupOrThrow(Registries.STRUCTURE_SET),
                           registryAccess.lookupOrThrow(Registries.PLACED_FEATURE)
                        )
                     )
                     .getOrThrow())
                  .toString();
            }

            WorldDimensionData properties = new WorldDimensionData(GsonHelper.parse(flatGenSettings), creator.type().name().toLowerCase(Locale.ROOT));
            WorldDimensions worldDimensions = properties.create(registryAccess);
            Complete complete = worldDimensions.bake(levelStemRegistry);
            if (complete.dimensions().getValue(actualDimension) == null) {
               throw new IllegalStateException("Missing generated level stem " + actualDimension + " for world " + name);
            }

            worldGenSettings = new WorldGenSettings(worldOptions, worldDimensions);
            contextRegistryAccess = complete.dimensionsRegistryAccess();
            loadedWorldData.levelOverrides().setHardcore(creator.hardcore());
            loadedWorldData = new LoadedWorldData(loadedWorldData.bukkitName(), loadedWorldData.uuid(), loadedWorldData.pdc(), loadedWorldData.levelOverrides());
         }

         WorldGenSettings genSettingsFinal = worldGenSettings;
         levelStemRegistry = contextRegistryAccess.lookupOrThrow(Registries.LEVEL_STEM);
         if (this.console.options.has("forceUpgrade")) {
            net.minecraft.server.Main.forceUpgrade(
               this.console.storageSource,
               DataFixers.getDataFixer(),
               this.console.options.has("eraseCache"),
               () -> true,
               contextRegistryAccess,
               this.console.options.has("recreateRegionFiles")
            );
         }

         long biomeZoomSeed = BiomeManager.obfuscateSeed(genSettingsFinal.options().seed());
         LevelStem customStem = (LevelStem)genSettingsFinal.dimensions().get(actualDimension).orElse(null);
         if (customStem == null) {
            customStem = (LevelStem)levelStemRegistry.getValue(actualDimension);
         }

         if (customStem == null) {
            throw new IllegalStateException("Missing level stem for world " + name + " using key " + actualDimension);
         }

         WorldInfo worldInfo = new CraftWorldInfo(
            loadedWorldData.bukkitName(),
            CraftNamespacedKey.fromMinecraft(dimensionKey.identifier()),
            genSettingsFinal.options().seed(),
            primaryLevelData.enabledFeatures(),
            creator.environment(),
            (DimensionType)customStem.type().value(),
            customStem.generator(),
            registryAccess,
            loadedWorldData.uuid()
         );
         if (biomeProvider == null && chunkGenerator != null) {
            biomeProvider = chunkGenerator.getDefaultBiomeProvider(worldInfo);
         }

         SavedDataStorage savedDataStorage = new SavedDataStorage(
            this.console.storageSource.getDimensionPath(dimensionKey).resolve(LevelResource.DATA.id()), this.console.getFixerUpper(), registryAccess
         );
         savedDataStorage.set(WorldGenSettings.TYPE, new WorldGenSettings(genSettingsFinal.options(), genSettingsFinal.dimensions()));
         List<CustomSpawner> list = ImmutableList.of(
            new PhantomSpawner(), new PatrolSpawner(), new CatSpawner(), new VillageSiege(), new WanderingTraderSpawner(savedDataStorage)
         );
         ServerLevel serverLevel = new ServerLevel(
            this.console,
            Util.backgroundExecutor(),
            this.console.storageSource,
            genSettingsFinal,
            dimensionKey,
            customStem,
            primaryLevelData.isDebugWorld(),
            biomeZoomSeed,
            (List)(creator.environment() == Environment.NORMAL ? list : ImmutableList.of()),
            true,
            actualDimension,
            creator.environment(),
            chunkGenerator,
            biomeProvider,
            savedDataStorage,
            loadedWorldData
         );
         if (!this.worlds.containsKey(name.toLowerCase(Locale.ROOT))) {
            return null;
         }

         this.console.addLevel(serverLevel);
         this.console.initWorld(serverLevel, creator);
         serverLevel.setSpawnSettings(true);
         this.getServer().prepareLevel(serverLevel);
         return serverLevel.getWorld();
      } else if (world == worldByKey) {
         return world;
      } else {
         throw new IllegalArgumentException(
            "Cannot create a world with key " + creator.key() + " and name " + name + " one (or both) already match a world that exists"
         );
      }
   }

   public boolean unloadWorld(String name, boolean save) {
      return this.unloadWorld(this.getWorld(name), save);
   }

   public boolean unloadWorld(World world, boolean save) {
      if (world == null) {
         return false;
      }

      ServerLevel handle = ((CraftWorld)world).getHandle();
      if (this.console.getLevel(handle.dimension()) == null) {
         return false;
      }

      if (handle.dimension() == Level.OVERWORLD) {
         return false;
      }

      if (!handle.players().isEmpty()) {
         return false;
      }

      WorldUnloadEvent event = new WorldUnloadEvent(handle.getWorld());
      if (!event.callEvent()) {
         return false;
      }

      try {
         if (save) {
            handle.save(null, true, false);
         }

         handle.getChunkSource().close(save);
         FeatureHooks.closeEntityManager(handle, save);
      } catch (Exception ex) {
         this.getLogger().log(java.util.logging.Level.SEVERE, null, ex);
      }

      this.worlds.remove(world.getName().toLowerCase(Locale.ROOT));
      this.console.removeLevel(handle);
      return true;
   }

   public World getRespawnWorld() {
      return this.console.findRespawnDimension().getWorld();
   }

   public void setRespawnWorld(World world) {
      Preconditions.checkArgument(world != null, "world cannot be null");
      ((PrimaryLevelData)this.console.getWorldData()).respawnDimension = ((CraftWorld)world).getHandle().dimension();
      this.console.updateEffectiveRespawnData();
   }

   public DedicatedServer getServer() {
      return this.console;
   }

   public World getWorld(String name) {
      Preconditions.checkArgument(name != null, "name cannot be null");
      return this.worlds.get(name.toLowerCase(Locale.ROOT));
   }

   public World getWorld(UUID uid) {
      for (World world : this.worlds.values()) {
         if (world.getUID().equals(uid)) {
            return world;
         }
      }

      return null;
   }

   public World getWorld(Key worldKey) {
      ServerLevel level = this.console.getLevel(ResourceKey.create(Registries.DIMENSION, PaperAdventure.asVanilla(worldKey)));
      return level == null ? null : level.getWorld();
   }

   public void addWorld(World world) {
      if (this.getWorld(world.getUID()) != null) {
         System.out
            .println(
               "World "
                  + world.key().asString()
                  + " is a duplicate of another world and has been prevented from loading. Please remove or change the duplicated Paper world metadata before loading it again."
            );
      } else {
         this.worlds.put(world.getName().toLowerCase(Locale.ROOT), world);
      }
   }

   public WorldBorder createWorldBorder() {
      net.minecraft.world.level.border.WorldBorder border = new net.minecraft.world.level.border.WorldBorder();
      border.setWarningTime(Settings.DEFAULT.warningTime());
      return new CraftWorldBorder(border);
   }

   public Logger getLogger() {
      return this.logger;
   }

   public PluginCommand getPluginCommand(String name) {
      Command command = this.commandMap.getCommand(name);
      return command instanceof PluginCommand ? (PluginCommand)command : null;
   }

   public void savePlayers() {
      this.checkSaveState();
      this.playerList.saveAll();
   }

   public boolean addRecipe(Recipe recipe, boolean resendRecipes) {
      if (!(recipe instanceof CraftRecipe toAdd)) {
         if (recipe instanceof ShapedRecipe) {
            toAdd = CraftShapedRecipe.fromBukkitRecipe((ShapedRecipe)recipe);
         } else if (recipe instanceof ShapelessRecipe) {
            toAdd = CraftShapelessRecipe.fromBukkitRecipe((ShapelessRecipe)recipe);
         } else if (recipe instanceof FurnaceRecipe) {
            toAdd = CraftFurnaceRecipe.fromBukkitRecipe((FurnaceRecipe)recipe);
         } else if (recipe instanceof BlastingRecipe) {
            toAdd = CraftBlastingRecipe.fromBukkitRecipe((BlastingRecipe)recipe);
         } else if (recipe instanceof CampfireRecipe) {
            toAdd = CraftCampfireRecipe.fromBukkitRecipe((CampfireRecipe)recipe);
         } else if (recipe instanceof SmokingRecipe) {
            toAdd = CraftSmokingRecipe.fromBukkitRecipe((SmokingRecipe)recipe);
         } else if (recipe instanceof StonecuttingRecipe) {
            toAdd = CraftStonecuttingRecipe.fromBukkitRecipe((StonecuttingRecipe)recipe);
         } else if (recipe instanceof SmithingTransformRecipe) {
            toAdd = CraftSmithingTransformRecipe.fromBukkitRecipe((SmithingTransformRecipe)recipe);
         } else if (recipe instanceof SmithingTrimRecipe) {
            toAdd = CraftSmithingTrimRecipe.fromBukkitRecipe((SmithingTrimRecipe)recipe);
         } else {
            if (!(recipe instanceof TransmuteRecipe)) {
               if (recipe instanceof ComplexRecipe) {
                  throw new UnsupportedOperationException("Cannot add custom complex recipe");
               }

               return false;
            }

            toAdd = CraftTransmuteRecipe.fromBukkitRecipe((TransmuteRecipe)recipe);
         }
      }

      toAdd.addToRecipeManager();
      this.playerList.reloadRecipes();
      return true;
   }

   public List<Recipe> getRecipesFor(ItemStack result) {
      Preconditions.checkArgument(result != null, "ItemStack cannot be null");
      List<Recipe> results = new ArrayList<>();
      Iterator<Recipe> iter = this.recipeIterator();

      while (iter.hasNext()) {
         Recipe recipe = iter.next();
         ItemStack stack = recipe.getResult();
         if (stack.getType() == result.getType() && (result.getDurability() == -1 || result.getDurability() == stack.getDurability())) {
            results.add(recipe);
         }
      }

      return results;
   }

   public Recipe getRecipe(NamespacedKey recipeKey) {
      Preconditions.checkArgument(recipeKey != null, "NamespacedKey recipeKey cannot be null");
      return this.getServer()
         .getRecipeManager()
         .byKey(CraftNamespacedKey.toResourceKey(Registries.RECIPE, recipeKey))
         .<Recipe>map(RecipeHolder::toBukkitRecipe)
         .orElse(null);
   }

   private CraftingContainer createCraftingContainer() {
      AbstractContainerMenu container = new 1(this, null, -1);
      return new TransientCraftingContainer(container, 3, 3);
   }

   public Recipe getCraftingRecipe(ItemStack[] craftingMatrix, World world) {
      return this.getNMSRecipe(craftingMatrix, this.createCraftingContainer(), (CraftWorld)world).<Recipe>map(RecipeHolder::toBukkitRecipe).orElse(null);
   }

   public ItemCraftResult craftItemResult(ItemStack[] craftingMatrix, World world, Player player) {
      Preconditions.checkArgument(world != null, "world cannot be null");
      Preconditions.checkArgument(player != null, "player cannot be null");
      CraftWorld craftWorld = (CraftWorld)world;
      CraftPlayer craftPlayer = (CraftPlayer)player;
      CraftingMenu container = new CraftingMenu(-1, craftPlayer.getHandle().getInventory());
      CraftingContainer craftingContainer = container.craftSlots;
      ResultContainer craftResult = container.resultSlots;
      Optional<RecipeHolder<CraftingRecipe>> recipe = this.getNMSRecipe(craftingMatrix, craftingContainer, craftWorld);
      net.minecraft.world.item.ItemStack result = net.minecraft.world.item.ItemStack.EMPTY;
      if (recipe.isPresent()) {
         RecipeHolder<CraftingRecipe> recipeCrafting = recipe.get();
         craftingContainer.setCurrentRecipe(recipeCrafting);
         if (craftResult.setRecipeUsed(craftPlayer.getHandle(), recipeCrafting)) {
            result = ((CraftingRecipe)recipeCrafting.value()).assemble(craftingContainer.asCraftInput());
         }
      }

      result = CraftEventFactory.callPreCraftEvent(
         craftingContainer, craftResult, result, container.getBukkitView(), recipe.map(RecipeHolder::value).orElse(null) instanceof RepairItemRecipe
      );
      return this.createItemCraftResult(recipe, CraftItemStack.asBukkitCopy(result), craftingContainer);
   }

   public ItemCraftResult craftItemResult(ItemStack[] craftingMatrix, World world) {
      Preconditions.checkArgument(world != null, "world must not be null");
      CraftWorld craftWorld = (CraftWorld)world;
      CraftingContainer craftingContainer = this.createCraftingContainer();
      Optional<RecipeHolder<CraftingRecipe>> recipe = this.getNMSRecipe(craftingMatrix, craftingContainer, craftWorld);
      ItemStack result = recipe.<ItemStack>map(
            holder -> CraftItemStack.asBukkitCopy(((CraftingRecipe)holder.value()).assemble(craftingContainer.asCraftInput()))
         )
         .orElseGet(ItemStack::empty);
      return this.createItemCraftResult(recipe, result, craftingContainer);
   }

   private CraftItemCraftResult createItemCraftResult(Optional<RecipeHolder<CraftingRecipe>> recipe, ItemStack result, CraftingContainer craftingContainer) {
      CraftItemCraftResult craftItemResult = new CraftItemCraftResult(result);
      Positioned positionedCraftInput = craftingContainer.asPositionedCraftInput();
      CraftingInput craftingInput = positionedCraftInput.input();
      recipe.map(holder -> ((CraftingRecipe)holder.value()).getRemainingItems(craftingInput))
         .ifPresent(
            remainingItems -> {
               for (int height = 0; height < craftingInput.height(); height++) {
                  for (int width = 0; width < craftingInput.width(); width++) {
                     int inventorySlot = width + positionedCraftInput.left() + (height + positionedCraftInput.top()) * craftingContainer.getWidth();
                     net.minecraft.world.item.ItemStack itemInMenu = craftingContainer.getItem(inventorySlot);
                     net.minecraft.world.item.ItemStack remainingItem = (net.minecraft.world.item.ItemStack)remainingItems.get(
                        width + height * craftingInput.width()
                     );
                     if (!itemInMenu.isEmpty()) {
                        craftingContainer.removeItem(inventorySlot, 1);
                        itemInMenu = craftingContainer.getItem(inventorySlot);
                     }

                     if (!remainingItem.isEmpty()) {
                        if (itemInMenu.isEmpty()) {
                           craftingContainer.setItem(inventorySlot, remainingItem);
                        } else if (net.minecraft.world.item.ItemStack.isSameItemSameComponents(itemInMenu, remainingItem)) {
                           remainingItem.grow(itemInMenu.getCount());
                           craftingContainer.setItem(inventorySlot, remainingItem);
                        } else {
                           craftItemResult.getOverflowItems().add(CraftItemStack.asBukkitCopy(remainingItem));
                        }
                     }
                  }
               }
            }
         );

      for (int i = 0; i < craftingContainer.getContents().size(); i++) {
         craftItemResult.setResultMatrix(i, CraftItemStack.asBukkitCopy(craftingContainer.getItem(i)));
      }

      return craftItemResult;
   }

   private Optional<RecipeHolder<CraftingRecipe>> getNMSRecipe(ItemStack[] craftingMatrix, CraftingContainer craftingContainer, CraftWorld world) {
      Preconditions.checkArgument(craftingMatrix != null, "craftingMatrix must not be null");
      Preconditions.checkArgument(craftingMatrix.length == 9, "craftingMatrix must be an array of length 9");
      Preconditions.checkArgument(world != null, "world must not be null");

      for (int i = 0; i < craftingMatrix.length; i++) {
         craftingContainer.setItem(i, CraftItemStack.asNMSCopy(craftingMatrix[i]));
      }

      return this.getServer().getRecipeManager().getRecipeFor(RecipeType.CRAFTING, craftingContainer.asCraftInput(), world.getHandle());
   }

   public Iterator<Recipe> recipeIterator() {
      return new RecipeIterator();
   }

   public void clearRecipes() {
      this.console.getRecipeManager().clearRecipes();
   }

   public void resetRecipes() {
      this.reloadData();
   }

   public boolean removeRecipe(NamespacedKey recipeKey, boolean resendRecipes) {
      Preconditions.checkArgument(recipeKey != null, "recipeKey == null");
      ResourceKey<net.minecraft.world.item.crafting.Recipe<?>> id = CraftNamespacedKey.toResourceKey(Registries.RECIPE, recipeKey);
      boolean removed = this.getServer().getRecipeManager().removeRecipe(id);
      if (removed) {
         this.playerList.reloadRecipes();
      }

      return removed;
   }

   public Map<String, String[]> getCommandAliases() {
      ConfigurationSection section = this.commandsConfiguration.getConfigurationSection("aliases");
      Map<String, String[]> result = new LinkedHashMap<>();
      if (section != null) {
         for (String key : section.getKeys(false)) {
            List<String> commands;
            if (section.isList(key)) {
               commands = section.getStringList(key);
            } else {
               commands = ImmutableList.of(section.getString(key));
            }

            result.put(key, commands.toArray(new String[commands.size()]));
         }
      }

      return result;
   }

   public Component shutdownMessage() {
      String msg = this.getShutdownMessage();
      return msg != null ? LegacyComponentSerializer.legacySection().deserialize(msg) : null;
   }

   @Deprecated
   public String getShutdownMessage() {
      return this.configuration.getString("settings.shutdown-message");
   }

   public int getSpawnRadius() {
      return this.getServer().spawnProtectionRadius();
   }

   public void setSpawnRadius(int value) {
      this.getServer().setSpawnProtectionRadius(value);
   }

   public boolean isEnforcingSecureProfiles() {
      return this.getServer().enforceSecureProfile();
   }

   public boolean isAcceptingTransfers() {
      return this.getServer().acceptsTransfers();
   }

   public boolean getHideOnlinePlayers() {
      return this.console.hidesOnlinePlayers();
   }

   public boolean getOnlineMode() {
      return this.console.usesAuthentication();
   }

   @NotNull
   public ServerConfiguration getServerConfig() {
      return this.serverConfig;
   }

   public boolean getAllowFlight() {
      return this.console.allowFlight();
   }

   public boolean isHardcore() {
      return this.console.isHardcore();
   }

   public ChunkGenerator getGenerator(String world) {
      ConfigurationSection section = this.configuration.getConfigurationSection("worlds");
      ChunkGenerator result = null;
      if (section != null) {
         section = section.getConfigurationSection(world);
         if (section != null) {
            String name = section.getString("generator");
            if (name != null && !name.isEmpty()) {
               String[] split = name.split(":", 2);
               String id = split.length > 1 ? split[1] : null;
               Plugin plugin = this.pluginManager.getPlugin(split[0]);
               if (plugin == null) {
                  this.getLogger().severe("Could not set generator for default world '" + world + "': Plugin '" + split[0] + "' does not exist");
               } else if (!plugin.isEnabled()) {
                  this.getLogger()
                     .severe(
                        "Could not set generator for default world '"
                           + world
                           + "': Plugin '"
                           + plugin.getDescription().getFullName()
                           + "' is not enabled yet (is it load:STARTUP?)"
                     );
               } else {
                  try {
                     result = plugin.getDefaultWorldGenerator(world, id);
                     if (result == null) {
                        this.getLogger()
                           .severe(
                              "Could not set generator for default world '"
                                 + world
                                 + "': Plugin '"
                                 + plugin.getDescription().getFullName()
                                 + "' lacks a default world generator"
                           );
                     }
                  } catch (Throwable t) {
                     plugin.getLogger()
                        .log(
                           java.util.logging.Level.SEVERE,
                           "Could not set generator for default world '" + world + "': Plugin '" + plugin.getDescription().getFullName(),
                           t
                        );
                  }
               }
            }
         }
      }

      return result;
   }

   public BiomeProvider getBiomeProvider(String world) {
      ConfigurationSection section = this.configuration.getConfigurationSection("worlds");
      BiomeProvider result = null;
      if (section != null) {
         section = section.getConfigurationSection(world);
         if (section != null) {
            String name = section.getString("biome-provider");
            if (name != null && !name.isEmpty()) {
               String[] split = name.split(":", 2);
               String id = split.length > 1 ? split[1] : null;
               Plugin plugin = this.pluginManager.getPlugin(split[0]);
               if (plugin == null) {
                  this.getLogger().severe("Could not set biome provider for default world '" + world + "': Plugin '" + split[0] + "' does not exist");
               } else if (!plugin.isEnabled()) {
                  this.getLogger()
                     .severe(
                        "Could not set biome provider for default world '"
                           + world
                           + "': Plugin '"
                           + plugin.getDescription().getFullName()
                           + "' is not enabled yet (is it load:STARTUP?)"
                     );
               } else {
                  try {
                     result = plugin.getDefaultBiomeProvider(world, id);
                     if (result == null) {
                        this.getLogger()
                           .severe(
                              "Could not set biome provider for default world '"
                                 + world
                                 + "': Plugin '"
                                 + plugin.getDescription().getFullName()
                                 + "' lacks a default world biome provider"
                           );
                     }
                  } catch (Throwable t) {
                     plugin.getLogger()
                        .log(
                           java.util.logging.Level.SEVERE,
                           "Could not set biome provider for default world '" + world + "': Plugin '" + plugin.getDescription().getFullName(),
                           t
                        );
                  }
               }
            }
         }
      }

      return result;
   }

   public CraftMapView getMap(int id) {
      Level overworld = this.console.overworld();
      if (overworld == null) {
         return null;
      }

      MapItemSavedData mapData = overworld.getMapData(new MapId(id));
      return mapData == null ? null : mapData.mapView;
   }

   public CraftMapView createMap(World world) {
      Preconditions.checkArgument(world != null, "World cannot be null");
      ServerLevel level = ((CraftWorld)world).getHandle();
      BlockPos spawn = level.serverLevelData.getRespawnData().pos();
      MapId newId = MapItem.createNewSavedData(level, spawn.getX(), spawn.getZ(), 3, false, false, level.dimension());
      return level.getMapData(newId).mapView;
   }

   public ItemStack createExplorerMap(World world, Location location, StructureType structureType, int radius, boolean findUnexplored) {
      Preconditions.checkArgument(world != null, "World cannot be null");
      Preconditions.checkArgument(structureType != null, "StructureType cannot be null");
      Preconditions.checkArgument(structureType.getMapIcon() != null, "Cannot create explorer maps for StructureType %s", structureType.getName());
      ServerLevel level = ((CraftWorld)world).getHandle();
      Location structureLocation = world.locateNearestStructure(location, structureType, radius, findUnexplored);
      if (structureLocation == null) {
         throw new IllegalStateException("Could not find a structure for " + structureType);
      }

      BlockPos structurePos = CraftLocation.toBlockPos(structureLocation);
      net.minecraft.world.item.ItemStack stack = MapItem.create(level, structurePos.getX(), structurePos.getZ(), Scale.NORMAL.getValue(), true, true);
      MapItem.renderBiomePreviewMap(level, stack);
      MapItemSavedData.addTargetDecoration(stack, structurePos, "+", CraftType.bukkitToMinecraftHolder(structureType.getMapIcon()));
      return CraftItemStack.asBukkitCopy(stack);
   }

   public ItemStack createExplorerMap(
      World world, Location location, org.bukkit.generator.structure.StructureType structureType, Type mapIcon, int radius, boolean findUnexplored
   ) {
      Preconditions.checkArgument(world != null, "World cannot be null");
      Preconditions.checkArgument(location != null, "Location cannot be null");
      Preconditions.checkArgument(structureType != null, "StructureType cannot be null");
      Preconditions.checkArgument(mapIcon != null, "mapIcon cannot be null");
      ServerLevel level = ((CraftWorld)world).getHandle();
      StructureSearchResult structureSearchResult = world.locateNearestStructure(location, structureType, radius, findUnexplored);
      if (structureSearchResult == null) {
         return null;
      }

      Location structureLocation = structureSearchResult.getLocation();
      BlockPos structurePos = new BlockPos(structureLocation.getBlockX(), structureLocation.getBlockY(), structureLocation.getBlockZ());
      net.minecraft.world.item.ItemStack stack = MapItem.create(level, structurePos.getX(), structurePos.getZ(), Scale.NORMAL.getValue(), true, true);
      MapItem.renderBiomePreviewMap(level, stack);
      MapItemSavedData.addTargetDecoration(stack, structurePos, "+", CraftType.bukkitToMinecraftHolder(mapIcon));
      return CraftItemStack.asBukkitCopy(stack);
   }

   public void shutdown() {
      this.console.halt(false);
   }

   public int broadcast(Component message, String permission) {
      Set<CommandSender> recipients = new HashSet<>();

      for (Permissible permissible : this.getPluginManager().getPermissionSubscriptions(permission)) {
         if (permissible instanceof CommandSender && !(permissible instanceof BlockCommandSender) && permissible.hasPermission(permission)) {
            recipients.add((CommandSender)permissible);
         }
      }

      BroadcastMessageEvent broadcastMessageEvent = new BroadcastMessageEvent(!Bukkit.isPrimaryThread(), message, recipients);
      if (!broadcastMessageEvent.callEvent()) {
         return 0;
      }

      message = broadcastMessageEvent.message();

      for (CommandSender recipient : recipients) {
         recipient.sendMessage(message);
      }

      return recipients.size();
   }

   public UUID getPlayerUniqueId(String name) {
      Player player = Bukkit.getPlayerExact(name);
      if (player != null) {
         return player.getUniqueId();
      }

      NameAndId nameAndId;
      if (GlobalConfiguration.get().proxies.isProxyOnlineMode()) {
         nameAndId = (NameAndId)this.console.services().nameToIdCache().get(name).orElse(null);
      } else {
         nameAndId = NameAndId.createOffline(name);
      }

      return nameAndId != null ? nameAndId.id() : null;
   }

   public OfflinePlayer getOfflinePlayer(String name) {
      Preconditions.checkArgument(name != null, "name cannot be null");
      Preconditions.checkArgument(!name.isBlank(), "name cannot be empty");
      OfflinePlayer result = this.getPlayerExact(name);
      if (result == null) {
         NameAndId profile = null;
         if (GlobalConfiguration.get().proxies.isProxyOnlineMode()) {
            profile = (NameAndId)this.console.services().nameToIdCache().get(name).orElse(null);
         }

         if (profile == null) {
            result = this.getOfflinePlayer(NameAndId.createOffline(name));
         } else {
            result = this.getOfflinePlayer(profile);
         }
      } else {
         this.offlinePlayers.remove(result.getUniqueId());
      }

      return result;
   }

   @Nullable
   public OfflinePlayer getOfflinePlayerIfCached(String name) {
      Preconditions.checkArgument(name != null, "Name cannot be null");
      Preconditions.checkArgument(!name.isEmpty(), "Name cannot be empty");
      OfflinePlayer result = this.getPlayerExact(name);
      if (result == null) {
         NameAndId profile = this.console.services().nameToIdCache().getIfCached(name);
         if (profile != null) {
            result = this.getOfflinePlayer(profile);
         }
      } else {
         this.offlinePlayers.remove(result.getUniqueId());
      }

      return result;
   }

   public OfflinePlayer getOfflinePlayer(UUID id) {
      Preconditions.checkArgument(id != null, "UUID id cannot be null");
      OfflinePlayer result = this.getPlayer(id);
      if (result == null) {
         result = this.offlinePlayers.get(id);
         if (result == null) {
            result = new CraftOfflinePlayer(this, new NameAndId(id, ""));
            this.offlinePlayers.put(id, result);
         }
      } else {
         this.offlinePlayers.remove(id);
      }

      return result;
   }

   public PlayerProfile createPlayerProfile(UUID uniqueId, String name) {
      return new CraftPlayerProfile(uniqueId, name);
   }

   public PlayerProfile createPlayerProfile(UUID uniqueId) {
      return new CraftPlayerProfile(uniqueId, null);
   }

   public PlayerProfile createPlayerProfile(String name) {
      return new CraftPlayerProfile(null, name);
   }

   public OfflinePlayer getOfflinePlayer(NameAndId nameAndId) {
      OfflinePlayer player = new CraftOfflinePlayer(this, nameAndId);
      this.offlinePlayers.put(nameAndId.id(), player);
      return player;
   }

   public Set<String> getIPBans() {
      return this.playerList.getIpBans().getEntries().stream().<String>map(StoredUserEntry::getUser).collect(Collectors.toSet());
   }

   public void banIP(String address) {
      Preconditions.checkArgument(address != null && !address.isBlank(), "Address cannot be null or blank.");
      this.getBanList(org.bukkit.BanList.Type.IP).addBan(address, null, null, null);
   }

   public void unbanIP(String address) {
      Preconditions.checkArgument(address != null && !address.isBlank(), "Address cannot be null or blank.");
      this.getBanList(org.bukkit.BanList.Type.IP).pardon(address);
   }

   public void banIP(InetAddress address) {
      Preconditions.checkArgument(address != null, "Address cannot be null.");
      ((CraftIpBanList)this.getBanList(org.bukkit.BanList.Type.IP)).addBan(address, null, (Date)null, null);
   }

   public void unbanIP(InetAddress address) {
      Preconditions.checkArgument(address != null, "Address cannot be null.");
      ((CraftIpBanList)this.getBanList(org.bukkit.BanList.Type.IP)).pardon(address);
   }

   public Set<OfflinePlayer> getBannedPlayers() {
      Set<OfflinePlayer> result = new HashSet<>();

      for (UserBanListEntry entry : this.playerList.getBans().getEntries()) {
         result.add(this.getOfflinePlayer((NameAndId)entry.getUser()));
      }

      return result;
   }

   // $VF: Unable to simplify switch on enum
   // Please report this to the Vineflower issue tracker, at https://github.com/Vineflower/vineflower/issues with a copy of the class file (if you have the rights to distribute it!)
   public <T extends BanList<?>> T getBanList(org.bukkit.BanList.Type type) {
      Preconditions.checkArgument(type != null, "BanList.Type cannot be null");

      return (T)(switch (3.$SwitchMap$org$bukkit$BanList$Type[type.ordinal()]) {
         case 1 -> new CraftIpBanList(this.playerList.getIpBans());
         case 2, 3 -> new CraftProfileBanList(this.playerList.getBans());
         default -> throw new MatchException(null, null);
      });
   }

   public <B extends BanList<E>, E> B getBanList(BanListType<B> type) {
      Preconditions.checkArgument(type != null, "BanList.BanType cannot be null");
      if (type == BanListType.IP) {
         return (B)(new CraftIpBanList(this.playerList.getIpBans()));
      } else if (type == BanListType.PROFILE) {
         return (B)(new CraftProfileBanList(this.playerList.getBans()));
      } else {
         throw new IllegalArgumentException("Unknown BanListType: " + type);
      }
   }

   public void setWhitelist(boolean value) {
      this.console.setUsingWhitelist(value);
   }

   public boolean isWhitelistEnforced() {
      return this.console.isEnforceWhitelist();
   }

   public void setWhitelistEnforced(boolean value) {
      this.console.setEnforceWhitelist(value);
   }

   public Set<OfflinePlayer> getWhitelistedPlayers() {
      Set<OfflinePlayer> result = new LinkedHashSet<>();

      for (UserWhiteListEntry entry : this.playerList.getWhiteList().getEntries()) {
         result.add(this.getOfflinePlayer((NameAndId)entry.getUser()));
      }

      return result;
   }

   public Set<OfflinePlayer> getOperators() {
      Set<OfflinePlayer> result = new HashSet<>();

      for (ServerOpListEntry entry : this.playerList.getOps().getEntries()) {
         result.add(this.getOfflinePlayer((NameAndId)entry.getUser()));
      }

      return result;
   }

   public void reloadWhitelist() {
      this.playerList.reloadWhiteList();
   }

   public GameMode getDefaultGameMode() {
      return GameMode.getByValue(
         ((GameType)Optionull.mapOrDefault(
               this.console.getLevel(Level.OVERWORLD), l -> l.serverLevelData.getGameType(), (GameType)this.console.getProperties().gameMode.get()
            ))
            .getId()
      );
   }

   public void setDefaultGameMode(GameMode mode) {
      Preconditions.checkArgument(mode != null, "GameMode cannot be null");

      for (World world : this.getWorlds()) {
         ((CraftWorld)world).getHandle().serverLevelData.setGameType(GameType.byId(mode.getValue()));
      }
   }

   public boolean forcesDefaultGameMode() {
      return (Boolean)this.console.getProperties().forceGameMode.get();
   }

   public ConsoleCommandSender getConsoleSender() {
      return this.console.console;
   }

   public CommandSender createCommandSender(Consumer<? super Component> feedback) {
      return new FeedbackForwardingSender(feedback, this);
   }

   public EntityMetadataStore getEntityMetadata() {
      return this.entityMetadata;
   }

   public PlayerMetadataStore getPlayerMetadata() {
      return this.playerMetadata;
   }

   public WorldMetadataStore getWorldMetadata() {
      return this.worldMetadata;
   }

   public File getWorldContainer() {
      return this.getServer().storageSource.getLevelDirectory().path().getParent().toFile();
   }

   public Path getLevelDirectory() {
      return this.getServer().storageSource.getLevelDirectory().path();
   }

   public OfflinePlayer[] getOfflinePlayers() {
      PlayerDataStorage storage = this.console.getPlayerList().playerIo;
      String[] files = storage.getPlayerDir().list((dir, name) -> name.endsWith(".dat"));
      Set<OfflinePlayer> players = new HashSet<>();

      for (String file : files) {
         try {
            players.add(this.getOfflinePlayer(UUID.fromString(file.substring(0, file.length() - 4))));
         } catch (IllegalArgumentException var9) {
         }
      }

      players.addAll(this.getOnlinePlayers());
      return players.toArray(new OfflinePlayer[players.size()]);
   }

   public Messenger getMessenger() {
      return this.messenger;
   }

   public void sendPluginMessage(Plugin source, String channel, byte[] message) {
      StandardMessenger.validatePluginMessage(this.getMessenger(), source, channel, message);

      for (Player player : this.getOnlinePlayers()) {
         player.sendPluginMessage(source, channel, message);
      }
   }

   public Set<String> getListeningPluginChannels() {
      Set<String> result = new HashSet<>();

      for (Player player : this.getOnlinePlayers()) {
         result.addAll(player.getListeningPluginChannels());
      }

      return result;
   }

   public Inventory createInventory(InventoryHolder owner, InventoryType type) {
      Preconditions.checkArgument(type != null, "InventoryType cannot be null");
      Preconditions.checkArgument(type.isCreatable(), "InventoryType.%s cannot be used to create a inventory", type);
      return CraftInventoryCreator.INSTANCE.createInventory(owner, type);
   }

   public Inventory createInventory(InventoryHolder owner, InventoryType type, Component title) {
      Preconditions.checkArgument(type.isCreatable(), "Cannot open an inventory of type ", type);
      return CraftInventoryCreator.INSTANCE.createInventory(owner, type, title);
   }

   public Inventory createInventory(InventoryHolder owner, InventoryType type, String title) {
      Preconditions.checkArgument(type != null, "InventoryType cannot be null");
      Preconditions.checkArgument(type.isCreatable(), "InventoryType.%s cannot be used to create a inventory", type);
      Preconditions.checkArgument(title != null, "title cannot be null");
      return CraftInventoryCreator.INSTANCE.createInventory(owner, type, title);
   }

   public Inventory createInventory(InventoryHolder owner, int size) throws IllegalArgumentException {
      Preconditions.checkArgument(
         9 <= size && size <= 54 && size % 9 == 0, "Size for custom inventory must be a multiple of 9 between 9 and 54 slots (got %s)", size
      );
      return CraftInventoryCreator.INSTANCE.createInventory(owner, size);
   }

   public Inventory createInventory(InventoryHolder owner, int size, Component title) throws IllegalArgumentException {
      Preconditions.checkArgument(
         9 <= size && size <= 54 && size % 9 == 0, "Size for custom inventory must be a multiple of 9 between 9 and 54 slots (got " + size + ")"
      );
      return CraftInventoryCreator.INSTANCE.createInventory(owner, size, title);
   }

   public Inventory createInventory(InventoryHolder owner, int size, String title) throws IllegalArgumentException {
      Preconditions.checkArgument(
         9 <= size && size <= 54 && size % 9 == 0, "Size for custom inventory must be a multiple of 9 between 9 and 54 slots (got %s)", size
      );
      return CraftInventoryCreator.INSTANCE.createInventory(owner, size, title);
   }

   public Merchant createMerchant(Component title) {
      return new CraftMerchantCustom(title == null ? InventoryType.MERCHANT.defaultTitle() : title);
   }

   @Deprecated
   public Merchant createMerchant(String title) {
      return new CraftMerchantCustom(title == null ? InventoryType.MERCHANT.getDefaultTitle() : title);
   }

   @NotNull
   public Merchant createMerchant() {
      return new CraftMerchantCustom();
   }

   public int getMaxChainedNeighborUpdates() {
      return this.getServer().getMaxChainedNeighborUpdates();
   }

   public HelpMap getHelpMap() {
      return this.helpMap;
   }

   public SimpleCommandMap getCommandMap() {
      return this.commandMap;
   }

   public int getSpawnLimit(SpawnCategory spawnCategory) {
      Preconditions.checkArgument(spawnCategory != null, "SpawnCategory cannot be null");
      Preconditions.checkArgument(CraftSpawnCategory.isValidForLimits(spawnCategory), "SpawnCategory." + spawnCategory + " does not have a spawn limit.");
      return this.getSpawnLimitUnsafe(spawnCategory);
   }

   public int getSpawnLimitUnsafe(SpawnCategory spawnCategory) {
      return this.spawnCategoryLimit.getOrDefault(spawnCategory, -1);
   }

   public boolean isPrimaryThread() {
      return TickThread.isTickThread();
   }

   public Component motd() {
      return this.console.motd();
   }

   public void motd(Component motd) {
      this.console.motd(motd);
   }

   public String getMotd() {
      return this.console.getMotd();
   }

   public void setMotd(String motd) {
      this.console.setMotd(motd);
   }

   public ServerLinks getServerLinks() {
      return this.serverLinks;
   }

   public WarningState getWarningState() {
      return this.warningState;
   }

   public CraftItemFactory getItemFactory() {
      return CraftItemFactory.instance();
   }

   public CraftEntityFactory getEntityFactory() {
      return CraftEntityFactory.instance();
   }

   public CraftScoreboardManager getScoreboardManager() {
      return this.scoreboardManager;
   }

   public Criteria getScoreboardCriteria(String name) {
      return CraftCriteria.getFromBukkit(name);
   }

   public void checkSaveState() {
      if (!this.printSaveWarning && this.console.autosavePeriod > 0) {
         this.printSaveWarning = true;
         this.getLogger()
            .log(
               java.util.logging.Level.WARNING,
               "A manual (plugin-induced) save has been detected while server is configured to auto-save. This may affect performance.",
               this.warningState == WarningState.ON ? new Throwable() : null
            );
      }
   }

   public CraftIconCache getServerIcon() {
      return this.icon;
   }

   public CraftIconCache loadServerIcon(File file) throws Exception {
      Preconditions.checkArgument(file != null, "File cannot be null");
      Preconditions.checkArgument(file.isFile(), "File (%s) is not a valid file", file);
      return loadServerIcon0(file);
   }

   static CraftIconCache loadServerIcon0(File file) throws Exception {
      return loadServerIcon0(ImageIO.read(file));
   }

   public CraftIconCache loadServerIcon(BufferedImage image) throws Exception {
      Preconditions.checkArgument(image != null, "BufferedImage image cannot be null");
      return loadServerIcon0(image);
   }

   static CraftIconCache loadServerIcon0(BufferedImage image) throws Exception {
      Preconditions.checkArgument(image.getWidth() == 64, "BufferedImage must be 64 pixels wide (%s)", image.getWidth());
      Preconditions.checkArgument(image.getHeight() == 64, "BufferedImage must be 64 pixels high (%s)", image.getHeight());
      ByteArrayOutputStream arrayOutputStream = new ByteArrayOutputStream();
      ImageIO.write(image, "PNG", arrayOutputStream);
      return new CraftIconCache(arrayOutputStream.toByteArray());
   }

   public void setIdleTimeout(int threshold) {
      this.console.setPlayerIdleTimeout(threshold);
   }

   public int getIdleTimeout() {
      return this.console.playerIdleTimeout();
   }

   public int getPauseWhenEmptyTime() {
      return this.console.pauseWhenEmptySeconds();
   }

   public void setPauseWhenEmptyTime(int seconds) {
      this.console.setPauseWhenEmptySeconds(seconds);
   }

   public ChunkData createChunkData(World world) {
      Preconditions.checkArgument(world != null, "World cannot be null");
      ServerLevel handle = ((CraftWorld)world).getHandle();
      return new OldCraftChunkData(world.getMinHeight(), world.getMaxHeight(), handle.palettedContainerFactory(), world);
   }

   public BossBar createBossBar(String title, BarColor color, BarStyle style, BarFlag... flags) {
      return new CraftBossBar(title, color, style, flags);
   }

   public KeyedBossBar createBossBar(NamespacedKey key, String title, BarColor barColor, BarStyle barStyle, BarFlag... barFlags) {
      Preconditions.checkArgument(key != null, "NamespacedKey key cannot be null");
      Preconditions.checkArgument(barColor != null, "BarColor key cannot be null");
      Preconditions.checkArgument(barStyle != null, "BarStyle key cannot be null");
      CustomBossEvent bossBattleCustom = this.getServer()
         .getCustomBossEvents()
         .create(RandomSource.create(), CraftNamespacedKey.toMinecraft(key), CraftChatMessage.fromString(title, true)[0]);
      KeyedBossBar keyedBossbar = bossBattleCustom.getBukkitEntity();
      keyedBossbar.setColor(barColor);
      keyedBossbar.setStyle(barStyle);

      for (BarFlag flag : barFlags) {
         if (flag != null) {
            keyedBossbar.addFlag(flag);
         }
      }

      return keyedBossbar;
   }

   public Iterator<KeyedBossBar> getBossBars() {
      return Iterators.unmodifiableIterator(
         Iterators.transform(this.getServer().getCustomBossEvents().getEvents().iterator(), CustomBossEvent::getBukkitEntity)
      );
   }

   public KeyedBossBar getBossBar(NamespacedKey key) {
      Preconditions.checkArgument(key != null, "key cannot be null");
      CustomBossEvent bossBattleCustom = this.getServer().getCustomBossEvents().get(CraftNamespacedKey.toMinecraft(key));
      return bossBattleCustom == null ? null : bossBattleCustom.getBukkitEntity();
   }

   public boolean removeBossBar(NamespacedKey key) {
      Preconditions.checkArgument(key != null, "key cannot be null");
      CustomBossEvents bossBattleCustomData = this.getServer().getCustomBossEvents();
      CustomBossEvent bossBattleCustom = bossBattleCustomData.get(CraftNamespacedKey.toMinecraft(key));
      if (bossBattleCustom != null) {
         bossBattleCustomData.remove(bossBattleCustom);
         return true;
      } else {
         return false;
      }
   }

   public org.bukkit.entity.Entity getEntity(UUID uuid) {
      Preconditions.checkArgument(uuid != null, "uuid cannot be null");

      for (ServerLevel world : this.getServer().getAllLevels()) {
         Entity entity = world.getEntity(uuid);
         if (entity != null) {
            return entity.getBukkitEntity();
         }
      }

      return null;
   }

   public Advancement getAdvancement(NamespacedKey key) {
      Preconditions.checkArgument(key != null, "key cannot be null");
      AdvancementHolder advancement = this.console.getAdvancements().get(CraftNamespacedKey.toMinecraft(key));
      return advancement == null ? null : advancement.toBukkit();
   }

   public Iterator<Advancement> advancementIterator() {
      return Iterators.unmodifiableIterator(Iterators.transform(this.console.getAdvancements().getAllAdvancements().iterator(), AdvancementHolder::toBukkit));
   }

   public BlockData createBlockData(Material material) {
      Preconditions.checkArgument(material != null, "Material cannot be null");
      return this.createBlockData(material, (String)null);
   }

   public BlockData createBlockData(Material material, Consumer<? super BlockData> consumer) {
      BlockData data = this.createBlockData(material);
      if (consumer != null) {
         consumer.accept(data);
      }

      return data;
   }

   public BlockData createBlockData(String data) throws IllegalArgumentException {
      Preconditions.checkArgument(data != null, "data cannot be null");
      return this.createBlockData(null, data);
   }

   public BlockData createBlockData(Material material, String data) {
      Preconditions.checkArgument(material != null || data != null, "Must provide one of material or data");
      BlockType type = null;
      if (material != null) {
         type = material.asBlockType();
         Preconditions.checkArgument(type != null, "Provided material must be a block");
      }

      return CraftBlockData.fromString(type, data);
   }

   public <T extends Keyed> Tag<T> getTag(String registry, NamespacedKey tag, Class<T> clazz) {
      Preconditions.checkArgument(registry != null, "registry cannot be null");
      Preconditions.checkArgument(tag != null, "NamespacedKey tag cannot be null");
      Preconditions.checkArgument(clazz != null, "Class clazz cannot be null");
      Identifier key = CraftNamespacedKey.toMinecraft(tag);
      switch (registry) {
         case "blocks":
            Preconditions.checkArgument(clazz == Material.class, "Block namespace (%s) must have material type", clazz.getName());
            TagKey<Block> blockTagKey = TagKey.create(Registries.BLOCK, key);
            if (BuiltInRegistries.BLOCK.get(blockTagKey).isPresent()) {
               return new CraftBlockTag(BuiltInRegistries.BLOCK, blockTagKey);
            }
            break;
         case "items":
            Preconditions.checkArgument(clazz == Material.class, "Item namespace (%s) must have material type", clazz.getName());
            TagKey<Item> itemTagKey = TagKey.create(Registries.ITEM, key);
            if (BuiltInRegistries.ITEM.get(itemTagKey).isPresent()) {
               return new CraftItemTag(BuiltInRegistries.ITEM, itemTagKey);
            }
            break;
         case "fluids":
            Preconditions.checkArgument(clazz == org.bukkit.Fluid.class, "Fluid namespace (%s) must have fluid type", clazz.getName());
            TagKey<Fluid> fluidTagKey = TagKey.create(Registries.FLUID, key);
            if (BuiltInRegistries.FLUID.get(fluidTagKey).isPresent()) {
               return new CraftFluidTag(BuiltInRegistries.FLUID, fluidTagKey);
            }
            break;
         case "entity_types":
            Preconditions.checkArgument(clazz == org.bukkit.entity.EntityType.class, "Entity type namespace (%s) must have entity type", clazz.getName());
            TagKey<EntityType<?>> entityTagKey = TagKey.create(Registries.ENTITY_TYPE, key);
            if (BuiltInRegistries.ENTITY_TYPE.get(entityTagKey).isPresent()) {
               return new CraftEntityTag(BuiltInRegistries.ENTITY_TYPE, entityTagKey);
            }
            break;
         case "damage_types":
            Preconditions.checkArgument(clazz == org.bukkit.damage.DamageType.class, "Damage type namespace (%s) must have damage type", clazz.getName());
            TagKey<DamageType> damageTagKey = TagKey.create(Registries.DAMAGE_TYPE, key);
            Registry<DamageType> damageRegistry = CraftRegistry.getMinecraftRegistry(Registries.DAMAGE_TYPE);
            if (damageRegistry.get(damageTagKey).isPresent()) {
               return new CraftDamageTag(damageRegistry, damageTagKey);
            }
            break;
         case "game_events":
            Preconditions.checkArgument(clazz == org.bukkit.GameEvent.class, "Game Event namespace must have GameEvent type");
            TagKey<GameEvent> gameEventTagKey = TagKey.create(Registries.GAME_EVENT, key);
            if (BuiltInRegistries.GAME_EVENT.get(gameEventTagKey).isPresent()) {
               return new CraftGameEventTag(BuiltInRegistries.GAME_EVENT, gameEventTagKey);
            }
            break;
         default:
            throw new IllegalArgumentException();
      }

      return null;
   }

   public <T extends Keyed> Iterable<Tag<T>> getTags(String registry, Class<T> clazz) {
      Preconditions.checkArgument(registry != null, "registry cannot be null");
      Preconditions.checkArgument(clazz != null, "Class clazz cannot be null");
      switch (registry) {
         case "blocks":
            Preconditions.checkArgument(clazz == Material.class, "Block namespace (%s) must have material type", clazz.getName());
            Registry<Block> blockTags = BuiltInRegistries.BLOCK;
            return blockTags.getTags().map(pair -> new CraftBlockTag(blockTags, pair.key())).collect(ImmutableList.toImmutableList());
         case "items":
            Preconditions.checkArgument(clazz == Material.class, "Item namespace (%s) must have material type", clazz.getName());
            Registry<Item> itemTags = BuiltInRegistries.ITEM;
            return itemTags.getTags().map(pair -> new CraftItemTag(itemTags, pair.key())).collect(ImmutableList.toImmutableList());
         case "fluids":
            Preconditions.checkArgument(clazz == org.bukkit.Fluid.class, "Fluid namespace (%s) must have fluid type", clazz.getName());
            Registry<Fluid> fluidTags = BuiltInRegistries.FLUID;
            return fluidTags.getTags().map(pair -> new CraftFluidTag(fluidTags, pair.key())).collect(ImmutableList.toImmutableList());
         case "entity_types":
            Preconditions.checkArgument(clazz == org.bukkit.entity.EntityType.class, "Entity type namespace (%s) must have entity type", clazz.getName());
            Registry<EntityType<?>> entityTags = BuiltInRegistries.ENTITY_TYPE;
            return entityTags.getTags().map(pair -> new CraftEntityTag(entityTags, pair.key())).collect(ImmutableList.toImmutableList());
         case "damage_types":
            Preconditions.checkArgument(clazz == org.bukkit.damage.DamageType.class, "Damage type namespace (%s) must have damage type", clazz.getName());
            Registry<DamageType> damageTags = CraftRegistry.getMinecraftRegistry(Registries.DAMAGE_TYPE);
            return damageTags.getTags().map(pair -> new CraftDamageTag(damageTags, pair.key())).collect(ImmutableList.toImmutableList());
         case "game_events":
            Preconditions.checkArgument(clazz == org.bukkit.GameEvent.class);
            Registry<GameEvent> gameEvents = BuiltInRegistries.GAME_EVENT;
            return gameEvents.getTags().map(pair -> new CraftGameEventTag(gameEvents, pair.key())).collect(ImmutableList.toImmutableList());
         default:
            throw new IllegalArgumentException();
      }
   }

   public LootTable getLootTable(NamespacedKey key) {
      Preconditions.checkArgument(key != null, "NamespacedKey key cannot be null");
      Holder registry = this.getServer().reloadableRegistries();
      return (LootTable)registry.lookup()
         .lookup(Registries.LOOT_TABLE)
         .flatMap(lookup -> lookup.get(CraftLootTable.bukkitKeyToMinecraft(key)))
         .map(holder -> new CraftLootTable(key, (net.minecraft.world.level.storage.loot.LootTable)holder.value()))
         .orElse(null);
   }

   public List<org.bukkit.entity.Entity> selectEntities(CommandSender sender, String selector) {
      Preconditions.checkArgument(selector != null, "selector cannot be null");
      Preconditions.checkArgument(sender != null, "CommandSender sender cannot be null");
      EntityArgument arg = EntityArgument.entities();

      List<? extends Entity> entities;
      try {
         StringReader reader = new StringReader(selector);
         entities = arg.parse(reader, true, true).findEntities(VanillaCommandWrapper.getListener(sender));
         Preconditions.checkArgument(!reader.canRead(), "Spurious trailing data in selector: %s", selector);
      } catch (CommandSyntaxException ex) {
         throw new IllegalArgumentException("Could not parse selector: " + selector, ex);
      }

      return new ArrayList<>(Lists.transform(entities, Entity::getBukkitEntity));
   }

   public StructureManager getStructureManager() {
      return this.structureManager;
   }

   public <T extends Keyed> org.bukkit.Registry<T> getRegistry(Class<T> aClass) {
      return io.papermc.paper.registry.RegistryAccess.registryAccess().getRegistry(aClass);
   }

   @Deprecated
   public UnsafeValues getUnsafe() {
      return CraftMagicNumbers.INSTANCE;
   }

   public long[] getTickTimes() {
      MSPTData reportData = this.getServer().getMSPTData5s();
      return reportData == null ? new long[0] : (long[])reportData.rawData().clone();
   }

   public double getAverageTickTime() {
      MSPTData reportData = this.getServer().getMSPTData5s();
      return reportData == null ? 0.0 : reportData.avg();
   }

   public Spigot spigot() {
      return this.spigot;
   }

   public void restart() {
      RestartCommand.restart();
   }

   public double[] getTPS() {
      return this.getServer().getTPS();
   }

   public void playSound(Sound sound) {
      if (sound.seed().isEmpty()) {
         AsyncCatcher.catchOp("play sound; cannot generate seed with world random");
      }

      long seed = sound.seed().orElseGet(this.console.overworld().getRandom()::nextLong);

      for (ServerPlayer player : this.playerList.getPlayers()) {
         player.connection.send(PaperAdventure.asSoundPacket(sound, player.getX(), player.getY(), player.getZ(), seed, null));
      }
   }

   public void playSound(Sound sound, double x, double y, double z) {
      AsyncCatcher.catchOp("play sound");
      PaperAdventure.asSoundPacket(
         sound, x, y, z, sound.seed().orElseGet(this.console.overworld().getRandom()::nextLong), this.playSound0(x, y, z, this.console.getAllLevels())
      );
   }

   public void playSound(Sound sound, Emitter emitter) {
      if (sound.seed().isEmpty()) {
         AsyncCatcher.catchOp("play sound; cannot generate seed with world random");
      }

      long seed = sound.seed().orElseGet(this.console.overworld().getRandom()::nextLong);
      if (emitter == Emitter.self()) {
         for (ServerPlayer player : this.playerList.getPlayers()) {
            player.connection.send(PaperAdventure.asSoundPacket(sound, player, seed, null));
         }
      } else {
         if (!(emitter instanceof CraftEntity craftEntity)) {
            throw new IllegalArgumentException("Sound emitter must be an Entity or self(), but was: " + emitter);
         }

         AsyncCatcher.catchOp("play sound; cannot use entity emitter");
         Entity entity = craftEntity.getHandle();
         PaperAdventure.asSoundPacket(sound, entity, seed, this.playSound0(entity.getX(), entity.getY(), entity.getZ(), List.of((ServerLevel)entity.level())));
      }
   }

   private BiConsumer<Packet<?>, Float> playSound0(double x, double y, double z, Iterable<ServerLevel> levels) {
      return (packet, distance) -> {
         for (ServerLevel level : levels) {
            level.getServer().getPlayerList().broadcast(null, x, y, z, distance.floatValue(), level.dimension(), packet);
         }
      };
   }

   public static Path dumpHeap(Path dir, String name) {
      try {
         Files.createDirectories(dir);
         MBeanServer server = ManagementFactory.getPlatformMBeanServer();

         Path file;
         try {
            Class clazz = Class.forName("openj9.lang.management.OpenJ9DiagnosticsMXBean");
            Object openj9Mbean = ManagementFactory.newPlatformMXBeanProxy(server, "openj9.lang.management:type=OpenJ9Diagnostics", clazz);
            Method m = clazz.getMethod("triggerDumpToFile", String.class, String.class);
            file = dir.resolve(name + ".phd");
            m.invoke(openj9Mbean, "heap", file.toString());
         } catch (ClassNotFoundException e) {
            Class clazz = Class.forName("com.sun.management.HotSpotDiagnosticMXBean");
            Object hotspotMBean = ManagementFactory.newPlatformMXBeanProxy(server, "com.sun.management:type=HotSpotDiagnostic", clazz);
            Method m = clazz.getMethod("dumpHeap", String.class, boolean.class);
            file = dir.resolve(name + ".hprof");
            m.invoke(hotspotMBean, file.toString(), true);
         }

         return file;
      } catch (Throwable t) {
         Bukkit.getLogger().log(java.util.logging.Level.SEVERE, "Could not write heap", t);
         return null;
      }
   }

   public Iterable<? extends Audience> audiences() {
      if (this.adventure$audiences == null) {
         this.adventure$audiences = Iterables.concat(Collections.singleton(this.getConsoleSender()), this.getOnlinePlayers());
      }

      return this.adventure$audiences;
   }

   public void reloadPermissions() {
      this.pluginManager.clearPermissions();
      if (GlobalConfiguration.get().misc.loadPermissionsYmlBeforePlugins) {
         this.loadCustomPermissions();
      }

      for (Plugin plugin : this.pluginManager.getPlugins()) {
         for (Permission perm : plugin.getDescription().getPermissions()) {
            try {
               this.pluginManager.addPermission(perm);
            } catch (IllegalArgumentException ex) {
               this.getLogger()
                  .log(
                     java.util.logging.Level.WARNING,
                     "Plugin " + plugin.getDescription().getFullName() + " tried to register permission '" + perm.getName() + "' but it's already registered",
                     ex
                  );
            }
         }
      }

      if (!GlobalConfiguration.get().misc.loadPermissionsYmlBeforePlugins) {
         this.loadCustomPermissions();
      }

      DefaultPermissions.registerCorePermissions();
      CraftDefaultPermissions.registerCorePermissions();
   }

   public boolean reloadCommandAliases() {
      Set<String> removals = this.getCommandAliases().keySet().stream().map(key -> key.toLowerCase(Locale.ENGLISH)).collect(Collectors.toSet());
      this.getCommandMap().getKnownCommands().keySet().removeIf(removals::contains);
      File file = this.getCommandsConfigFile();

      try {
         this.commandsConfiguration.load(file);
      } catch (FileNotFoundException ex) {
         return false;
      } catch (IOException | InvalidConfigurationException ex) {
         Bukkit.getLogger().log(java.util.logging.Level.SEVERE, "Cannot load " + file, ex);
         return false;
      }

      this.commandMap.registerServerAliases();
      return true;
   }

   public boolean suggestPlayerNamesWhenNullTabCompletions() {
      return GlobalConfiguration.get().commands.suggestPlayerNamesWhenNullTabCompletions;
   }

   public String getPermissionMessage() {
      return LegacyComponentSerializer.legacyAmpersand().serialize(GlobalConfiguration.get().messages.noPermission);
   }

   public Component permissionMessage() {
      return GlobalConfiguration.get().messages.noPermission;
   }

   public com.destroystokyo.paper.profile.PlayerProfile createProfile(@NotNull UUID uuid) {
      return this.createProfile(uuid, null);
   }

   public com.destroystokyo.paper.profile.PlayerProfile createProfile(@NotNull String name) {
      return this.createProfile(null, name);
   }

   public com.destroystokyo.paper.profile.PlayerProfile createProfile(@Nullable UUID uuid, @Nullable String name) {
      Player player = uuid != null ? Bukkit.getPlayer(uuid) : (name != null ? Bukkit.getPlayerExact(name) : null);
      return player != null
         ? new com.destroystokyo.paper.profile.CraftPlayerProfile((CraftPlayer)player)
         : new com.destroystokyo.paper.profile.CraftPlayerProfile(uuid, name);
   }

   public com.destroystokyo.paper.profile.PlayerProfile createProfileExact(@Nullable UUID uuid, @Nullable String name) {
      Player player = uuid != null ? Bukkit.getPlayer(uuid) : (name != null ? Bukkit.getPlayerExact(name) : null);
      if (player == null) {
         return new com.destroystokyo.paper.profile.CraftPlayerProfile(uuid, name);
      }

      if (Objects.equals(uuid, player.getUniqueId()) && Objects.equals(name, player.getName())) {
         return new com.destroystokyo.paper.profile.CraftPlayerProfile((CraftPlayer)player);
      }

      com.destroystokyo.paper.profile.CraftPlayerProfile profile = new com.destroystokyo.paper.profile.CraftPlayerProfile(uuid, name);
      profile.getGameProfileUnsafe().properties().putAll(((CraftPlayer)player).getHandle().getGameProfile().properties());
      return profile;
   }

   public int getCurrentTick() {
      return MinecraftServer.currentTick;
   }

   public boolean isStopping() {
      return MinecraftServer.getServer().hasStopped();
   }

   public MobGoals getMobGoals() {
      return this.mobGoals;
   }

   public PaperDatapackManager getDatapackManager() {
      return this.datapackManager;
   }

   public PaperPotionBrewer getPotionBrewer() {
      return this.potionBrewer;
   }

   public boolean isPaused() {
      return this.console.isTickPaused();
   }

   public void allowPausing(Plugin plugin, boolean value) {
      this.console.addPluginAllowingSleep(plugin.getName(), value);
   }

   static {
      ConfigurationSerialization.registerClass(CraftOfflinePlayer.class);
      ConfigurationSerialization.registerClass(CraftPlayerProfile.class);
      ConfigurationSerialization.registerClass(com.destroystokyo.paper.profile.CraftPlayerProfile.class);
      CraftItemFactory.instance();
      CraftEntityFactory.instance();
   }
}
