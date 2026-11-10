package org.Cry_Legende.sHFKey.network;

import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Ce dont le mode réseau a besoin de SHNetwork. Cette interface ne mentionne aucune classe de SHNetwork :
 * le plugin la manipule sans jamais charger {@link ShNetworkLink}, donc sans SHNetwork il ne se passe rien.
 */
public interface NetworkLink {

    /** Joueur connecté au réseau, sur ce serveur ou sur un autre. */
    record Target(UUID uuid, String name) {
    }

    /** Attend que le serveur de départ ait fini de sauvegarder le joueur ; se termine hors du fil principal. */
    CompletableFuture<Boolean> awaitSaved(UUID uuid, Duration timeout);

    /** Inscrit la tâche exécutée au départ de chaque joueur (fil principal, pendant PlayerQuitEvent). */
    void registerSaveTask(Function<Player, CompletableFuture<Void>> task);

    /** Cherche un joueur connecté au réseau par son pseudo (sans tenir compte de la casse). */
    Optional<Target> find(String name);

    /** Publie un message JSON aux autres serveurs, sur le sujet donné. */
    void publish(String channel, String json);

    /** Reçoit sur le fil principal les messages JSON des autres serveurs pour le sujet donné. */
    void subscribe(String channel, Consumer<String> handler);
}
