package wiki.creeper.harbor.collection.display;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.entity.Display;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import wiki.creeper.harbor.collection.api.CollectionEntry;
import wiki.creeper.harbor.collection.api.DisplayCaseService;
import wiki.creeper.harbor.collection.api.DisplaySlot;
import wiki.creeper.harbor.collection.content.DisplayCaseRules;
import wiki.creeper.harbor.collection.data.CollectionRepository;
import wiki.creeper.harbor.collection.data.DisplayRepository;
import wiki.creeper.harbor.collection.hook.NpcBridge;
import wiki.creeper.harbor.collection.service.CollectionManager;
import wiki.creeper.harbor.common.content.type.FishDef;
import wiki.creeper.harbor.common.donor.DonorTier;
import wiki.creeper.harbor.common.id.FishId;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.common.model.SeasonId;
import wiki.creeper.harbor.core.api.HarborApi;
import wiki.creeper.harbor.core.api.HarborPlayer;
import wiki.creeper.harbor.core.api.RigInstance;
import wiki.creeper.harbor.core.task.Tasks;
import wiki.creeper.harbor.world.api.HousingService;
import wiki.creeper.harbor.world.api.PlotInfo;
import wiki.creeper.harbor.world.api.PlotPermission;
import wiki.creeper.harbor.world.api.TankBounds;
import wiki.creeper.harbor.world.api.event.DisplayAnchorChangedEvent;
import wiki.creeper.harbor.world.api.event.PlotLoadedEvent;
import wiki.creeper.harbor.world.api.event.PlotUnloadedEvent;

/**
 * Personal aquarium display (docs 20 4.4 'personal aquarium display', 0.1 and 0.6 D11/D14). This
 * class is only loaded when Harbor-World is enabled.
 *
 * <ul>
 *   <li>Tank positions (DisplayAnchor) and the edit permission (EDIT_DISPLAY) belong to World. The
 *       display contents (housing_display), slot count, swimming animation and visitor info live
 *       here.
 *   <li>Display slot count = base + bonus for the best reputation tier across seasons (D11). Tanks
 *       beyond the slot count stay empty (display rows are not deleted, so they show again when
 *       slots grow).
 *   <li>Theme slot count = base + donor tier extra slots (D14, Common DonorTier). When a donation
 *       ends and slots shrink, only the themes of the excess slots revert to the default.
 *   <li>On PlotLoadedEvent a Core rig is spawned per slot, visible only to players inside the plot
 *       or within view_radius blocks of it, and removed on PlotUnloadedEvent. Past part_budget
 *       parts per plot, the simple rig is used instead.
 *   <li>One Interaction per slot: players with edit permission get the edit menu (sneak for info);
 *       visitors get an info Text Display shown for a few seconds only to whoever clicked.
 * </ul>
 */
public final class DisplayCaseManager implements DisplayCaseService, Listener {

    private static final int VIEW_TICKS = 20;
    private static final int SWIM_TICKS = 10;

    private final HarborApi api;
    private final Tasks tasks;
    private final Logger log;
    private final HousingService housing;
    private final NpcBridge npc;
    private final ShopThemes themes;
    private final DisplayCaseRenderer renderer;
    private final DisplayCaseMenus menus;
    private final DisplayCaseHover hover;
    private final DisplayCaseClicks clicks;
    private final DisplayCaseChunks chunks;
    private final Map<Long, PlotView> plots = new HashMap<>();
    private final Map<UUID, SlotRef> boxes = new HashMap<>();
    private final Set<Long> loading = new HashSet<>();
    private @Nullable BukkitTask task;
    private long ticks;

    record SlotRef(long plotId, int slot) {}

    /** One loaded plot. */
    static final class PlotView {
        PlotInfo plot;
        final Map<Integer, DisplaySlot> rows = new TreeMap<>();
        final Map<Integer, SlotView> views = new HashMap<>();
        int slotCount;
        int themeSlots;
        Set<UUID> viewers = Set.of();

        PlotView(PlotInfo plot) {
            this.plot = plot;
        }
    }

    /**
     * One drawn slot. Slots of one tank (several fish in a larger tank, World TankBounds) share the
     * tank's box, swim area and label spot; the first slot of a tank owns its click boxes.
     */
    static final class SlotView {
        /** Middle of the swim area. */
        final Location center;

        final Interaction box;
        final TankBounds tank;

        /** Where info labels appear (above the tank). */
        final Location label;

        /** Swim half-extents around {@link #center} (x, y, z). */
        final double rx;

        final double ry;
        final double rz;

        /** All slots of this tank in slot order (just this slot for a 1×1 tank). */
        List<Integer> mates = List.of();

        /** Click boxes of the tank, removed with the first slot. */
        List<Interaction> boxes = List.of();

        @Nullable RigInstance rig;

        /** Floor and tank model displays of this slot, removed with it. */
        final List<Display> props = new ArrayList<>();

        @Nullable Particle particle;

        SlotView(
                Location center,
                Interaction box,
                TankBounds tank,
                Location label,
                double rx,
                double ry,
                double rz) {
            this.center = center;
            this.box = box;
            this.tank = tank;
            this.label = label;
            this.rx = rx;
            this.ry = ry;
            this.rz = rz;
        }

        /** The first slot of its tank (click and hover target). */
        int head(int slot) {
            return mates.isEmpty() ? slot : mates.getFirst();
        }

        void remove() {
            box.remove();
            boxes.forEach(Interaction::remove);
            RigInstance r = rig;
            if (r != null) {
                r.remove();
            }
            props.forEach(Display::remove);
        }
    }

    public DisplayCaseManager(
            Plugin plugin,
            HarborApi api,
            Tasks tasks,
            Logger log,
            HousingService housing,
            NpcBridge npc,
            ShopThemes themes) {
        this.api = api;
        this.tasks = tasks;
        this.log = log;
        this.housing = housing;
        this.npc = npc;
        this.themes = themes;
        this.renderer = new DisplayCaseRenderer(this, api, log, housing, boxes);
        this.menus = new DisplayCaseMenus(this, plugin, api, tasks, themes);
        this.hover = new DisplayCaseHover(this, menus, plugin, api, housing, plots, boxes);
        this.clicks = new DisplayCaseClicks(housing, menus, hover, plots, boxes);
        this.chunks = new DisplayCaseChunks(housing, plots);
    }

    /** Tank click Listener (register it next to this one). */
    public Listener clicks() {
        return clicks;
    }

    /** Tank chunk load Listener (register it next to this one). */
    public Listener chunks() {
        return chunks;
    }

    DisplayCaseRules rules() {
        return api.content().snapshot().single(DisplayCaseRules.TYPE);
    }

    public void start() {
        task = tasks.timer(20, 1, this::tick);
        npc.onTierChanged(this::refreshOwner);
    }

    public void stop() {
        BukkitTask t = task;
        if (t != null) {
            t.cancel();
        }
        hover.stop();
        chunks.clear();
        plots.values().forEach(this::despawn);
        plots.clear();
        boxes.clear();
    }

    // ------------------------------------------------------------------ slot counts

    @Override
    public CompletableFuture<Integer> slotCount(UUID owner) {
        return npc.loadBestTier(owner).thenApply(tier -> rules().slotCount(tier));
    }

    @Override
    public CompletableFuture<Integer> themeSlotCount(UUID owner) {
        return CompletableFuture.supplyAsync(
                        () -> DonorTier.lookup(api.redis(), owner).extraThemeSlots(),
                        tasks.virtual())
                .thenApplyAsync(extra -> rules().themeSlotCount(extra), tasks.main());
    }

    @Override
    public CompletableFuture<List<DisplaySlot>> slots(long plotId) {
        return api.database()
                .async(c -> DisplayRepository.load(c, plotId))
                .thenApplyAsync(List::copyOf, tasks.main());
    }

    // ------------------------------------------------------------------ loading and drawing

    @EventHandler
    public void onPlotLoaded(PlotLoadedEvent event) {
        load(event.plot());
    }

    @EventHandler
    public void onPlotUnloaded(PlotUnloadedEvent event) {
        long id = event.plot().plotId();
        loading.remove(id);
        chunks.forget(id);
        PlotView view = plots.remove(id);
        if (view != null) {
            despawn(view);
        }
    }

    /**
     * A tank was placed or removed. The display only points at the owner's collection (nothing is
     * consumed), so removing a tank empties its slot: the fish goes back to the collection, the
     * owner is told, and a later tank on the same slot number starts empty. A new tank also clears
     * a row left on its slot from before this rule (proposed).
     */
    @EventHandler
    public void onAnchor(DisplayAnchorChangedEvent event) {
        long plotId = event.plot().plotId();
        int slot = event.anchor().slot();
        PlotView view = plots.get(plotId);
        if (view != null) {
            view.plot = event.plot();
        }
        // Take the row out of memory now so the redraw below never shows the old fish
        DisplaySlot row = view == null ? null : view.rows.remove(slot);
        if (view == null || row != null || !event.added()) {
            UUID owner = event.plot().owner();
            var _ =
                    clear(null, plotId, slot)
                            .thenAccept(
                                    removed -> {
                                        if (removed && !event.added()) {
                                            returned(owner, row);
                                        }
                                    });
        }
        if (view != null) {
            render(view);
        }
    }

    private void returned(UUID owner, @Nullable DisplaySlot row) {
        Player player = Bukkit.getPlayer(owner);
        if (player == null || row == null) {
            return;
        }
        String name =
                api.content()
                        .snapshot()
                        .get(FishDef.TYPE, row.fish().id())
                        .map(FishDef::name)
                        .orElse(row.fish().id().name());
        api.messages()
                .send(player, "collection.display.returned", Placeholder.unparsed("name", name));
    }

    /**
     * Recomputes the slot count of that player's plots when their reputation tier changes (NPC
     * ReputationTierChangedEvent).
     */
    public void refreshOwner(UUID owner) {
        for (PlotView view : plots.values()) {
            if (view.plot.owner().equals(owner)) {
                view.slotCount = rules().slotCount(npc.bestTier(owner));
                render(view);
            }
        }
    }

    private void load(PlotInfo plot) {
        long id = plot.plotId();
        UUID owner = plot.owner();
        loading.add(id);
        CompletableFuture<List<DisplaySlot>> rows =
                api.database().async(c -> DisplayRepository.load(c, id));
        CompletableFuture<Integer> donor =
                CompletableFuture.supplyAsync(
                        () -> DonorTier.lookup(api.redis(), owner).extraThemeSlots(),
                        tasks.virtual());
        CompletableFuture<Integer> best = npc.loadBestTier(owner);
        var _ =
                rows.thenCombine(donor, RowsDonor::new)
                        .thenCombine(best, (rd, b) -> new Loaded(rd.rows(), rd.donorExtra(), b))
                        .thenAcceptAsync(loaded -> onLoaded(plot, loaded), tasks.main())
                        .exceptionally(
                                e -> {
                                    log.warn(
                                            "개인 어항을 읽지 못했습니다: 플롯 {} {}",
                                            id,
                                            String.valueOf(e.getMessage()));
                                    return null;
                                });
    }

    /** Bundles of async results. */
    private record RowsDonor(List<DisplaySlot> rows, int donorExtra) {}

    private record Loaded(List<DisplaySlot> rows, int donorExtra, int bestTier) {}

    private void onLoaded(PlotInfo plot, Loaded loaded) {
        long id = plot.plotId();
        if (!loading.remove(id)) {
            return; // unloaded in the meantime
        }
        DisplayCaseRules rules = rules();
        PlotView view = new PlotView(plot);
        loaded.rows().forEach(r -> view.rows.put(r.slot(), r));
        view.slotCount = rules.slotCount(loaded.bestTier());
        view.themeSlots = rules.themeSlotCount(loaded.donorExtra());
        // D14: if a donation ended and theme slots shrank, revert only the excess slots' themes
        List<Integer> themed = new ArrayList<>();
        view.rows
                .values()
                .forEach(
                        r -> {
                            if (r.theme() != null) {
                                themed.add(r.slot());
                            }
                        });
        List<Integer> revert = DisplayCaseRules.themesToRevert(themed, view.themeSlots);
        if (!revert.isEmpty()) {
            for (int slot : revert) {
                DisplaySlot r = view.rows.get(slot);
                if (r != null) {
                    view.rows.put(slot, withTheme(r, null));
                }
            }
            var _ = api.database().asyncVoid(c -> DisplayRepository.revertThemes(c, id, revert));
            Player owner = Bukkit.getPlayer(plot.owner());
            if (owner != null) {
                api.messages().send(owner, "collection.display.theme_reverted");
            }
        }
        PlotView old = plots.put(id, view);
        if (old != null) {
            despawn(old);
        }
        render(view);
    }

    private void despawn(PlotView view) {
        hover.invalidate(view.plot.plotId());
        renderer.despawn(view);
    }

    private void render(PlotView view) {
        hover.invalidate(view.plot.plotId());
        renderer.render(view);
    }

    // ------------------------------------------------------------------ viewers and swimming

    private void tick() {
        ticks++;
        if (ticks % DisplayCaseHover.PERIOD == 0) {
            hover.tick(ticks);
        }
        for (long id : chunks.due()) {
            PlotView view = plots.get(id);
            if (view != null) {
                render(view);
            }
        }
        if (ticks % VIEW_TICKS == 0) {
            renderer.updateViewers(plots.values());
        }
        if (ticks % SWIM_TICKS == 0) {
            DisplayCaseRenderer.swimAll(plots.values());
        }
    }

    /**
     * The plot owner's cumulative collection (from memory when online, otherwise the DB). Completes
     * on the main thread.
     */
    CompletableFuture<Map<FishId, CollectionEntry>> ownerCollection(UUID owner) {
        Optional<HarborPlayer> online = api.players().get(owner);
        if (online.isPresent()) {
            return CompletableFuture.completedFuture(
                    online.get().get(CollectionManager.COMPONENT).total());
        }
        SeasonId season = api.infra().season();
        return api.database()
                .async(c -> CollectionRepository.load(c, owner, season).total())
                .thenApplyAsync(Map::copyOf, tasks.main());
    }

    // ------------------------------------------------------------------ editing

    @Override
    public CompletableFuture<Boolean> set(Player editor, long plotId, int slot, FishId fish) {
        PlotView view = plots.get(plotId);
        if (view == null || !housing.can(editor, view.plot, PlotPermission.EDIT_DISPLAY)) {
            api.messages().send(editor, "collection.display.no_permission");
            return CompletableFuture.completedFuture(false);
        }
        if (!DisplayCaseRules.slotOpen(slot, view.slotCount) || !hasAnchor(plotId, slot)) {
            api.messages()
                    .send(
                            editor,
                            "collection.display.slot_locked",
                            Placeholder.unparsed("count", String.valueOf(view.slotCount)));
            return CompletableFuture.completedFuture(false);
        }
        UUID owner = view.plot.owner();
        return ownerCollection(owner)
                .thenCompose(
                        collection -> {
                            CollectionEntry entry = collection.get(fish);
                            if (entry == null) {
                                api.messages().send(editor, "collection.display.not_registered");
                                return CompletableFuture.completedFuture(false);
                            }
                            DisplaySlot old = view.rows.get(slot);
                            DisplaySlot row =
                                    new DisplaySlot(
                                            slot,
                                            fish,
                                            entry.maxSize(),
                                            entry.bestQuality(),
                                            entry.firstCaught(),
                                            old == null ? null : old.theme());
                            return api.database()
                                    .asyncVoid(c -> DisplayRepository.upsert(c, plotId, owner, row))
                                    .thenApplyAsync(
                                            v -> {
                                                PlotView now = plots.get(plotId);
                                                if (now != null) {
                                                    now.rows.put(slot, row);
                                                    render(now);
                                                }
                                                api.messages()
                                                        .send(editor, "collection.display.set");
                                                return true;
                                            },
                                            tasks.main());
                        });
    }

    @Override
    public CompletableFuture<Boolean> setTheme(
            Player editor, long plotId, int slot, @Nullable HarborId theme) {
        PlotView view = plots.get(plotId);
        if (view == null || !housing.can(editor, view.plot, PlotPermission.EDIT_DISPLAY)) {
            api.messages().send(editor, "collection.display.no_permission");
            return CompletableFuture.completedFuture(false);
        }
        DisplaySlot row = view.rows.get(slot);
        if (row == null) {
            api.messages().send(editor, "collection.display.empty");
            return CompletableFuture.completedFuture(false);
        }
        if (theme != null) {
            if (rules().theme(theme).isEmpty() || !themes.owns(view.plot.owner(), theme)) {
                api.messages().send(editor, "collection.display.theme_not_owned");
                return CompletableFuture.completedFuture(false);
            }
            long others =
                    view.rows.values().stream()
                            .filter(r -> r.slot() != slot && r.theme() != null)
                            .count();
            if (others >= view.themeSlots) {
                api.messages()
                        .send(
                                editor,
                                "collection.display.theme_full",
                                Placeholder.unparsed("slots", String.valueOf(view.themeSlots)));
                return CompletableFuture.completedFuture(false);
            }
        }
        return api.database()
                .async(c -> DisplayRepository.setTheme(c, plotId, slot, theme))
                .thenApplyAsync(
                        ok -> {
                            PlotView now = plots.get(plotId);
                            DisplaySlot current = now == null ? null : now.rows.get(slot);
                            if (ok && now != null && current != null) {
                                now.rows.put(slot, withTheme(current, theme));
                                render(now);
                            }
                            api.messages()
                                    .send(
                                            editor,
                                            ok
                                                    ? "collection.display.theme_set"
                                                    : "collection.admin.failed");
                            return ok;
                        },
                        tasks.main());
    }

    @Override
    public CompletableFuture<Boolean> clear(@Nullable Player editor, long plotId, int slot) {
        PlotView view = plots.get(plotId);
        if (editor != null
                && (view == null || !housing.can(editor, view.plot, PlotPermission.EDIT_DISPLAY))) {
            api.messages().send(editor, "collection.display.no_permission");
            return CompletableFuture.completedFuture(false);
        }
        return api.database()
                .async(c -> DisplayRepository.delete(c, plotId, slot))
                .thenApplyAsync(
                        ok -> {
                            PlotView now = plots.get(plotId);
                            if (now != null && now.rows.remove(slot) != null) {
                                render(now);
                            }
                            if (editor != null) {
                                api.messages().send(editor, "collection.display.cleared");
                            }
                            return ok;
                        },
                        tasks.main());
    }

    private boolean hasAnchor(long plotId, int slot) {
        return housing.displayAnchors(plotId).stream().anyMatch(a -> a.slot() == slot);
    }

    private static DisplaySlot withTheme(DisplaySlot r, @Nullable HarborId theme) {
        return new DisplaySlot(r.slot(), r.fish(), r.size(), r.quality(), r.firstCaught(), theme);
    }
}
