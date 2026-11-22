package wiki.creeper.harbor.quest.service;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.quest.logic.Matchers;

/**
 * Links to optional dependency modules (World, Boss) (docs 20 6.2). Both modules are still being
 * written, so their classes are not used directly; the link goes through the event and accessor
 * names in the documented contract. If the plugin or the event class is missing, only that link is
 * turned off (one log line). Once World and Boss finalize their api, only this class changes.
 *
 * <ul>
 *   <li>Boss {@code BossEncounterEndEvent}: boss objectives of participants when result() is
 *       CLEARED (boss() or bossId() → boss ID, participants(), members() or players() → list of
 *       UUIDs or Players)
 *   <li>World region entry {@code RegionEnterEvent}: getPlayer() or player() + region() → visit
 *       objectives
 *   <li>World {@code ChartProgressEvent}: getPlayer(), player() or uuid() + region() + percent() →
 *       chart_percent objectives
 * </ul>
 *
 * Even without these links, other modules can advance the same objectives through {@link
 * wiki.creeper.harbor.quest.api.ObjectiveSignal}.
 */
final class OptionalBridges implements Listener {

    static final String BOSS_END = "wiki.creeper.harbor.boss.api.event.BossEncounterEndEvent";
    static final String REGION_ENTER = "wiki.creeper.harbor.world.api.event.RegionEnterEvent";
    static final String CHART = "wiki.creeper.harbor.world.api.event.ChartProgressEvent";

    private final QuestEngine engine;

    OptionalBridges(QuestEngine engine) {
        this.engine = engine;
    }

    void register(Plugin plugin) {
        bind(plugin, "Harbor-Boss", BOSS_END, this::onBossEnd);
        bind(plugin, "Harbor-World", REGION_ENTER, this::onRegionEnter);
        bind(plugin, "Harbor-World", CHART, this::onChart);
    }

    private void bind(
            Plugin plugin,
            String owner,
            String className,
            java.util.function.Consumer<Event> handler) {
        Plugin source = Bukkit.getPluginManager().getPlugin(owner);
        if (source == null || !source.isEnabled()) {
            return;
        }
        try {
            Class<? extends Event> type =
                    Class.forName(className, true, source.getClass().getClassLoader())
                            .asSubclass(Event.class);
            Bukkit.getPluginManager()
                    .registerEvent(
                            type,
                            this,
                            EventPriority.MONITOR,
                            (listener, event) -> {
                                if (type.isInstance(event)) {
                                    try {
                                        handler.accept(event);
                                    } catch (RuntimeException e) {
                                        engine.log.warn(
                                                "{} 연결 오류: {}", className, String.valueOf(e));
                                    }
                                }
                            },
                            plugin,
                            false);
            engine.log.info("{} 이벤트에 연결했습니다", className);
        } catch (ClassNotFoundException | ClassCastException e) {
            engine.log.info("{}이 없어 연결하지 않았습니다 (선택 의존)", className);
        }
    }

    private void onBossEnd(Event event) {
        Object result = call(event, "result");
        if (result == null || !String.valueOf(result).equals("CLEARED")) {
            return;
        }
        Optional<HarborId> boss = id(call(event, "boss", "bossId"));
        for (Player player : players(call(event, "participants", "members", "players"))) {
            engine.progress(
                    player, "boss", o -> Matchers.matchesId(o, boss.orElse(null)), 1, false);
        }
    }

    private void onRegionEnter(Event event) {
        Player player = player(event);
        Optional<HarborId> region = id(call(event, "region", "regionId"));
        if (player == null || region.isEmpty()) {
            return;
        }
        var at = java.util.Objects.requireNonNull(player.getLocation());
        var w = at.getWorld();
        String world = w == null ? "" : engine.tracker().worldId(w);
        engine.progress(
                player,
                "visit",
                o -> Matchers.matchesVisit(o, region.get(), world, at.getX(), at.getY(), at.getZ()),
                1,
                false);
    }

    private void onChart(Event event) {
        Player player = player(event);
        Optional<HarborId> region = id(call(event, "region", "regionId"));
        Object percent = call(event, "percent");
        if (player == null || region.isEmpty() || !(percent instanceof Number n)) {
            return;
        }
        engine.progress(
                player,
                "chart_percent",
                o ->
                        o.target().kind() == wiki.creeper.harbor.quest.content.Target.Kind.ANY
                                || region.get().equals(o.target().id()),
                (int) Math.floor(n.doubleValue()),
                true);
    }

    // ------------------------------------------------------------------ Reflection helpers

    private static @Nullable Player player(Event event) {
        Object p = call(event, "getPlayer", "player", "uuid");
        if (p instanceof Player player) {
            return player;
        }
        if (p instanceof UUID uuid) {
            return Bukkit.getPlayer(uuid);
        }
        return null;
    }

    private static List<Player> players(@Nullable Object value) {
        List<Player> result = new ArrayList<>();
        if (value instanceof Collection<?> list) {
            for (Object o : list) {
                if (o instanceof Player p) {
                    result.add(p);
                } else if (o instanceof UUID uuid) {
                    Player p = Bukkit.getPlayer(uuid);
                    if (p != null) {
                        result.add(p);
                    }
                }
            }
        }
        return result;
    }

    /** Reads the toString of a HarborId or TypedId (BossId, RegionId) as the ID. */
    private static Optional<HarborId> id(@Nullable Object value) {
        if (value instanceof HarborId id) {
            return Optional.of(id);
        }
        return value == null ? Optional.empty() : HarborId.tryParse(String.valueOf(value));
    }

    private static @Nullable Object call(Object target, String... names) {
        for (String name : names) {
            try {
                Method m = target.getClass().getMethod(name);
                return m.invoke(target);
            } catch (ReflectiveOperationException | RuntimeException e) {
                // next name
            }
        }
        return null;
    }
}
