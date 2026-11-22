package wiki.creeper.harbor.world.ship;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.core.api.RigInstance;
import wiki.creeper.harbor.world.WorldKit;
import wiki.creeper.harbor.world.api.ShipInfo;
import wiki.creeper.harbor.world.api.ShipState;
import wiki.creeper.harbor.world.content.ShipDef;

/**
 * Ship state store. Holds each player's owned ship rows, launched boats and boat entity → ship ID,
 * and handles recall (entity removal + saving durability and state). Main thread only.
 */
final class ShipFleet {

    private final WorldKit kit;
    private final Map<UUID, List<ShipRepository.Row>> owned = new HashMap<>();
    private final Map<Long, Afloat> afloat = new HashMap<>();
    private final Map<UUID, Long> byBoat = new HashMap<>();

    ShipFleet(WorldKit kit) {
        this.kit = kit;
    }

    // ------------------------------------------------------------------ lookup

    Optional<ShipDef> def(HarborId type) {
        return kit.api().content().snapshot().get(ShipDef.TYPE, type);
    }

    ShipInfo info(ShipRepository.Row row) {
        Optional<ShipDef> def = def(row.type());
        int max = def.map(d -> ShipMath.maxDurability(d, row.upgrades())).orElse(row.durability());
        ShipState state = afloat.containsKey(row.shipId()) ? ShipState.AFLOAT : row.state();
        return new ShipInfo(
                row.shipId(),
                row.owner(),
                row.type(),
                def.map(ShipDef::name).orElse(row.type().name()),
                row.upgrades(),
                row.durability(),
                max,
                state,
                def.map(ShipDef::flags).orElse(Set.of()));
    }

    List<ShipRepository.Row> rows(UUID owner) {
        return owned.getOrDefault(owner, List.of());
    }

    void setRows(UUID owner, List<ShipRepository.Row> rows) {
        owned.put(owner, new ArrayList<>(rows));
    }

    void addRow(UUID owner, ShipRepository.Row row) {
        owned.computeIfAbsent(owner, u -> new ArrayList<>()).add(row);
    }

    void forget(UUID owner) {
        owned.remove(owner);
    }

    /** Copy of the launched boat list (safe to recall while iterating). */
    List<Afloat> afloatList() {
        return new ArrayList<>(afloat.values());
    }

    @Nullable Afloat afloat(long shipId) {
        return afloat.get(shipId);
    }

    boolean isAfloat(long shipId) {
        return afloat.containsKey(shipId);
    }

    int afloatCount() {
        return afloat.size();
    }

    /** Ship ID if this entity is the boat of a launched ship. */
    @Nullable Long shipIdOf(UUID entity) {
        return byBoat.get(entity);
    }

    boolean isShipBoat(UUID entity) {
        return byBoat.containsKey(entity);
    }

    Optional<Afloat> aboardAfloat(Player player) {
        Entity vehicle = player.getVehicle();
        if (vehicle == null) {
            return Optional.empty();
        }
        Long id = byBoat.get(vehicle.getUniqueId());
        return id == null ? Optional.empty() : Optional.ofNullable(afloat.get(id));
    }

    Optional<Afloat> byEntity(Entity boat) {
        Long id = byBoat.get(boat.getUniqueId());
        return id == null ? Optional.empty() : Optional.ofNullable(afloat.get(id));
    }

    // ------------------------------------------------------------------ launch and recall

    void launch(Afloat a) {
        afloat.put(a.row.shipId(), a);
        byBoat.put(a.boat.getUniqueId(), a.row.shipId());
    }

    /** Recall: dismounts passengers, removes the entity, then saves durability and state. */
    CompletableFuture<Void> recall(Afloat a, ShipState state) {
        a.engine.stop();
        afloat.remove(a.row.shipId());
        byBoat.remove(a.boat.getUniqueId());
        RigInstance rig = a.rig;
        if (rig != null) {
            rig.remove();
        }
        if (a.boat.isValid()) {
            a.boat.eject();
            a.boat.remove();
        }
        ShipRepository.Row row = a.row.withDurability(a.row.durability(), state);
        replace(row);
        long id = row.shipId();
        int durability = row.durability();
        return kit.api()
                .database()
                .asyncVoid(c -> ShipRepository.updateState(c, id, durability, state));
    }

    void replace(ShipRepository.Row row) {
        List<ShipRepository.Row> list = owned.get(row.owner());
        if (list == null) {
            return;
        }
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).shipId() == row.shipId()) {
                list.set(i, row);
                return;
            }
        }
        list.add(row);
    }
}
