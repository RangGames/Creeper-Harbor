package wiki.creeper.harbor.npc.reputation;

import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import wiki.creeper.harbor.common.content.type.Balance;
import wiki.creeper.harbor.common.model.SeasonId;
import wiki.creeper.harbor.core.api.HarborApi;
import wiki.creeper.harbor.core.api.HarborPlayer;
import wiki.creeper.harbor.core.api.PlayerComponent;
import wiki.creeper.harbor.core.api.StatKey;
import wiki.creeper.harbor.core.task.Tasks;
import wiki.creeper.harbor.npc.api.ReputationLogEntry;
import wiki.creeper.harbor.npc.api.ReputationResult;
import wiki.creeper.harbor.npc.api.ReputationService;
import wiki.creeper.harbor.npc.api.ReputationSource;
import wiki.creeper.harbor.npc.api.ReputationTier;
import wiki.creeper.harbor.npc.api.event.ReputationChangedEvent;
import wiki.creeper.harbor.npc.api.event.ReputationTierChangedEvent;
import wiki.creeper.harbor.npc.content.NpcBalance;
import wiki.creeper.harbor.npc.data.NpcData;
import wiki.creeper.harbor.npc.data.NpcRepository;
import wiki.creeper.harbor.npc.logic.ReputationMath;

/**
 * Harbor reputation (docs 20 5.4). The value is Core player_season.reputation
 * (HarborPlayer.setReputation → Core save cycle). The same refId is counted only once per season
 * via the npc_reputation_log unique key; the log entry is inserted first, then the value is applied
 * on the main thread. Amounts in DB round-trips are reserved per source so concurrent requests do
 * not exceed the daily cap.
 */
public final class ReputationManager implements ReputationService, Listener {

    /**
     * Economy line "harbor investment" (same key as the Jobs design). Applied only to CONTRIBUTION
     * sources.
     */
    public static final StatKey REPUTATION_GAIN = StatKey.of("reputation_gain");

    private final HarborApi api;
    private final Tasks tasks;
    private final PlayerComponent<NpcData> component;
    private final Logger log;

    /** Per-source amounts in DB round-trips (daily cap reservation). */
    private final Map<UUID, Map<ReputationSource, Integer>> pending = new HashMap<>();

    public ReputationManager(
            HarborApi api, Tasks tasks, PlayerComponent<NpcData> component, Logger log) {
        this.api = api;
        this.tasks = tasks;
        this.component = component;
        this.log = log;
    }

    private NpcBalance balance() {
        return new NpcBalance(api.content().snapshot().single(Balance.TYPE));
    }

    // ------------------------------------------------------------------ queries

    @Override
    public int value(UUID player) {
        return api.players().get(player).map(HarborPlayer::reputation).orElse(0);
    }

    @Override
    public int tier(UUID player) {
        return ReputationMath.tierFor(value(player), tiers());
    }

    @Override
    public int bestTier(UUID player) {
        return api.players()
                .get(player)
                .map(hp -> Math.max(hp.get(component).bestTier(), tier(player)))
                .orElse(1);
    }

    @Override
    public CompletableFuture<Integer> loadBestTier(UUID player) {
        if (api.players().get(player).isPresent()) {
            return CompletableFuture.completedFuture(bestTier(player));
        }
        return api.database().async(c -> NpcRepository.loadBest(c, player));
    }

    @Override
    public List<ReputationTier> tiers() {
        return balance().reputationTiers();
    }

    @Override
    public ReputationTier tierInfo(int tier) {
        List<ReputationTier> tiers = tiers();
        return tiers.stream()
                .filter(t -> t.tier() == tier)
                .findFirst()
                .orElse(tier <= tiers.getFirst().tier() ? tiers.getFirst() : tiers.getLast());
    }

    @Override
    public Optional<ReputationTier> nextTier(UUID player) {
        return ReputationMath.next(value(player), tiers());
    }

    @Override
    public int defaultValue(ReputationSource source) {
        return balance().sourceValue(source);
    }

    @Override
    public List<ReputationLogEntry> recent(UUID player) {
        return api.players().get(player).map(hp -> hp.get(component).recent()).orElse(List.of());
    }

    // ------------------------------------------------------------------ changes

    @Override
    public CompletableFuture<ReputationResult> add(
            UUID player, int delta, ReputationSource source, @Nullable String refId) {
        Optional<? extends HarborPlayer> found = api.players().get(player);
        if (found.isEmpty()) {
            return done(ReputationResult.Status.OFFLINE, 0, 0, 1);
        }
        HarborPlayer hp = found.get();
        int value = hp.reputation();
        int tier = ReputationMath.tierFor(value, tiers());
        if (delta == 0) {
            return done(ReputationResult.Status.IGNORED, 0, value, tier);
        }
        NpcData data = hp.get(component);
        if (refId != null && data.refs().contains(refId)) {
            return done(ReputationResult.Status.DUPLICATE, 0, value, tier);
        }
        NpcBalance balance = balance();
        int amount = delta;
        if (source.contribution()) {
            amount =
                    ReputationMath.scaled(
                            amount, api.modifiers().apply(player, REPUTATION_GAIN, 1.0));
        }
        LocalDate today = api.time().gameDay();
        boolean capped = false;
        if (source != ReputationSource.ADMIN) {
            int cap = balance.dailyCap(source);
            int used = data.repToday(source, today) + reserved(player, source);
            int allowed = ReputationMath.capped(amount, used, cap);
            capped = allowed < amount;
            amount = allowed;
        }
        if (amount == 0) {
            return done(ReputationResult.Status.CAPPED, 0, value, tier);
        }
        int finalAmount = amount;
        boolean wasCapped = capped;
        reserve(player, source, finalAmount);
        SeasonId season = hp.season();
        return api.database()
                .async(
                        c ->
                                NpcRepository.insertReputation(
                                        c, player, season, source, finalAmount, refId, today))
                .handleAsync(
                        (inserted, ex) -> {
                            reserve(player, source, -finalAmount);
                            if (ex != null) {
                                log.error(
                                        "평판 기록 실패 ({}, {} {})",
                                        player,
                                        source.key(),
                                        finalAmount,
                                        ex);
                                return new ReputationResult(
                                        ReputationResult.Status.IGNORED,
                                        0,
                                        value(player),
                                        tier(player));
                            }
                            if (!inserted) {
                                remember(player, refId);
                                return new ReputationResult(
                                        ReputationResult.Status.DUPLICATE,
                                        0,
                                        value(player),
                                        tier(player));
                            }
                            return applyNow(
                                    player, season, source, finalAmount, refId, today, wasCapped);
                        },
                        tasks.main());
    }

    private ReputationResult applyNow(
            UUID player,
            SeasonId season,
            ReputationSource source,
            int amount,
            @Nullable String refId,
            LocalDate today,
            boolean capped) {
        Optional<? extends HarborPlayer> found = api.players().get(player);
        if (found.isEmpty() || !found.get().season().equals(season)) {
            // The player left in the meantime: delete the log entry so it can be received again
            if (refId != null) {
                var _ =
                        api.database()
                                .asyncVoid(
                                        c ->
                                                NpcRepository.deleteReputation(
                                                        c, player, season, refId));
            }
            return new ReputationResult(ReputationResult.Status.OFFLINE, 0, 0, 1);
        }
        HarborPlayer hp = found.get();
        List<ReputationTier> tiers = tiers();
        int oldValue = hp.reputation();
        int newValue = ReputationMath.apply(oldValue, amount);
        int oldTier = ReputationMath.tierFor(oldValue, tiers);
        int newTier = ReputationMath.tierFor(newValue, tiers);
        hp.setReputation(newValue);
        NpcData data =
                hp.get(component)
                        .withReputation(
                                today,
                                new ReputationLogEntry(source, amount, refId, Instant.now()),
                                balance().recentSize());
        int previousBest = data.bestTier();
        data = data.withBestTier(newTier);
        hp.set(component, data);
        Bukkit.getPluginManager()
                .callEvent(new ReputationChangedEvent(player, oldValue, newValue, source, refId));
        Player online = Bukkit.getPlayer(player);
        if (online != null && amount > 0 && source != ReputationSource.ADMIN) {
            api.messages()
                    .send(
                            online,
                            "npc.reputation.gained",
                            Placeholder.unparsed("amount", String.valueOf(amount)));
        }
        if (newTier != oldTier) {
            if (data.bestTier() > previousBest) {
                int best = data.bestTier();
                var _ = api.database().asyncVoid(c -> NpcRepository.raiseBest(c, player, best));
            }
            Bukkit.getPluginManager()
                    .callEvent(
                            new ReputationTierChangedEvent(
                                    player, oldTier, newTier, data.bestTier()));
            if (online != null) {
                api.messages()
                        .send(
                                online,
                                newTier > oldTier
                                        ? "npc.reputation.tier_up"
                                        : "npc.reputation.tier_down",
                                Placeholder.unparsed("tier", tierInfo(newTier).name()));
            }
        }
        return new ReputationResult(
                capped ? ReputationResult.Status.CAPPED : ReputationResult.Status.APPLIED,
                amount,
                newValue,
                newTier);
    }

    private static CompletableFuture<ReputationResult> done(
            ReputationResult.Status status, int applied, int value, int tier) {
        return CompletableFuture.completedFuture(
                new ReputationResult(status, applied, value, tier));
    }

    private int reserved(UUID player, ReputationSource source) {
        Map<ReputationSource, Integer> map = pending.get(player);
        return map == null ? 0 : Math.max(0, map.getOrDefault(source, 0));
    }

    private void reserve(UUID player, ReputationSource source, int delta) {
        if (delta == 0) {
            return;
        }
        if (delta < 0 && !pending.containsKey(player)) {
            // already cleared on quit: this is a late rollback of an in-flight grant, so do not
            // create a
            // new negative reservation (if left over, the next session would undercount the daily
            // cap and
            // allow exceeding it)
            return;
        }
        Map<ReputationSource, Integer> map =
                pending.computeIfAbsent(player, k -> new EnumMap<>(ReputationSource.class));
        int next = map.getOrDefault(source, 0) + delta;
        if (next <= 0) {
            map.remove(source);
        } else {
            map.put(source, next);
        }
        if (map.isEmpty()) {
            pending.remove(player);
        }
    }

    private void remember(UUID player, @Nullable String refId) {
        if (refId == null) {
            return;
        }
        api.players()
                .get(player)
                .ifPresent(
                        hp -> {
                            NpcData d = hp.get(component);
                            if (!d.refs().contains(refId)) {
                                java.util.Set<String> refs =
                                        new java.util.LinkedHashSet<>(d.refs());
                                refs.add(refId);
                                hp.set(
                                        component,
                                        new NpcData(
                                                d.affinity(),
                                                d.repDay(),
                                                d.repToday(),
                                                d.recent(),
                                                refs,
                                                d.bestTier()));
                            }
                        });
    }

    /**
     * On join, fixes the best tier if it is lower than the current tier (guards against Common
     * column migration and data recovery).
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        api.players()
                .get(uuid)
                .ifPresent(
                        hp -> {
                            int current = ReputationMath.tierFor(hp.reputation(), tiers());
                            NpcData data = hp.get(component);
                            if (current > data.bestTier()) {
                                hp.set(component, data.withBestTier(current));
                                var _ =
                                        api.database()
                                                .asyncVoid(
                                                        c ->
                                                                NpcRepository.raiseBest(
                                                                        c, uuid, current));
                            }
                        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        pending.remove(event.getPlayer().getUniqueId());
    }
}
