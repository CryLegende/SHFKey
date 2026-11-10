package org.Cry_Legende.sHFKey.network;

import org.Cry_Legende.sHNetwork.api.NetPlayer;
import org.Cry_Legende.sHNetwork.api.SHNetworkAPI;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Function;

/** Pont vers SHNetwork. Cette classe n'est chargée que si SHNetwork est installé. */
public final class ShNetworkLink implements NetworkLink {

    private final SHNetworkAPI api;

    private ShNetworkLink() {
        this.api = SHNetworkAPI.get();
    }

    /** Point d'entrée : le plugin ne résout cette classe qu'à cet appel, donc jamais sans SHNetwork. */
    public static NetworkLink open() {
        return new ShNetworkLink();
    }

    @Override
    public CompletableFuture<Boolean> awaitSaved(UUID uuid, Duration timeout) {
        return api.awaitSaved(uuid, timeout);
    }

    @Override
    public void registerSaveTask(Function<Player, CompletableFuture<Void>> task) {
        api.registerSaveTask("SHFKey", task);
    }

    @Override
    public Optional<Target> find(String name) {
        Optional<NetPlayer> found = api.find(name);
        return found.map(p -> new Target(p.uuid(), p.name()));
    }

    @Override
    public void publish(String channel, String json) {
        api.publish(channel, json);
    }

    @Override
    public void subscribe(String channel, Consumer<String> handler) {
        api.subscribe(channel, message -> handler.accept(message.json()), true);
    }
}
