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

/** 선박 상태 저장소. 플레이어별 보유 선박 행, 띄운 배, 보트 엔티티 → 선박 ID를 들고 회수(엔티티 제거 + 내구도·상태 저장)를 맡는다. 메인 스레드 전용. */
final class ShipFleet {

    private final WorldKit kit;
    private final Map<UUID, List<ShipRepository.Row>> owned = new HashMap<>();
    private final Map<Long, Afloat> afloat = new HashMap<>();
    private final Map<UUID, Long> byBoat = new HashMap<>();

    ShipFleet(WorldKit kit) {
        this.kit = kit;
    }

    // ------------------------------------------------------------------ 조회

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

    /** 띄운 배 목록의 복사본 (돌면서 회수해도 된다). */
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

    /** 이 엔티티가 띄운 배의 보트면 선박 ID. */
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

    // ------------------------------------------------------------------ 띄우기·회수

    void launch(Afloat a) {
        afloat.put(a.row.shipId(), a);
        byBoat.put(a.boat.getUniqueId(), a.row.shipId());
    }

    /** 회수: 승객을 내리고 엔티티를 지운 뒤 내구도·상태를 저장한다. */
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
