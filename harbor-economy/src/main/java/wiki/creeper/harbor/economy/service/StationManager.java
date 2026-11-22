package wiki.creeper.harbor.economy.service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.content.type.Balance;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.core.api.HarborApi;
import wiki.creeper.harbor.core.api.event.ContentReloadedEvent;
import wiki.creeper.harbor.economy.content.StationDef;

/**
 * Crafting stations and auction boards (docs 20 7.4 "Stations: place an Interaction entity at the
 * stations coordinates; clicking shows only that station's recipes"). Entities are not saved
 * (setPersistent false) and are recreated on content reload and world load. At startup, entities
 * still carrying the PDC marker are removed.
 */
public final class StationManager implements Listener {

    /** Station click handling (crafting screen, auction house). */
    public interface Opener {
        void open(Player player, StationDef station);
    }

    private final HarborApi api;
    private final NamespacedKey key;
    private final List<Interaction> spawned = new ArrayList<>();
    private @Nullable Opener opener;

    public StationManager(Plugin plugin, HarborApi api) {
        this.api = api;
        this.key = new NamespacedKey(plugin, "station");
    }

    public void setOpener(Opener opener) {
        this.opener = opener;
    }

    public Collection<StationDef> all() {
        return api.content().snapshot().all(StationDef.TYPE).values();
    }

    public Optional<StationDef> get(HarborId id) {
        return api.content().snapshot().get(StationDef.TYPE, id);
    }

    /** Content world ID → Bukkit world (balance.yml world_alias, same rule as Fishing and NPC). */
    Optional<World> world(String contentWorld) {
        World direct = Bukkit.getWorld(contentWorld);
        if (direct != null) {
            return Optional.of(direct);
        }
        Balance balance = api.content().snapshot().single(Balance.TYPE);
        for (String alias : balance.keys("world_alias")) {
            if (balance.string("world_alias." + alias).orElse("").equals(contentWorld)) {
                World w = Bukkit.getWorld(alias);
                if (w != null) {
                    return Optional.of(w);
                }
            }
        }
        return Optional.empty();
    }

    Optional<Location> location(StationDef station) {
        return world(station.world())
                .map(w -> new Location(w, station.x(), station.y(), station.z()));
    }

    /** Nearest station within the radius (any kind if kind is null). */
    public Optional<StationDef> nearest(
            Player player, StationDef.@Nullable Kind kind, double radius) {
        Location at = Objects.requireNonNull(player.getLocation());
        StationDef best = null;
        double bestDist = radius * radius;
        for (StationDef station : all()) {
            if (kind != null && station.kind() != kind) {
                continue;
            }
            Optional<Location> loc = location(station);
            if (loc.isEmpty() || !Objects.equals(loc.get().getWorld(), at.getWorld())) {
                continue;
            }
            double d = loc.get().distanceSquared(at);
            if (d <= bestDist) {
                bestDist = d;
                best = station;
            }
        }
        return Optional.ofNullable(best);
    }

    /** Whether the content has at least one station of this kind. */
    public boolean anyOf(StationDef.Kind kind) {
        return all().stream().anyMatch(s -> s.kind() == kind);
    }

    // ------------------------------------------------------------------ Entities

    public void respawn() {
        despawn();
        for (World world : Bukkit.getWorlds()) {
            cleanup(world);
        }
        for (StationDef station : all()) {
            location(station).ifPresent(loc -> spawn(station, loc));
        }
    }

    private void spawn(StationDef station, Location loc) {
        World world = Objects.requireNonNull(loc.getWorld());
        Interaction entity =
                world.spawn(
                        loc,
                        Interaction.class,
                        e -> {
                            e.setPersistent(false);
                            e.setInteractionWidth(station.width());
                            e.setInteractionHeight(station.height());
                            e.setResponsive(true);
                            e.getPersistentDataContainer()
                                    .set(key, PersistentDataType.STRING, station.id().toString());
                        });
        spawned.add(entity);
    }

    private void cleanup(World world) {
        for (Entity entity : world.getEntitiesByClass(Interaction.class)) {
            if (entity.getPersistentDataContainer().has(key, PersistentDataType.STRING)) {
                entity.remove();
            }
        }
    }

    public void despawn() {
        spawned.forEach(Entity::remove);
        spawned.clear();
    }

    @EventHandler
    public void onReload(ContentReloadedEvent event) {
        if (event.reloadedTypes().contains(StationDef.TYPE.name())
                || event.reloadedTypes().contains(Balance.TYPE.name())) {
            respawn();
        }
    }

    @EventHandler
    public void onWorldLoad(WorldLoadEvent event) {
        for (StationDef station : all()) {
            location(station)
                    .filter(l -> Objects.equals(l.getWorld(), event.getWorld()))
                    .ifPresent(l -> spawn(station, l));
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onInteract(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND
                || !(event.getRightClicked() instanceof Interaction interaction)) {
            return;
        }
        String id = interaction.getPersistentDataContainer().get(key, PersistentDataType.STRING);
        if (id == null) {
            return;
        }
        event.setCancelled(true);
        Opener o = opener;
        HarborId stationId = HarborId.tryParse(id).orElse(null);
        if (o == null || stationId == null) {
            return;
        }
        get(stationId).ifPresent(station -> o.open(event.getPlayer(), station));
    }
}
