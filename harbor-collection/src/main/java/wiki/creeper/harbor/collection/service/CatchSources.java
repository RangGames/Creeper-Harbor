package wiki.creeper.harbor.collection.service;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.bukkit.Bukkit;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.server.ServerLoadEvent;
import org.bukkit.plugin.Plugin;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import wiki.creeper.harbor.common.content.type.FishDef;
import wiki.creeper.harbor.common.id.FishId;
import wiki.creeper.harbor.core.api.HarborApi;
import wiki.creeper.harbor.core.api.HarborItem;
import wiki.creeper.harbor.core.api.HarborPlayer;
import wiki.creeper.harbor.fishing.api.event.TrapHarvestEvent;

/**
 * Catch sources other than the rod (docs 20 4.4 "per-source handling"):
 *
 * <ul>
 *   <li>Trap harvests ({@link TrapHarvestEvent}): collection only, never records ('1. Design':
 *       rankings and biggest fish only count fish caught directly)
 *   <li>release_only boss fish (Lord of the Trench, Verdigris Thunderfish): Harbor-Boss {@code
 *       BossEncounterEndEvent} with result CLEARED registers the linked fish in the collection of
 *       every participant. There is no item, so the size is rolled inside the species range and the
 *       quality is 1 (proposed). Story fights (no loot or records) are skipped. Boss sits above
 *       Collection (Boss → Shop? → Collection), so the event is subscribed by class name after
 *       server start, the same way Shop's OptionalPassSources does
 * </ul>
 */
public final class CatchSources implements Listener {

    static final String BOSS_PLUGIN = "Harbor-Boss";
    static final String BOSS_END = "wiki.creeper.harbor.boss.api.event.BossEncounterEndEvent";

    private final Plugin plugin;
    private final HarborApi api;
    private final Logger log;
    private final CollectionManager collection;
    private boolean bossHooked;

    public CatchSources(Plugin plugin, HarborApi api, Logger log, CollectionManager collection) {
        this.plugin = plugin;
        this.api = api;
        this.log = log;
        this.collection = collection;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onTrap(TrapHarvestEvent event) {
        Optional<HarborPlayer> hp = api.players().get(event.getPlayer().getUniqueId());
        if (hp.isEmpty()) {
            return;
        }
        for (HarborItem.Fish item : event.fish()) {
            Optional<FishDef> def = api.content().snapshot().get(FishDef.TYPE, item.species().id());
            if (def.isEmpty()) {
                continue;
            }
            FishDef fish = def.get();
            double size = item.sizeCm();
            if (size < fish.sizeMin() - 0.05 || size > fish.sizeMax() + 0.05) {
                log.warn(
                        "크기가 범위를 벗어난 통발 수확은 기록하지 않습니다: {} {} {}cm",
                        event.getPlayer().getName(),
                        fish.id(),
                        size);
                continue;
            }
            collection.record(hp.get(), fish, size, item.quality().stars(), item.caughtAt(), false);
        }
    }

    // ------------------------------------------------------------------ Harbor-Boss (optional)

    /** Boss may enable after Collection, so the link is made once every plugin is enabled. */
    @EventHandler
    public void onServerLoad(ServerLoadEvent event) {
        if (bossHooked) {
            return;
        }
        Plugin boss = Bukkit.getPluginManager().getPlugin(BOSS_PLUGIN);
        if (boss == null || !boss.isEnabled()) {
            return;
        }
        Class<? extends Event> type;
        try {
            type =
                    Class.forName(BOSS_END, true, boss.getClass().getClassLoader())
                            .asSubclass(Event.class);
        } catch (ClassNotFoundException | ClassCastException e) {
            log.warn("보스 종료 이벤트를 찾지 못해 보스 어종 도감 등록을 끕니다: {}", BOSS_END);
            return;
        }
        bossHooked = true;
        Bukkit.getPluginManager()
                .registerEvent(
                        type,
                        this,
                        EventPriority.MONITOR,
                        (listener, e) -> {
                            if (!type.isInstance(e)) {
                                return;
                            }
                            try {
                                onBossEnd(e);
                            } catch (RuntimeException ex) {
                                log.warn("보스 어종 도감 등록 오류", ex);
                            }
                        },
                        plugin,
                        true);
        log.info("보스 어종 도감 등록 연결: {}", BOSS_END);
    }

    private void onBossEnd(Event event) {
        if (!Boolean.TRUE.equals(call(event, "cleared"))
                || Boolean.TRUE.equals(call(event, "story"))
                || !Boolean.TRUE.equals(call(event, "releaseOnly"))
                || !(call(event, "fish") instanceof Optional<?> fishOpt)
                || !(fishOpt.orElse(null) instanceof FishId fishId)) {
            return;
        }
        Optional<FishDef> def = api.content().snapshot().get(FishDef.TYPE, fishId.id());
        if (def.isEmpty() || !(call(event, "participants") instanceof List<?> members)) {
            return;
        }
        FishDef fish = def.get();
        Instant now = Instant.now();
        for (Object member : members) {
            if (!(member instanceof UUID uuid)) {
                continue;
            }
            // Only players loaded on this server (the encounter runs where they are)
            Optional<HarborPlayer> hp = api.players().get(uuid);
            if (hp.isEmpty()) {
                continue;
            }
            double size =
                    fish.sizeMax() > fish.sizeMin()
                            ? ThreadLocalRandom.current().nextDouble(fish.sizeMin(), fish.sizeMax())
                            : fish.sizeMin();
            collection.record(hp.get(), fish, Math.round(size * 10) / 10.0, 1, now, false);
        }
    }

    private static @Nullable Object call(Object target, String method) {
        try {
            Method m = target.getClass().getMethod(method);
            return m.invoke(target);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }
}
