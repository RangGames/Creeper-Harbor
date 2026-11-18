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
 * 선택 의존 모듈(World·Boss) 연결 (docs 20 6.2). 두 모듈은 지금 같이 작성 중이라 클래스를 직접 쓰지 않고, 문서 계약의 이벤트 이름과 접근자 이름으로
 * 연결한다. 플러그인이 없거나 이벤트 클래스가 없으면 그 연결만 꺼진다 (로그 1줄). World·Boss가 api를 확정하면 여기만 바꾼다.
 *
 * <ul>
 *   <li>Boss {@code BossEncounterEndEvent}: result()가 CLEARED인 참가자의 boss 목표 (boss()·bossId() → 보스
 *       ID, participants()·members()·players() → UUID 또는 Player 목록)
 *   <li>World 지역 진입 {@code RegionEnterEvent}: getPlayer()·player() + region() → visit 목표
 *   <li>World {@code ChartProgressEvent}: getPlayer()·player()·uuid() + region() + percent() →
 *       chart_percent 목표
 * </ul>
 *
 * 연결이 안 되어도 다른 모듈은 {@link wiki.creeper.harbor.quest.api.ObjectiveSignal}로 같은 목표를 진행시킬 수 있다.
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

    // ------------------------------------------------------------------ 반사 도우미

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

    /** HarborId 또는 TypedId(BossId·RegionId)의 toString을 ID로 읽는다. */
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
                // 다음 이름
            }
        }
        return null;
    }
}
