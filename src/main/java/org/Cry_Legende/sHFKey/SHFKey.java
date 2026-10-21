package org.Cry_Legende.sHFKey;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.regions.RegionContainer;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class SHFKey extends JavaPlugin implements Listener, CommandExecutor {
    private RegionContainer regionContainer;

    // Gestion du data.yml
    private File dataFile;
    private FileConfiguration dataConfig;

    @Override
    public void onEnable() {
        // Config par défaut
        this.saveDefaultConfig();

        // Initialisation data.yml
        createDataFile();

        // Events & Commandes
        getServer().getPluginManager().registerEvents(this, this);
        getCommand("shfkey").setExecutor(this);

        // Hook WorldGuard
        initializeWorldGuard();

        // Hook PlaceholderAPI
        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            new SHFKeyExpansion(this).register();
        } else {
            getLogger().warning("PlaceholderAPI non trouvé. Les placeholders ne fonctionneront pas.");
        }
    }

    private void initializeWorldGuard() {
        if (getServer().getPluginManager().getPlugin("WorldGuard") != null) {
            regionContainer = WorldGuard.getInstance().getPlatform().getRegionContainer();
        }
    }

    // --- GESTION DES DONNÉES (YAML) ---

    private void createDataFile() {
        dataFile = new File(getDataFolder(), "data.yml");
        if (!dataFile.exists()) {
            try {
                dataFile.createNewFile();
            } catch (IOException e) {
                getLogger().severe("Impossible de créer data.yml !");
                e.printStackTrace();
            }
        }
        dataConfig = YamlConfiguration.loadConfiguration(dataFile);
    }

    private void saveData() {
        try {
            dataConfig.save(dataFile);
        } catch (IOException e) {
            getLogger().severe("Impossible de sauvegarder data.yml !");
        }
    }

    public boolean isPluginEnabledFor(Player player) {
        List<String> disabledPlayers = dataConfig.getStringList("disabled-players");
        return !disabledPlayers.contains(player.getUniqueId().toString());
    }

    public void setPluginEnabledFor(Player player, boolean enabled) {
        List<String> disabledPlayers = dataConfig.getStringList("disabled-players");
        String uuid = player.getUniqueId().toString();

        if (enabled) {
            disabledPlayers.remove(uuid);
        } else {
            if (!disabledPlayers.contains(uuid)) {
                disabledPlayers.add(uuid);
            }
        }

        dataConfig.set("disabled-players", disabledPlayers);
        saveData();
    }

    // --- COMMANDE ---

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!sender.hasPermission("shfkey.admin")) {
            sender.sendMessage(ChatColor.RED + "Vous n'avez pas la permission.");
            return true;
        }

        if (args.length == 2 && args[0].equalsIgnoreCase("toggle")) {
            Player target = Bukkit.getPlayer(args[1]);
            if (target == null) {
                sender.sendMessage(ChatColor.RED + "Joueur introuvable.");
                return true;
            }

            boolean currentState = isPluginEnabledFor(target);
            setPluginEnabledFor(target, !currentState);

            String status = !currentState ? ChatColor.GREEN + "activé" : ChatColor.RED + "désactivé";
            sender.sendMessage(ChatColor.YELLOW + "SHFKey a été " + status + ChatColor.YELLOW + " pour " + target.getName());
            return true;
        }

        sender.sendMessage(ChatColor.YELLOW + "Usage: /shfkey toggle <joueur>");
        return true;
    }

    // --- EVENTS ---

    @EventHandler
    public void onSwapHand(PlayerSwapHandItemsEvent event) {
        Player player = event.getPlayer();

        // Si le plugin est désactivé pour ce joueur via la commande admin, on laisse le comportement Vanilla
        if (!isPluginEnabledFor(player)) {
            return;
        }

        // Sinon, on applique la logique du plugin et on annule le swap vanilla
        event.setCancelled(true);
        FileConfiguration config = this.getConfig();

        for (String key : config.getConfigurationSection("actions").getKeys(false)) {
            if (checkConditions(player, config, key)) {
                boolean asOP = config.getBoolean("actions." + key + ".asOP");
                executeCommands(player, config.getStringList("actions." + key + ".commands"), asOP);
                break;
            }
        }
    }

    private boolean checkConditions(Player player, FileConfiguration config, String key) {
        String permission = config.getString("actions." + key + ".permission", "none");
        List<String> worlds = config.getStringList("actions." + key + ".world");
        String regionName = config.getString("actions." + key + ".region", "none");
        boolean asOP = config.getBoolean("actions." + key + ".asOP");

        boolean hasPermission = permission.equals("none") || player.hasPermission(permission);

        // Gestion sécurisée si la liste des mondes est vide ou nulle
        boolean isInWorld = worlds.isEmpty() || worlds.contains("none") || worlds.contains(player.getWorld().getName());

        boolean isOpCheck = !asOP || player.isOp();
        boolean isInRegion = true;

        if (!regionName.equals("none")) {
            isInRegion = isPlayerInRegion(player, regionName);
        }

        return hasPermission && isInWorld && isOpCheck && isInRegion;
    }

    private boolean isPlayerInRegion(Player player, String regionName) {
        if (regionContainer == null) return false;

        RegionManager regionManager = regionContainer.get(BukkitAdapter.adapt(player.getWorld()));
        if (regionManager == null) return false;

        ProtectedRegion region = regionManager.getRegion(regionName);
        if (region == null) return false;

        return region.contains(player.getLocation().getBlockX(), player.getLocation().getBlockY(), player.getLocation().getBlockZ());
    }

    private void executeCommands(Player player, List<String> commands, boolean asOP) {
        commands.forEach(command -> {
            String cmd = command.replaceAll("%player%", player.getName());
            if (asOP) {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
            } else {
                player.chat("/" + cmd);
            }
        });
    }

    // --- CLASSE INTERNE POUR PLACEHOLDERAPI ---
    // Vous pouvez mettre ceci dans un fichier séparé si vous préférez, mais ça marche très bien ici.

    public class SHFKeyExpansion extends PlaceholderExpansion {
        private final SHFKey plugin;

        public SHFKeyExpansion(SHFKey plugin) {
            this.plugin = plugin;
        }

        @Override
        public @NotNull String getIdentifier() {
            return "shfkey";
        }

        @Override
        public @NotNull String getAuthor() {
            return "Cry";
        }

        @Override
        public @NotNull String getVersion() {
            return "1.0";
        }

        @Override
        public boolean persist() {
            return true;
        }

        @Override
        public String onPlaceholderRequest(Player player, @NotNull String params) {
            if (player == null) return "";

            // Placeholder: %shfkey_is_enabled%
            if (params.equalsIgnoreCase("is_enabled")) {
                return plugin.isPluginEnabledFor(player) ? "true" : "false";
            }

            return null;
        }
    }
}