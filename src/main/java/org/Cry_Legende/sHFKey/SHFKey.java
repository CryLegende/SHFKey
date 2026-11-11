package org.Cry_Legende.sHFKey;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.regions.RegionContainer;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.Cry_Legende.sHFKey.network.DisabledDatabase;
import org.Cry_Legende.sHFKey.network.NetworkFKey;
import org.Cry_Legende.sHFKey.network.NetworkLink;
import org.Cry_Legende.sHFKey.network.ShNetworkLink;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.InvalidConfigurationException;
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
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

public class SHFKey extends JavaPlugin implements Listener, TabExecutor {
    private RegionContainer regionContainer;

    // Gestion du data.yml
    private File dataFile;
    private FileConfiguration dataConfig;
    // Joueurs ayant désactivé le plugin, en mémoire pour ne pas relire data.yml à chaque touche
    // (lu aussi par PlaceholderAPI, qui peut interroger depuis un autre fil)
    private final Set<String> disabledPlayers = Collections.synchronizedSet(new LinkedHashSet<>());
    // Non nul seulement avec SHNetwork : les joueurs désactivés vivent alors dans la base partagée
    private NetworkFKey network;
    // Remplacé par /shfkey reload
    private volatile YamlConfiguration messages = new YamlConfiguration();

    @Override
    public void onEnable() {
        // Config par défaut
        this.saveDefaultConfig();

        // Initialisation data.yml
        createDataFile();
        loadMessages();
        enableNetwork();

        // Events & Commandes
        getServer().getPluginManager().registerEvents(this, this);
        getCommand("shfkey").setExecutor(this);
        getCommand("shfkey").setTabCompleter(this);

        // Hook WorldGuard
        initializeWorldGuard();

        // Hook PlaceholderAPI
        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            new SHFKeyExpansion(this).register();
        } else {
            getLogger().warning("PlaceholderAPI non trouvé. Les placeholders ne fonctionneront pas.");
        }
    }

    @Override
    public void onDisable() {
        if (network != null) {
            network.stop();
        }
    }

    /**
     * Passe en mode réseau si SHNetwork est installé et 'reseau.actif' est vrai. Les classes qui touchent à SHNetwork
     * ne sont chargées qu'ici : sans lui, le plugin se comporte exactement comme avant.
     */
    private void enableNetwork() {
        if (!getConfig().getBoolean("reseau.actif", true)) return;
        if (!Bukkit.getPluginManager().isPluginEnabled("SHNetwork")) return;
        DisabledDatabase db = null;
        try {
            NetworkLink link = ShNetworkLink.open();
            db = new DisabledDatabase(
                getConfig().getString("reseau.base-de-donnees.url"),
                getConfig().getString("reseau.base-de-donnees.utilisateur"),
                getConfig().getString("reseau.base-de-donnees.mot-de-passe"),
                Math.max(250, getConfig().getInt("reseau.base-de-donnees.delai-ms", 3000)));
            db.createTable();
            Duration wait = Duration.ofSeconds(Math.max(1, getConfig().getInt("reseau.attente-max-secondes", 10)));
            NetworkFKey net = new NetworkFKey(this, disabledPlayers, db, link, wait);
            // la base fait foi : la liste de data.yml n'est reprise que par /shfkey import
            disabledPlayers.clear();
            net.start();
            network = net;
            getLogger().info("SHNetwork détecté : la liste des joueurs désactivés est partagée entre les serveurs (base partagée).");
        } catch (Exception | LinkageError e) {
            if (db != null) db.close();
            getLogger().log(Level.SEVERE, "SHNetwork présent mais inutilisable, liste des joueurs désactivés locale à ce serveur : " + e);
            // la liste de data.yml reste la référence
            disabledPlayers.clear();
            disabledPlayers.addAll(dataConfig.getStringList("disabled-players"));
        }
    }

    /** Lit messages.yml au démarrage. Illisible : les textes du jar sont utilisés et l'erreur est écrite dans la console. */
    private void loadMessages() {
        try {
            messages = readMessages();
        } catch (IOException | InvalidConfigurationException e) {
            getLogger().severe("messages.yml illisible, textes livrés utilisés : " + oneLine(e));
            YamlConfiguration jar = new YamlConfiguration();
            jar.setDefaults(jarMessages());
            messages = jar;
        }
    }

    /** messages.yml sans rien remplacer, avec les valeurs du jar pour toute clé absente du fichier du serveur. */
    private YamlConfiguration readMessages() throws IOException, InvalidConfigurationException {
        File file = new File(getDataFolder(), "messages.yml");
        if (!file.exists()) saveResource("messages.yml", false);
        YamlConfiguration loaded = new YamlConfiguration();
        loaded.load(file);
        loaded.setDefaults(jarMessages());
        return loaded;
    }

    private YamlConfiguration jarMessages() {
        try (InputStreamReader defaults = new InputStreamReader(Objects.requireNonNull(getResource("messages.yml")), StandardCharsets.UTF_8)) {
            return YamlConfiguration.loadConfiguration(defaults);
        } catch (Exception e) {
            getLogger().log(Level.WARNING, "messages.yml de référence illisible : " + e.getMessage());
            return new YamlConfiguration();
        }
    }

    private static String oneLine(Exception e) {
        String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        return message.replaceAll("\\s*\\R\\s*", " ").trim();
    }

    /**
     * Relit config.yml (actions) et messages.yml. Les deux fichiers sont lus avant de remplacer quoi que ce soit : si l'un
     * est invalide, l'ancienne configuration reste active. data.yml, la base et la liste des joueurs désactivés ne sont pas
     * touchés ; la section reseau n'est lue qu'au démarrage.
     *
     * @return null si tout est rechargé, sinon le fichier et l'erreur
     */
    private String reloadAll() {
        try {
            new YamlConfiguration().load(new File(getDataFolder(), "config.yml"));
        } catch (IOException | InvalidConfigurationException e) {
            return "config.yml : " + oneLine(e);
        }
        YamlConfiguration loaded;
        try {
            loaded = readMessages();
        } catch (IOException | InvalidConfigurationException e) {
            return "messages.yml : " + oneLine(e);
        }
        reloadConfig();
        messages = loaded;
        return null;
    }

    /** Texte de messages.yml, couleurs appliquées. */
    private String getMessage(String key) {
        return ChatColor.translateAlternateColorCodes('&', messages.getString(key, key));
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
        disabledPlayers.clear();
        disabledPlayers.addAll(dataConfig.getStringList("disabled-players"));
    }

    private void saveData() {
        try {
            dataConfig.save(dataFile);
        } catch (IOException e) {
            getLogger().severe("Impossible de sauvegarder data.yml !");
        }
    }

    public boolean isPluginEnabledFor(Player player) {
        return !disabledPlayers.contains(player.getUniqueId().toString());
    }

    public void setPluginEnabledFor(Player player, boolean enabled) {
        if (network != null) {
            network.set(player.getUniqueId(), !enabled);
            return;
        }
        String uuid = player.getUniqueId().toString();

        if (enabled) {
            disabledPlayers.remove(uuid);
        } else {
            disabledPlayers.add(uuid);
        }

        List<String> snapshot;
        synchronized (disabledPlayers) {
            snapshot = new ArrayList<>(disabledPlayers);
        }
        dataConfig.set("disabled-players", snapshot);
        saveData();
    }

    /** Message de /shfkey toggle : le nouvel état de SHFKey pour le joueur visé. */
    private String toggleStatus(boolean nowEnabled, String playerName) {
        return getMessage(nowEnabled ? "toggle-enabled" : "toggle-disabled").replace("%player%", playerName);
    }

    /** /shfkey toggle avec SHNetwork : le joueur peut être sur ce serveur ou sur un autre. */
    private void toggleOnNetwork(CommandSender sender, String targetName) {
        Player target = Bukkit.getPlayer(targetName);
        if (target != null) {
            boolean nowDisabled = network.toggle(target.getUniqueId());
            sender.sendMessage(toggleStatus(!nowDisabled, target.getName()));
            return;
        }
        Optional<NetworkLink.Target> remote = network.find(targetName);
        if (remote.isEmpty()) {
            sender.sendMessage(getMessage("player-not-found"));
            return;
        }
        String name = remote.get().name();
        network.toggleRemote(remote.get().uuid(), (nowDisabled, error) -> getServer().getScheduler().runTask(this, () -> {
            if (error != null) {
                sender.sendMessage(getMessage("reseau-erreur").replace("%erreur%", error));
            } else {
                sender.sendMessage(toggleStatus(!nowDisabled, name));
            }
        }));
    }

    /** /shfkey import : reprend la liste de data.yml dans la base partagée (une fois, sans écraser ce qui y est déjà). */
    private void importDisabledPlayers(CommandSender sender) {
        if (network == null) {
            sender.sendMessage(getMessage("reseau-inactif"));
            return;
        }
        network.importFile(dataFile.toPath(), (counts, error) -> {
            if (error != null) {
                sender.sendMessage(getMessage("reseau-import-erreur").replace("%erreur%", error));
            } else {
                sender.sendMessage(getMessage("reseau-import")
                    .replace("%ajoutes%", String.valueOf(counts[0]))
                    .replace("%deja%", String.valueOf(counts[1])));
            }
        });
    }

    // --- COMMANDE ---

    static final String TOGGLE_PERMISSION = "shfkey.admin.toggle";
    static final String IMPORT_PERMISSION = "shfkey.admin.import";
    static final String RELOAD_PERMISSION = "shfkey.admin.reload";
    static final String LIST_PERMISSION = "shfkey.admin.list";

    /** Sous-commandes que l'expéditeur peut utiliser ; l'import n'est proposé qu'avec SHNetwork. */
    private List<String> allowed(CommandSender sender) {
        List<String> subs = new ArrayList<>();
        if (sender.hasPermission(TOGGLE_PERMISSION)) subs.add("toggle");
        if (network != null && sender.hasPermission(IMPORT_PERMISSION)) subs.add("import");
        if (sender.hasPermission(RELOAD_PERMISSION)) subs.add("reload");
        if (sender.hasPermission(LIST_PERMISSION)) subs.add("list");
        return subs;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        List<String> allowed = allowed(sender);
        String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        // Sans réseau, l'import reste répondu (reseau-inactif) à qui a le droit de le demander
        boolean importAsked = sub.equals("import") && sender.hasPermission(IMPORT_PERMISSION);
        if (allowed.isEmpty() && !importAsked) {
            sender.sendMessage(getMessage("no-permission"));
            return true;
        }

        if (importAsked) {
            importDisabledPlayers(sender);
        } else if (sub.equals("reload") && allowed.contains("reload")) {
            String error = reloadAll();
            sender.sendMessage(error == null ? getMessage("reloaded") : getMessage("reload-failed").replace("%erreur%", error));
        } else if (sub.equals("list") && allowed.contains("list")) {
            listDisabled(sender);
        } else if (sub.equals("toggle") && allowed.contains("toggle")) {
            if (args.length < 2) {
                sender.sendMessage(getMessage("usage-toggle"));
            } else {
                toggle(sender, args[1]);
            }
        } else {
            sender.sendMessage(getMessage("usage").replace("%subs%", String.join("|", allowed)));
        }
        return true;
    }

    private void toggle(CommandSender sender, String targetName) {
        if (network != null) {
            toggleOnNetwork(sender, targetName);
            return;
        }
        Player target = Bukkit.getPlayer(targetName);
        if (target == null) {
            sender.sendMessage(getMessage("player-not-found"));
            return;
        }

        boolean currentState = isPluginEnabledFor(target);
        setPluginEnabledFor(target, !currentState);
        sender.sendMessage(toggleStatus(!currentState, target.getName()));
    }

    /** /shfkey list : les joueurs pour qui SHFKey est désactivé (la base du réseau avec SHNetwork, sinon data.yml). */
    private void listDisabled(CommandSender sender) {
        if (network != null) {
            network.listDisabled((uuids, error) -> getServer().getScheduler().runTask(this, () -> {
                if (error != null) {
                    sender.sendMessage(getMessage("reseau-erreur").replace("%erreur%", error));
                } else {
                    sendDisabledList(sender, uuids);
                }
            }));
            return;
        }
        List<String> snapshot;
        synchronized (disabledPlayers) {
            snapshot = new ArrayList<>(disabledPlayers);
        }
        sendDisabledList(sender, snapshot);
    }

    private void sendDisabledList(CommandSender sender, List<String> uuids) {
        if (uuids.isEmpty()) {
            sender.sendMessage(getMessage("list-none"));
            return;
        }
        int max = Math.max(1, getConfig().getInt("list-max", 50));
        sender.sendMessage(getMessage("list-header").replace("%count%", String.valueOf(uuids.size())));
        for (String text : uuids.subList(0, Math.min(max, uuids.size()))) {
            sender.sendMessage(getMessage("list-line").replace("%player%", displayName(text)));
        }
        if (uuids.size() > max) {
            sender.sendMessage(getMessage("list-more").replace("%count%", String.valueOf(uuids.size() - max)));
        }
    }

    /** Pseudo d'un UUID connu du serveur (jamais de recherche Mojang), sinon l'UUID. */
    private String displayName(String uuidText) {
        try {
            String name = Bukkit.getOfflinePlayer(UUID.fromString(uuidText)).getName();
            return name == null ? uuidText : name;
        } catch (IllegalArgumentException invalid) {
            return uuidText;
        }
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        List<String> allowed = allowed(sender);
        List<String> result = new ArrayList<>();
        if (args.length == 1) {
            result.addAll(allowed);
        } else if (args.length == 2 && args[0].equalsIgnoreCase("toggle") && allowed.contains("toggle")) {
            for (Player player : Bukkit.getOnlinePlayers()) {
                result.add(player.getName());
            }
        }
        String typed = args[args.length - 1].toLowerCase(Locale.ROOT);
        result.removeIf(candidate -> !candidate.toLowerCase(Locale.ROOT).startsWith(typed));
        return result;
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
            return plugin.getDescription().getVersion();
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