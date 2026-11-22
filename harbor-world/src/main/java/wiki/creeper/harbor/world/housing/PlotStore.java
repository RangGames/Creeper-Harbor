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
 * Store of loaded plots. Loads plot rows from the DB (including expansion by reputation tier),
 * unloads empty plots whose owner is absent, and finds the plot at a position. Admin reset and
 * owner change also clear the cache here. Main thread only.
 */
final class PlotStore {

    private final WorldKit kit;
    private final Function<UUID, CompletableFuture<Integer>> bestTier;
    private final Map<Long, PlotState> loaded = new HashMap<>();
    private final Map<UUID, Long> byOwner = new HashMap<>();
    private final Set<Long> loading = new HashSet<>();

    /**
     * Notice that plot settings changed (harbor:housing, proposed). Other servers keep holding
     * their loaded plots, so a receiving server re-reads that plot from the DB (on drop it unloads
     * it from the cache and re-reads it on the next lookup).
     */
    public record PlotChanged(long plotId, boolean drop) {}

    static final Channel<PlotChanged> CHANGED = new Channel<>("harbor:housing", PlotChanged.class);

    PlotStore(WorldKit kit, Function<UUID, CompletableFuture<Integer>> bestTier) {
        this.kit = kit;
        this.bestTier = bestTier;
    }

    // ------------------------------------------------------------------ world

    String worldId() {
        return kit.balance().housingWorld();
    }

    boolean isHousingWorld(World world) {
        return kit.worldId(world).equals(worldId());
    }

    private @Nullable World world() {
        return kit.bukkitWorld(worldId());
    }

    // ------------------------------------------------------------------ cross-server sync

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

    /** Call after committing to the DB (any thread). */
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
                                        return; // It was unloaded or reloaded in the meantime
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

    // ------------------------------------------------------------------ loading

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

    /** Loads the plot row (unchanged if already loaded). */
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

    /**
     * Expands if the best cross-season reputation tier meets the threshold (never shrinks, D11).
     */
    private void growIfNeeded(PlotState state) {
        WorldBalance b = kit.balance();
        var _ =
                bestTier.apply(state.info.owner())
                        .thenAcceptAsync(
                                tier -> {
                                    if (loaded.get(state.info.plotId()) != state) {
                                        return; // It was unloaded in the meantime
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

    /** Every minute: unloads plots whose owner is absent and that have nobody inside. */
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

    // ------------------------------------------------------------------ lookup

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

    /** The owner's loaded plot (null if none). */
    @Nullable PlotState ofOwner(UUID owner) {
        Long id = byOwner.get(owner);
        return id == null ? null : loaded.get(id);
    }

    int loadedCount() {
        return loaded.size();
    }

    // ------------------------------------------------------------------ admin commands

    /** Admin command: reset (co-builders, tanks, likes). */
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

    /** Admin command: change owner (only if the recipient has no plot). */
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
