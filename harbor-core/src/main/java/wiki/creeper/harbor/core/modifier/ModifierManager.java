package wiki.creeper.harbor.core.modifier;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.content.type.Balance;
import wiki.creeper.harbor.core.api.Modifier;
import wiki.creeper.harbor.core.api.ModifierService;
import wiki.creeper.harbor.core.api.StatKey;
import wiki.creeper.harbor.core.api.event.ContentReloadedEvent;

/**
 * ModifierService 구현. 플레이어별·스탯별로 결과를 캐시하고, 보정자가 바뀌거나 가장 이른 만료 시각이 지나면 다시 계산한다. 메인 스레드
 * 전용.
 */
public final class ModifierManager implements ModifierService, Listener {

    private record Cached(double base, Breakdown breakdown, long globalVersion, @Nullable Instant nextExpiry) {}

    private static final class PlayerMods {
        final List<Modifier> modifiers = new ArrayList<>();
        final Map<StatKey, Cached> cache = new HashMap<>();
    }

    private final Map<UUID, PlayerMods> players = new HashMap<>();
    private final List<Modifier> global = new ArrayList<>();
    private final Supplier<Balance> balance;
    private long globalVersion;

    public ModifierManager(Supplier<Balance> balance) {
        this.balance = balance;
    }

    @Override
    public void add(UUID player, Modifier modifier) {
        PlayerMods mods = players.computeIfAbsent(player, k -> new PlayerMods());
        mods.modifiers.add(modifier);
        mods.cache.remove(modifier.stat());
    }

    @Override
    public void removeSource(UUID player, String source) {
        PlayerMods mods = players.get(player);
        if (mods != null && mods.modifiers.removeIf(m -> m.source().equals(source))) {
            mods.cache.clear();
        }
    }

    @Override
    public void addGlobal(Modifier modifier) {
        global.add(modifier);
        globalVersion++;
    }

    @Override
    public void removeGlobalSource(String source) {
        if (global.removeIf(m -> m.source().equals(source))) {
            globalVersion++;
        }
    }

    @Override
    public double value(UUID player, StatKey stat) {
        return apply(player, stat, balance.get().number("stats." + stat.key() + ".base", 0));
    }

    @Override
    public double apply(UUID player, StatKey stat, double base) {
        return explain(player, stat, base).result();
    }

    @Override
    public Breakdown explain(UUID player, StatKey stat, double base) {
        Instant now = Instant.now();
        PlayerMods mods = players.computeIfAbsent(player, k -> new PlayerMods());
        Cached cached = mods.cache.get(stat);
        if (cached != null && cached.base() == base && cached.globalVersion() == globalVersion) {
            Instant expiry = cached.nextExpiry();
            if (expiry == null || now.isBefore(expiry)) {
                return cached.breakdown();
            }
        }
        mods.modifiers.removeIf(m -> m.isExpired(now));
        global.removeIf(m -> m.isExpired(now));

        List<Modifier> relevant = new ArrayList<>();
        @Nullable Instant nextExpiry = null;
        for (List<Modifier> source : List.of(global, mods.modifiers)) {
            for (Modifier m : source) {
                if (m.stat().equals(stat)) {
                    relevant.add(m);
                    Instant expires = m.expiresAt();
                    if (expires != null && (nextExpiry == null || expires.isBefore(nextExpiry))) {
                        nextExpiry = expires;
                    }
                }
            }
        }
        Balance b = balance.get();
        double min = b.number("stats." + stat.key() + ".min", Double.NEGATIVE_INFINITY);
        double max = b.number("stats." + stat.key() + ".max", Double.POSITIVE_INFINITY);
        Breakdown breakdown = ModifierStack.compute(base, relevant, min, max, now);
        mods.cache.put(stat, new Cached(base, breakdown, globalVersion, nextExpiry));
        return breakdown;
    }

    /** 상한·하한이 balance.yml에 있으므로 리로드되면 캐시를 비운다. */
    @EventHandler
    public void onReload(ContentReloadedEvent event) {
        players.values().forEach(m -> m.cache.clear());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        players.remove(event.getPlayer().getUniqueId());
    }
}
