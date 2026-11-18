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
 * 제작 스테이션·경매 게시판 (docs 20 7.4 "스테이션: stations 좌표에 Interaction 엔티티를 두고, 클릭하면 해당 station의 레시피만
 * 보여줍니다"). 엔티티는 저장하지 않고(setPersistent false) 콘텐츠 리로드·월드 로드 때 다시 만든다. 시작할 때 PDC 표시가 남은 엔티티는 지운다.
 */
public final class StationManager implements Listener {

    /** 스테이션 클릭 처리 (제작 화면, 경매장). */
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

    /** 콘텐츠 월드 ID → Bukkit 월드 (balance.yml world_alias, Fishing·NPC와 같은 규칙). */
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

    /** 반경 안의 가장 가까운 스테이션 (kind가 null이면 종류 무관). */
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

    /** 이 종류의 스테이션이 콘텐츠에 하나라도 있는지. */
    public boolean anyOf(StationDef.Kind kind) {
        return all().stream().anyMatch(s -> s.kind() == kind);
    }

    // ------------------------------------------------------------------ 엔티티

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
