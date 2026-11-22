package wiki.creeper.harbor.shop.pass;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;
import org.slf4j.Logger;

/**
 * Bridges events from optional-dependency modules (Quest, World, Boss) to pass exp (docs 20 12.2
 * subscription table). Those modules are being built alongside Shop, so there is no compile
 * dependency; if the plugin is enabled, the event class is looked up by name and subscribed (same
 * approach as Quest's OptionalBridges). Otherwise it does nothing.
 *
 * <ul>
 *   <li>Harbor-Quest {@code QuestCompletedEvent}: type daily → quest_daily, weekly → quest_weekly
 *   <li>Harbor-World {@code ChartProgressEvent}: chart_percent per 1%p of chart
 *   <li>Harbor-Boss {@code BossEncounterEndEvent}: if CLEARED and not a story fight, to
 *       reward-eligible participants boss_clear
 * </ul>
 *
 * These modules do not call {@code SeasonPassService.registerSource} again with the same source
 * keys (double grants). If event names or accessors change, only this needs fixing.
 */
public final class OptionalPassSources implements Listener {

    private final PassManager passes;
    private final Plugin shop;
    private final Logger log;
    private final Map<String, Integer> chartFloor = new HashMap<>();

    public OptionalPassSources(PassManager passes, Plugin shop, Logger log) {
        this.passes = passes;
        this.shop = shop;
        this.log = log;
    }

    public void register() {
        hook(
                "Harbor-Quest",
                "wiki.creeper.harbor.quest.api.event.QuestCompletedEvent",
                (event, type) -> {
                    UUID player = (UUID) call(event, "player");
                    String questType = String.valueOf(call(event, "type"));
                    switch (questType) {
                        case "daily" -> passes.addExp(player, "quest_daily", 1);
                        case "weekly" -> passes.addExp(player, "quest_weekly", 1);
                        default -> {}
                    }
                });
        hook(
                "Harbor-World",
                "wiki.creeper.harbor.world.api.event.ChartProgressEvent",
                (event, type) -> {
                    Player player = (Player) call(event, "getPlayer");
                    String region = String.valueOf(call(event, "region"));
                    double percent = ((Number) call(event, "percent")).doubleValue();
                    String key = player.getUniqueId() + "|" + region;
                    int floor = (int) Math.floor(percent);
                    Integer before = chartFloor.put(key, floor);
                    // For a region seen for the first time, count only this one-step change (the
                    // event only fires on changes of 1%p or more)
                    int units = before == null ? 1 : floor - before;
                    if (units > 0) {
                        passes.addExp(player.getUniqueId(), "chart_percent", units);
                    }
                });
        hook(
                "Harbor-Boss",
                "wiki.creeper.harbor.boss.api.event.BossEncounterEndEvent",
                (event, type) -> {
                    if (!Boolean.TRUE.equals(call(event, "cleared"))
                            || Boolean.TRUE.equals(call(event, "story"))) {
                        return;
                    }
                    for (Object c : (List<?>) call(event, "contributions")) {
                        if (Boolean.TRUE.equals(call(c, "eligible"))) {
                            passes.addExp((UUID) call(c, "player"), "boss_clear", 1);
                        }
                    }
                });
    }

    private void hook(String pluginName, String eventClass, BiConsumer<Object, Class<?>> handler) {
        Plugin owner = Bukkit.getPluginManager().getPlugin(pluginName);
        if (owner == null || !owner.isEnabled()) {
            return;
        }
        Class<? extends Event> type;
        try {
            type =
                    Class.forName(eventClass, true, owner.getClass().getClassLoader())
                            .asSubclass(Event.class);
        } catch (ClassNotFoundException | ClassCastException e) {
            log.warn("{} 이벤트를 찾지 못해 패스 경험치 연결을 건너뜁니다: {}", pluginName, eventClass);
            return;
        }
        Bukkit.getPluginManager()
                .registerEvent(
                        type,
                        this,
                        EventPriority.MONITOR,
                        (listener, event) -> {
                            if (!type.isInstance(event)) {
                                return;
                            }
                            try {
                                handler.accept(event, type);
                            } catch (RuntimeException e) {
                                log.warn("패스 경험치 연결 오류 ({})", eventClass, e);
                            }
                        },
                        shop,
                        true);
        log.info("시즌 패스 경험치 연결: {}", eventClass);
    }

    private static Object call(Object target, String method) {
        try {
            Method m = target.getClass().getMethod(method);
            Object value = m.invoke(target);
            if (value == null) {
                throw new IllegalStateException(method + "가 null");
            }
            return value;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("접근자 호출 실패: " + method, e);
        }
    }
}
