package org.Cry_Legende.sHFKey.network;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.Cry_Legende.sHFKey.SHFKey;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Duration;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.logging.Level;

/**
 * Mode réseau : la liste des joueurs pour qui SHFKey est désactivé vit dans la base partagée, une ligne par joueur.
 *
 * <ul>
 *   <li>à l'arrivée d'un joueur : une fois le départ de l'autre serveur confirmé, son état est lu en base ;
 *       d'ici là il est traité comme actif (valeur par défaut), et cette valeur par défaut n'est jamais écrite ;</li>
 *   <li>à chaque bascule : une seule ligne est ajoutée ou retirée, sur un fil à part (jamais le fil principal) ;</li>
 *   <li>au départ : la barrière de SHNetwork attend que ces écritures soient finies ;</li>
 *   <li>au départ aussi, le joueur sort de la mémoire : la base fait foi à son prochain passage ;</li>
 *   <li>{@code /shfkey toggle} sur un joueur connecté à un autre serveur : l'état est basculé en base, puis le
 *       serveur du joueur est prévenu par le bus.</li>
 * </ul>
 *
 * Le fichier data.yml n'est plus lu ni écrit dans ce mode (sauf par la commande d'import).
 */
public final class NetworkFKey implements Listener {

    private static final int WRITE_ATTEMPTS = 3;
    private static final String CHANNEL = "shfkey";

    private final SHFKey plugin;
    /** Ensemble du plugin : les UUID (texte) des joueurs en ligne pour qui SHFKey est désactivé. */
    private final Set<String> memory;
    private final DisabledDatabase db;
    private final NetworkLink link;
    private final Duration waitMax;
    private final ExecutorService io = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "SHFKey-reseau");
        thread.setDaemon(true);
        return thread;
    });
    private final Object lock = new Object();
    /** Lecture en cours de chaque joueur qui vient d'arriver (jeton de la lecture). */
    private final Map<UUID, Object> loading = new ConcurrentHashMap<>();
    /** Joueurs dont l'état a changé pendant leur lecture : ce changement l'emporte sur ce que la base répond. */
    private final Set<UUID> touched = ConcurrentHashMap.newKeySet();

    public NetworkFKey(SHFKey plugin, Set<String> memory, DisabledDatabase db, NetworkLink link, Duration waitMax) {
        this.plugin = plugin;
        this.memory = memory;
        this.db = db;
        this.link = link;
        this.waitMax = waitMax;
    }

    /** Branche le mode réseau : écoute des connexions, du bus et sauvegarde au départ. */
    public void start() {
        link.registerSaveTask(player -> flush());
        link.subscribe(CHANNEL, this::onRemoteState);
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        // les joueurs déjà là (rechargement à chaud) sont lus comme à une arrivée
        for (Player online : plugin.getServer().getOnlinePlayers()) {
            beginLoad(online.getUniqueId());
        }
    }

    /** Termine les écritures en cours puis ferme la base. */
    public void stop() {
        io.shutdown();
        try {
            if (!io.awaitTermination(5, TimeUnit.SECONDS)) {
                plugin.getLogger().warning("Des écritures de SHFKey n'ont pas pu être terminées à l'arrêt.");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        db.close();
    }

    /** Cherche un joueur connecté au réseau (ce serveur ou un autre) par son pseudo. */
    public java.util.Optional<NetworkLink.Target> find(String name) {
        return link.find(name);
    }

    /** Bascule l'état d'un joueur de ce serveur ; rend vrai s'il est désormais désactivé. */
    public boolean toggle(UUID uuid) {
        String key = uuid.toString();
        boolean nowDisabled;
        synchronized (lock) {
            nowDisabled = memory.add(key);
            if (!nowDisabled) {
                memory.remove(key);
            }
            if (loading.containsKey(uuid)) {
                touched.add(uuid);
            }
        }
        submit(() -> write(uuid, nowDisabled));
        return nowDisabled;
    }

    /** Fixe l'état d'un joueur de ce serveur : vrai pour désactiver SHFKey, faux pour le réactiver. */
    public void set(UUID uuid, boolean disabled) {
        synchronized (lock) {
            apply(uuid, disabled);
            if (loading.containsKey(uuid)) {
                touched.add(uuid);
            }
        }
        submit(() -> write(uuid, disabled));
    }

    /**
     * Bascule l'état d'un joueur connecté à un autre serveur : en base d'abord, puis le serveur du joueur est prévenu.
     * Tourne hors du fil principal ; {@code done} reçoit (vrai si désormais désactivé, message d'erreur éventuel).
     */
    public void toggleRemote(UUID uuid, BiConsumer<Boolean, String> done) {
        submit(() -> {
            try {
                boolean nowDisabled = db.flip(uuid);
                JsonObject o = new JsonObject();
                o.addProperty("uuid", uuid.toString());
                o.addProperty("disabled", nowDisabled);
                link.publish(CHANNEL, o.toString());
                done.accept(nowDisabled, null);
            } catch (SQLException e) {
                done.accept(null, e.getMessage());
            }
        });
    }

    /** Un autre serveur a changé l'état d'un joueur : si le joueur est ici, la mémoire suit. Fil principal. */
    private void onRemoteState(String json) {
        try {
            JsonObject o = JsonParser.parseString(json).getAsJsonObject();
            UUID uuid = UUID.fromString(o.get("uuid").getAsString());
            boolean disabled = o.get("disabled").getAsBoolean();
            if (plugin.getServer().getPlayer(uuid) == null) {
                return;
            }
            synchronized (lock) {
                apply(uuid, disabled);
                if (loading.containsKey(uuid)) {
                    touched.add(uuid);
                }
            }
        } catch (RuntimeException e) {
            plugin.getLogger().log(Level.WARNING, "Message " + CHANNEL + " illisible : " + e.getMessage());
        }
    }

    private void apply(UUID uuid, boolean disabled) {
        if (disabled) {
            memory.add(uuid.toString());
        } else {
            memory.remove(uuid.toString());
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        beginLoad(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        synchronized (lock) {
            loading.remove(uuid);
            touched.remove(uuid);
            memory.remove(uuid.toString());
        }
    }

    private void beginLoad(UUID uuid) {
        Object token = new Object();
        synchronized (lock) {
            loading.put(uuid, token);
            touched.remove(uuid);
            memory.remove(uuid.toString());
        }
        link.awaitSaved(uuid, waitMax).whenComplete((saved, error) -> {
            if (error != null || !Boolean.TRUE.equals(saved)) {
                plugin.getLogger().warning("Départ de " + uuid + " non confirmé par l'autre serveur"
                    + (error != null ? " (" + error + ")" : "") + " : état de SHFKey relu quand même.");
            }
            submit(() -> load(uuid, token));
        });
    }

    private void load(UUID uuid, Object token) {
        boolean disabled;
        try {
            disabled = db.isDisabled(uuid);
        } catch (SQLException e) {
            // le joueur garde l'état par défaut (actif) plutôt que de rester bloqué
            plugin.getLogger().log(Level.WARNING, "État de SHFKey de " + uuid + " non relu en base : " + e.getMessage());
            synchronized (lock) {
                loading.remove(uuid, token);
                touched.remove(uuid);
            }
            return;
        }
        synchronized (lock) {
            if (loading.get(uuid) != token) {
                return; // parti ou revenu entre-temps
            }
            loading.remove(uuid);
            if (!touched.remove(uuid)) {
                apply(uuid, disabled);
            }
        }
    }

    private void write(UUID uuid, boolean disabled) {
        for (int attempt = 1; attempt <= WRITE_ATTEMPTS; attempt++) {
            try {
                db.setDisabled(uuid, disabled);
                return;
            } catch (SQLException e) {
                if (attempt == WRITE_ATTEMPTS) {
                    plugin.getLogger().log(Level.WARNING, "État de SHFKey de " + uuid + " non écrit en base : " + e.getMessage());
                    return;
                }
                try {
                    Thread.sleep(200L);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    /**
     * Reprend les joueurs de la liste disabled-players de data.yml dans la base, sans toucher à ceux qui y sont déjà.
     * Tourne hors du fil principal ; {@code done} reçoit (nombre ajouté, nombre déjà présent) ou l'erreur.
     */
    public void importFile(Path file, BiConsumer<int[], String> done) {
        submit(() -> {
            Set<UUID> players = new HashSet<>();
            try {
                if (Files.exists(file)) {
                    for (String text : YamlConfiguration.loadConfiguration(file.toFile()).getStringList("disabled-players")) {
                        try {
                            players.add(UUID.fromString(text.trim()));
                        } catch (IllegalArgumentException invalid) {
                            plugin.getLogger().warning("UUID invalide ignoré dans " + file.getFileName() + " : " + text);
                        }
                    }
                }
                int added = db.importAll(players);
                done.accept(new int[]{added, players.size() - added}, null);
            } catch (SQLException e) {
                done.accept(null, e.getMessage());
            }
        });
    }

    /** Futur terminé quand toutes les écritures déjà demandées sont faites (le fil d'écriture les traite dans l'ordre). */
    private CompletableFuture<Void> flush() {
        CompletableFuture<Void> done = new CompletableFuture<>();
        try {
            io.execute(() -> done.complete(null));
        } catch (RejectedExecutionException stopped) {
            done.complete(null);
        }
        return done;
    }

    private void submit(Runnable task) {
        try {
            io.execute(task);
        } catch (RejectedExecutionException stopped) {
            // plugin arrêté : plus rien à écrire
        }
    }
}
