package wiki.creeper.harbor.world.housing;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.redis.Channel;
import wiki.creeper.harbor.world.WorldKit;
import wiki.creeper.harbor.world.api.DisplayAnchor;
import wiki.creeper.harbor.world.api.PlotInfo;
import wiki.creeper.harbor.world.api.event.PlotLoadedEvent;
import wiki.creeper.harbor.world.api.event.PlotUnloadedEvent;
import wiki.creeper.harbor.world.content.WorldBalance;
import wiki.creeper.harbor.world.housing.HousingManager.PlotState;

/**
 * 적재된 플롯 저장소. 플롯 행을 DB에서 읽어 올리고(평판 등급에 따른 확장 포함), 주인 없는 빈 플롯을 내리고, 좌표 → 플롯을 찾는다. 관리 명령의 초기화·주인 바꾸기도
 * 여기서 캐시를 비운다. 메인 스레드 전용.
 */
final class PlotStore {

    private final WorldKit kit;
    private final Function<UUID, CompletableFuture<Integer>> bestTier;
    private final Map<Long, PlotState> loaded = new HashMap<>();
    private final Map<UUID, Long> byOwner = new HashMap<>();
    private final Set<Long> loading = new HashSet<>();

    /**
     * 플롯 설정이 바뀌었다는 알림 (harbor:housing, 추가 제안). 다른 서버도 적재한 플롯을 계속 들고 있으므로, 받은 서버는 그 플롯 정보를 DB에서 다시
     * 읽는다 (drop이면 캐시에서 내리고 다음 조회 때 다시 읽는다).
     */
    public record PlotChanged(long plotId, boolean drop) {}

    static final Channel<PlotChanged> CHANGED = new Channel<>("harbor:housing", PlotChanged.class);

    PlotStore(WorldKit kit, Function<UUID, CompletableFuture<Integer>> bestTier) {
        this.kit = kit;
        this.bestTier = bestTier;
    }

    // ------------------------------------------------------------------ 월드

    String worldId() {
        return kit.balance().housingWorld();
    }

    boolean isHousingWorld(World world) {
        return kit.worldId(world).equals(worldId());
    }

    private @Nullable World world() {
        return kit.bukkitWorld(worldId());
    }

    // ------------------------------------------------------------------ 서버 간 동기화

    void subscribe() {
        kit.api()
                .bus()
                .subscribe(
                        CHANGED,
                        envelope -> {
                            if (envelope.server().equals(kit.api().infra().serverId())) {
                                return;
                            }
                            PlotChanged change = envelope.payload();
                            kit.tasks().main().execute(() -> onChanged(change));
                        });
    }

    /** DB에 커밋한 뒤 부른다 (어느 스레드든 된다). */
    void publishChanged(long plotId, boolean drop) {
        kit.api().bus().publish(CHANGED, new PlotChanged(plotId, drop));
    }

    private void onChanged(PlotChanged change) {
        long plotId = change.plotId();
        PlotState state = loaded.get(plotId);
        if (state == null) {
            return;
        }
        if (change.drop()) {
            drop(state);
            return;
        }
        var _ =
                kit.api()
                        .database()
                        .async(c -> HousingRepository.byId(c, plotId))
                        .thenAcceptAsync(
                                row -> {
                                    if (loaded.get(plotId) != state) {
                                        return; // 그사이 내려가거나 다시 올라왔다
                                    }
                                    if (row.isEmpty()
                                            || !row.get().owner().equals(state.info.owner())) {
                                        drop(state);
                                        return;
                                    }
                                    state.info = info(row.get());
                                },
                                kit.tasks().main());
    }

    private void drop(PlotState state) {
        loaded.remove(state.info.plotId());
        byOwner.remove(state.info.owner(), state.info.plotId());
    }

    // ------------------------------------------------------------------ 적재

    PlotInfo info(HousingRepository.PlotRow row) {
        WorldBalance b = kit.balance();
        PlotGrid.Bounds bounds =
                PlotGrid.bounds(
                        new PlotGrid.Cell(row.gridX(), row.gridZ()), b.housingCell(), row.size());
        return new PlotInfo(
                row.plotId(),
                row.owner(),
                row.ownerName(),
                row.gridX(),
                row.gridZ(),
                row.size(),
                row.theme(),
                row.policy(),
                row.likes(),
                worldId(),
                bounds.minX(),
                bounds.minZ(),
                bounds.maxX(),
                bounds.maxZ(),
                row.spawnX(),
                row.spawnY(),
                row.spawnZ(),
                row.spawnYaw(),
                row.members());
    }

    /** 플롯 행을 읽어 올린다 (이미 있으면 그대로). */
    CompletableFuture<Optional<PlotState>> loadById(long plotId) {
        PlotState existing = loaded.get(plotId);
        if (existing != null) {
            return CompletableFuture.completedFuture(Optional.of(existing));
        }
        return kit.api()
                .database()
                .async(
                        c -> {
                            Optional<HousingRepository.PlotRow> row =
                                    HousingRepository.byId(c, plotId);
                            return row.isEmpty()
                                    ? Optional.<Object[]>empty()
                                    : Optional.of(
                                            new Object[] {
                                                row.get(), HousingRepository.anchors(c, plotId)
                                            });
                        })
                .thenApplyAsync(this::install, kit.tasks().main());
    }

    CompletableFuture<Optional<PlotState>> loadOwner(UUID owner) {
        Long id = byOwner.get(owner);
        if (id != null && loaded.containsKey(id)) {
            return CompletableFuture.completedFuture(Optional.ofNullable(loaded.get(id)));
        }
        return kit.api()
                .database()
                .async(
                        c -> {
                            Optional<HousingRepository.PlotRow> row =
                                    HousingRepository.byOwner(c, owner);
                            return row.isEmpty()
                                    ? Optional.<Object[]>empty()
                                    : Optional.of(
                                            new Object[] {
                                                row.get(),
                                                HousingRepository.anchors(c, row.get().plotId())
                                            });
                        })
                .thenApplyAsync(this::install, kit.tasks().main());
    }

    @SuppressWarnings("unchecked")
    private Optional<PlotState> install(Optional<Object[]> raw) {
        if (raw.isEmpty()) {
            return Optional.empty();
        }
        HousingRepository.PlotRow row = (HousingRepository.PlotRow) raw.get()[0];
        List<DisplayAnchor> anchors = (List<DisplayAnchor>) raw.get()[1];
        PlotState existing = loaded.get(row.plotId());
        if (existing != null) {
            return Optional.of(existing);
        }
        PlotState state = new PlotState(info(row), anchors, row.redstone());
        loaded.put(row.plotId(), state);
        byOwner.put(row.owner(), row.plotId());
        Bukkit.getPluginManager().callEvent(new PlotLoadedEvent(state.info));
        growIfNeeded(state);
        return Optional.of(state);
    }

    /** 시즌 간 최고 평판 등급이 기준 이상이면 넓힌다 (줄이지 않음, D11). */
    private void growIfNeeded(PlotState state) {
        WorldBalance b = kit.balance();
        var _ =
                bestTier.apply(state.info.owner())
                        .thenAcceptAsync(
                                tier -> {
                                    if (loaded.get(state.info.plotId()) != state) {
                                        return; // 그사이 내려갔다
                                    }
                                    int size =
                                            PlotGrid.size(
                                                    state.info.size(),
                                                    tier,
                                                    b.housingLargeTier(),
                                                    b.housingBaseSize(),
                                                    b.housingLargeSize());
                                    if (size <= state.info.size()) {
                                        return;
                                    }
                                    long id = state.info.plotId();
                                    var _ =
                                            kit.api()
                                                    .database()
                                                    .asyncVoid(
                                                            c ->
                                                                    HousingRepository.updateSize(
                                                                            c, id, size));
                                    PlotInfo old = state.info;
                                    PlotGrid.Bounds bounds =
                                            PlotGrid.bounds(
                                                    new PlotGrid.Cell(old.gridX(), old.gridZ()),
                                                    b.housingCell(),
                                                    size);
                                    state.info =
                                            new PlotInfo(
                                                    old.plotId(),
                                                    old.owner(),
                                                    old.ownerName(),
                                                    old.gridX(),
                                                    old.gridZ(),
                                                    size,
                                                    old.theme(),
                                                    old.visitPolicy(),
                                                    old.likes(),
                                                    old.world(),
                                                    bounds.minX(),
                                                    bounds.minZ(),
                                                    bounds.maxX(),
                                                    bounds.maxZ(),
                                                    old.spawnX(),
                                                    old.spawnY(),
                                                    old.spawnZ(),
                                                    old.spawnYaw(),
                                                    old.members());
                                    Player owner = Bukkit.getPlayer(old.owner());
                                    if (owner != null) {
                                        kit.send(
                                                owner,
                                                "world.housing.grown",
                                                Placeholder.unparsed("size", String.valueOf(size)));
                                    }
                                },
                                kit.tasks().main());
    }

    /** 1분마다: 주인이 없고 안에 아무도 없는 플롯을 내린다. */
    void unloadIdle() {
        World world = world();
        for (PlotState state : List.copyOf(loaded.values())) {
            if (Bukkit.getPlayer(state.info.owner()) != null) {
                continue;
            }
            boolean occupied = false;
            if (world != null) {
                for (Player p : world.getPlayers()) {
                    Location at = WorldKit.loc(p);
                    if (state.info.contains(state.info.world(), at.getX(), at.getZ())) {
                        occupied = true;
                        break;
                    }
                }
            }
            if (!occupied) {
                loaded.remove(state.info.plotId());
                byOwner.remove(state.info.owner());
                Bukkit.getPluginManager().callEvent(new PlotUnloadedEvent(state.info));
            }
        }
    }

    // ------------------------------------------------------------------ 조회

    Optional<PlotState> stateAt(Location location) {
        World w = location.getWorld();
        if (w == null || !isHousingWorld(w)) {
            return Optional.empty();
        }
        PlotGrid.Cell cell =
                PlotGrid.cellAt(location.getX(), location.getZ(), kit.balance().housingCell());
        long id = PlotGrid.index(cell.x(), cell.z());
        PlotState state = loaded.get(id);
        if (state == null) {
            if (loading.add(id)) {
                var _ = loadById(id).whenComplete((r, e) -> loading.remove(id));
            }
            return Optional.empty();
        }
        return state.info.contains(state.info.world(), location.getX(), location.getZ())
                ? Optional.of(state)
                : Optional.empty();
    }

    Optional<PlotState> state(long plotId) {
        return Optional.ofNullable(loaded.get(plotId));
    }

    /** 주인의 적재된 플롯 (없으면 null). */
    @Nullable PlotState ofOwner(UUID owner) {
        Long id = byOwner.get(owner);
        return id == null ? null : loaded.get(id);
    }

    int loadedCount() {
        return loaded.size();
    }

    // ------------------------------------------------------------------ 관리 명령

    /** 관리 명령: 초기화 (공동 건축자·수조·좋아요). */
    CompletableFuture<Boolean> adminReset(UUID owner) {
        return kit.api()
                .database()
                .async(
                        c -> {
                            Optional<HousingRepository.PlotRow> row =
                                    HousingRepository.byOwner(c, owner);
                            if (row.isPresent()) {
                                HousingRepository.reset(c, row.get().plotId());
                            }
                            return row.map(HousingRepository.PlotRow::plotId);
                        })
                .thenApplyAsync(
                        id -> {
                            id.ifPresent(
                                    i -> {
                                        loaded.remove(i);
                                        byOwner.remove(owner);
                                        publishChanged(i, true);
                                    });
                            return id.isPresent();
                        },
                        kit.tasks().main());
    }

    /** 관리 명령: 주인 바꾸기 (받는 쪽에 플롯이 없을 때만). */
    CompletableFuture<Boolean> adminTransfer(UUID from, UUID to) {
        return kit.api()
                .database()
                .async(
                        c -> {
                            Optional<HousingRepository.PlotRow> src =
                                    HousingRepository.byOwner(c, from);
                            if (src.isEmpty() || HousingRepository.byOwner(c, to).isPresent()) {
                                return Optional.<Long>empty();
                            }
                            HousingRepository.transfer(c, src.get().plotId(), to);
                            return Optional.of(src.get().plotId());
                        })
                .thenApplyAsync(
                        moved -> {
                            Long id = byOwner.remove(from);
                            if (id != null) {
                                loaded.remove(id);
                            }
                            moved.ifPresent(i -> publishChanged(i, true));
                            return moved.isPresent();
                        },
                        kit.tasks().main());
    }
}
