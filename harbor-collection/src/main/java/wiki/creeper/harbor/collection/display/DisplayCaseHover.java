package wiki.creeper.harbor.collection.display;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.collection.api.DisplaySlot;
import wiki.creeper.harbor.collection.content.DisplayCaseRules;
import wiki.creeper.harbor.collection.display.DisplayCaseManager.PlotView;
import wiki.creeper.harbor.collection.display.DisplayCaseManager.SlotRef;
import wiki.creeper.harbor.collection.display.DisplayCaseManager.SlotView;
import wiki.creeper.harbor.core.api.HarborApi;
import wiki.creeper.harbor.world.api.HousingService;
import wiki.creeper.harbor.world.api.PlotPermission;

/**
 * Tank info while looking at it (proposed): every few ticks each player near a loaded plot traces
 * their view to the tank Interaction boxes (display_case.yml hover_distance, walls block it). The
 * looked-at tank shows the fish info (the same lines as the click info) on a Text Display only that
 * player sees, with an edit hint for players who may edit it; empty and locked slots say so. The
 * label stays briefly after looking away so it does not flicker. Main thread only.
 */
final class DisplayCaseHover {

    static final int PERIOD = 4;
    private static final long GRACE_TICKS = 10;
    private static final long RANK_CACHE_TICKS = 20 * 60;

    private static final class State {
        @Nullable SlotRef ref;
        @Nullable TextDisplay label;
        long lastSeen;
        int token;
    }

    private record CachedRank(int rank, long at) {}

    private final DisplayCaseManager manager;
    private final DisplayCaseMenus menus;
    private final Plugin plugin;
    private final HarborApi api;
    private final HousingService housing;
    private final Map<Long, PlotView> plots;
    private final Map<UUID, SlotRef> boxes;
    private final Map<UUID, State> states = new HashMap<>();
    private final Map<String, CachedRank> ranks = new HashMap<>();
    private long now;

    DisplayCaseHover(
            DisplayCaseManager manager,
            DisplayCaseMenus menus,
            Plugin plugin,
            HarborApi api,
            HousingService housing,
            Map<Long, PlotView> plots,
            Map<UUID, SlotRef> boxes) {
        this.manager = manager;
        this.menus = menus;
        this.plugin = plugin;
        this.api = api;
        this.housing = housing;
        this.plots = plots;
        this.boxes = boxes;
    }

    /** Called every {@link #PERIOD} ticks. */
    void tick(long ticks) {
        now = ticks;
        double distance = manager.rules().hoverDistance();
        Map<UUID, Player> watching = new HashMap<>();
        if (distance > 0) {
            for (PlotView view : plots.values()) {
                for (UUID id : view.viewers) {
                    Player p = Bukkit.getPlayer(id);
                    if (p != null) {
                        watching.put(id, p);
                    }
                }
            }
        }
        for (Player player : watching.values()) {
            SlotRef ref = target(player, distance);
            State state = states.computeIfAbsent(player.getUniqueId(), u -> new State());
            if (ref != null) {
                state.lastSeen = ticks;
                if (!ref.equals(state.ref)) {
                    show(player, state, ref);
                }
            }
        }
        Iterator<Map.Entry<UUID, State>> it = states.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, State> e = it.next();
            State state = e.getValue();
            boolean gone = !watching.containsKey(e.getKey());
            if (state.ref != null && (gone || ticks - state.lastSeen > GRACE_TICKS)) {
                hide(state);
            }
            if (gone && state.ref == null) {
                it.remove();
            }
        }
        ranks.values().removeIf(r -> ticks - r.at() > RANK_CACHE_TICKS);
    }

    /**
     * Whether the player is looking at this slot with its label up (click info is then skipped).
     */
    boolean showing(Player player, SlotRef ref) {
        State state = states.get(player.getUniqueId());
        return state != null && ref.equals(state.ref) && state.label != null;
    }

    /** Drops the labels of a plot (its slots were redrawn or removed); they reappear next tick. */
    void invalidate(long plotId) {
        for (State state : states.values()) {
            SlotRef ref = state.ref;
            if (ref != null && ref.plotId() == plotId) {
                hide(state);
            }
        }
        ranks.keySet().removeIf(k -> k.startsWith(plotId + ":"));
    }

    void stop() {
        states.values().forEach(this::hide);
        states.clear();
        ranks.clear();
    }

    // ------------------------------------------------------------------ tracing

    private @Nullable SlotRef target(Player player, double distance) {
        Location eye = player.getEyeLocation();
        World world = eye.getWorld();
        if (world == null) {
            return null;
        }
        Vector dir = eye.getDirection();
        RayTraceResult hit =
                world.rayTraceEntities(
                        eye, dir, distance, 0.0, e -> boxes.containsKey(e.getUniqueId()));
        if (hit == null) {
            return null;
        }
        Entity entity = hit.getHitEntity();
        if (entity == null) {
            return null;
        }
        // A block more than a block in front of the box (a wall) hides it; the tank block itself
        // sits inside the box
        double reach = hit.getHitPosition().distance(eye.toVector()) - 1.0;
        if (reach > 0
                && world.rayTraceBlocks(eye, dir, reach, FluidCollisionMode.NEVER, true) != null) {
            return null;
        }
        return boxes.get(entity.getUniqueId());
    }

    // ------------------------------------------------------------------ labels

    private void show(Player player, State state, SlotRef ref) {
        hide(state);
        state.ref = ref;
        int token = ++state.token;
        PlotView view = plots.get(ref.plotId());
        SlotView sv = view == null ? null : view.views.get(ref.slot());
        if (view == null || sv == null) {
            return;
        }
        boolean editor = housing.can(player, view.plot, PlotPermission.EDIT_DISPLAY);
        if (!DisplayCaseRules.slotOpen(ref.slot(), view.slotCount)) {
            spawn(
                    player,
                    state,
                    sv,
                    List.of(
                            api.messages()
                                    .get(
                                            "collection.display.hover_locked",
                                            Placeholder.unparsed(
                                                    "count", String.valueOf(view.slotCount)))));
            return;
        }
        DisplaySlot row = view.rows.get(ref.slot());
        if (row == null) {
            spawn(
                    player,
                    state,
                    sv,
                    List.of(
                            api.messages()
                                    .get(
                                            editor
                                                    ? "collection.display.hover_empty_edit"
                                                    : "collection.display.empty")));
            return;
        }
        String key = ref.plotId() + ":" + ref.slot() + ":" + row.fish().id();
        CachedRank cached = ranks.get(key);
        if (cached != null) {
            spawn(player, state, sv, lines(view, row, cached.rank(), editor));
            return;
        }
        var _ =
                menus.rank(view, row)
                        .thenAccept(
                                rank -> {
                                    ranks.put(key, new CachedRank(rank, now));
                                    if (state.token != token
                                            || !ref.equals(state.ref)
                                            || !player.isOnline()) {
                                        return;
                                    }
                                    PlotView current = plots.get(ref.plotId());
                                    SlotView slot =
                                            current == null ? null : current.views.get(ref.slot());
                                    DisplaySlot shown =
                                            current == null ? null : current.rows.get(ref.slot());
                                    if (current == null
                                            || slot == null
                                            || shown == null
                                            || !Objects.equals(shown.fish(), row.fish())) {
                                        return;
                                    }
                                    spawn(player, state, slot, lines(current, shown, rank, editor));
                                });
    }

    private List<Component> lines(PlotView view, DisplaySlot row, int rank, boolean editor) {
        List<Component> lines = new ArrayList<>(menus.infoLines(view, row, rank));
        if (editor) {
            lines.add(api.messages().get("collection.display.hover_hint_edit"));
        }
        return lines;
    }

    private void spawn(Player player, State state, SlotView sv, List<Component> lines) {
        World world = sv.center.getWorld();
        if (world == null) {
            return;
        }
        Component text = Component.join(JoinConfiguration.newlines(), lines);
        TextDisplay label =
                world.spawn(
                        sv.center.clone().add(0, 0.9, 0),
                        TextDisplay.class,
                        e -> {
                            e.setPersistent(false);
                            e.setVisibleByDefault(false);
                            e.setBillboard(Display.Billboard.CENTER);
                            e.text(text);
                        });
        player.showEntity(plugin, label);
        state.label = label;
    }

    private void hide(State state) {
        TextDisplay label = state.label;
        if (label != null) {
            label.remove();
        }
        state.label = null;
        state.ref = null;
        state.token++;
    }
}
