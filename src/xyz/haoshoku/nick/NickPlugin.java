package xyz.haoshoku.nick;

import org.bukkit.Bukkit;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;
import xyz.haoshoku.nick.api.NickConfig;
import xyz.haoshoku.nick.listener.AsyncPlayerPreLoginListener;
import xyz.haoshoku.nick.listener.PlayerCommandPreprocessListener;
import xyz.haoshoku.nick.listener.PlayerJoinListener;
import xyz.haoshoku.nick.listener.PlayerLoginListener;
import xyz.haoshoku.nick.listener.PlayerQuitListener;
import xyz.haoshoku.nick.metrics.Metrics;
import xyz.haoshoku.nick.version.VersionHandler;
import xyz.haoshoku.nick.version.v1_21_R5.Handler_v1_21_R5;
import xyz.haoshoku.nick.version.v1_21_R6.Handler_v1_21_R6;
import xyz.haoshoku.nick.version.v1_21_R7.Handler_v1_21_R7;
import xyz.haoshoku.nick.version.v1_8_R3.Handler_v1_8_R3;
import xyz.haoshoku.nick.version.v26_2.Handler_v26_2;

public class NickPlugin extends JavaPlugin {
   private static NickPlugin instance;
   private VersionHandler handler;
   private String nmsVersion;
   private boolean bypassGameProfile;

   public void onEnable() {
      instance = this;
      this.setNmsVersion();
      this.initializeVersion();
      this.registerListener();
      this.handler.pluginOnEnable(this);
      this.getConfig().options().copyDefaults(true);
      this.saveDefaultConfig();
      NickConfig.setDefaultSkin(this.getConfig().getString("nickapi.default_skin_name"));
      NickConfig.setCracked(this.getConfig().getBoolean("nickapi.cracked"));
      int id = 9115;
      new Metrics(this, id);
   }

   public void onDisable() {
      this.handler.pluginOnDisable(this);
   }

   private void initializeVersion() {
      this.getLogger().info(this.nmsVersion + " has been detected");
      switch (this.nmsVersion) {
         case "v1_8_R3":
            this.handler = new Handler_v1_8_R3();
            break;
         case "v1_21_R5":
            this.handler = new Handler_v1_21_R5();
            break;
         case "v1_21_R6":
            this.handler = new Handler_v1_21_R6();
            break;
         case "v1_21_R7":
            this.handler = new Handler_v1_21_R7();
            break;
         case "v26_2":
            this.handler = new Handler_v26_2();
            break;
         default:
            this.getLogger().warning(this.nmsVersion + " is not compatible to the NickAPI version you are using");
            Bukkit.getPluginManager().disablePlugin(this);
      }

   }

   private void setNmsVersion() {
      String versionTemp = "Error while reading the version..";

      try {
         versionTemp = Bukkit.getServer().getClass().getPackage().getName().split("\\.")[3];
      } catch (Exception var4) {
         String bukkitVersion = Bukkit.getBukkitVersion();
         if (bukkitVersion.startsWith("26.")) {
            // PATCH (portage Paper 26.2) : Mojang/Paper est passe a un schema de
            // versions "26.x" a partir de mi-2026. Un handler dedie (Handler_v26_2)
            // a ete ecrit a partir du bytecode reel de Paper 26.2 (plusieurs
            // classes NMS ont change de structure depuis 1.21.11, notamment
            // ClientboundPlayerInfoUpdatePacket et PlayerList#respawn).
            versionTemp = "v26_2";
            this.bypassGameProfile = true;
         } else if (!bukkitVersion.startsWith("1.21.11") && !bukkitVersion.startsWith("1.21.12")) {
            if (!bukkitVersion.startsWith("1.21.10") && !bukkitVersion.startsWith("1.21.9")) {
               if (!bukkitVersion.startsWith("1.21.8") && !bukkitVersion.startsWith("1.21.7")) {
                  if (bukkitVersion.startsWith("1.21.5")) {
                     versionTemp = "v1_21_R4";
                  } else if (bukkitVersion.startsWith("1.21.4")) {
                     versionTemp = "v1_21_R3";
                  } else if (!bukkitVersion.startsWith("1.21.2") && !bukkitVersion.startsWith("1.21.3")) {
                     if (bukkitVersion.startsWith("1.21.1")) {
                        versionTemp = "v1_21_R1";
                     } else {
                        versionTemp = "Error";
                     }
                  } else {
                     versionTemp = "v1_21_R2";
                  }
               } else {
                  versionTemp = "v1_21_R5";
               }
            } else {
               versionTemp = "v1_21_R6";
               this.bypassGameProfile = true;
            }
         } else {
            versionTemp = "v1_21_R7";
            this.bypassGameProfile = true;
         }
      }

      this.nmsVersion = versionTemp;
   }

   private void registerListener() {
      Listener[] listeners = new Listener[]{new AsyncPlayerPreLoginListener(), new PlayerCommandPreprocessListener(), new PlayerJoinListener(), new PlayerLoginListener(), new PlayerQuitListener()};

      for(Listener listener : listeners) {
         Bukkit.getPluginManager().registerEvents(listener, this);
      }

   }

   public String getNMSVersion() {
      return this.nmsVersion;
   }

   public static NickPlugin instance() {
      return instance;
   }

   public VersionHandler getHandler() {
      return this.handler;
   }

   public boolean isBypassGameProfile() {
      return this.bypassGameProfile;
   }
}
