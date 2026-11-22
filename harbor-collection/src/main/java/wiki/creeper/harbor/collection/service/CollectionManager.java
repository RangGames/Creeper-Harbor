package wiki.creeper.harbor.collection.service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import wiki.creeper.harbor.collection.api.CodexEntry;
import wiki.creeper.harbor.collection.api.CodexScope;
import wiki.creeper.harbor.collection.api.CodexService;
import wiki.creeper.harbor.collection.api.HintService;
import wiki.creeper.harbor.collection.api.RecordEntry;
import wiki.creeper.harbor.collection.api.RecordService;
import wiki.creeper.harbor.collection.api.event.CodexMilestoneEvent;
import wiki.creeper.harbor.collection.api.event.CodexRegisteredEvent;
import wiki.creeper.harbor.collection.api.event.RecordBrokenEvent;
import wiki.creeper.harbor.collection.content.CodexRules;
import wiki.creeper.harbor.collection.content.CodexTitles;
import wiki.creeper.harbor.collection.content.StatBonus;
import wiki.creeper.harbor.collection.data.CodexData;
import wiki.creeper.harbor.collection.data.CollectionRepository;
import wiki.creeper.harbor.common.content.ContentSnapshot;
import wiki.creeper.harbor.common.content.type.FishDef;
import wiki.creeper.harbor.common.content.type.RegionDef;
import wiki.creeper.harbor.common.id.FishId;
import wiki.creeper.harbor.common.id.RegionId;
import wiki.creeper.harbor.common.ledger.LedgerReason;
import wiki.creeper.harbor.common.model.Currency;
import wiki.creeper.harbor.common.model.Grade;
import wiki.creeper.harbor.common.model.JobTier;
import wiki.creeper.harbor.common.model.SeasonId;
import wiki.creeper.harbor.common.redis.Channel;
import wiki.creeper.harbor.core.api.HarborApi;
import wiki.creeper.harbor.core.api.HarborPlayer;
import wiki.creeper.harbor.core.api.HarborSounds;
import wiki.creeper.harbor.core.api.Modifier;
import wiki.creeper.harbor.core.api.PlayerComponent;
import wiki.creeper.harbor.core.api.StatKey;
import wiki.creeper.harbor.core.api.event.ContentReloadedEvent;
import wiki.creeper.harbor.core.task.Tasks;
import wiki.creeper.harbor.fishing.api.CodexProvider;
import wiki.creeper.harbor.fishing.api.event.FishCaughtEvent;
import wiki.creeper.harbor.season.api.event.WeeklyResetEvent;

/**
 * Codex, hints and biggest-fish records (docs 20 4.4). Catch handling uses only main-thread memory;
 * codex and hints go through the write-behind queue, records are upserted immediately on a virtual
 * thread ({@link RecordBoard}).
 */
public final class CollectionManager
        implements CodexService, HintService, RecordService, CodexProvider, Listener {

    /**
     * Codex cache. Writes are queued separately on the write-behind queue for every catch, so the
     * component save function does nothing.
     */
    public static final PlayerComponent<CodexData> COMPONENT =
            new PlayerComponent<>(
                    "codex", CollectionRepository::load, (c, uuid, season, data) -> {});

    /** Refreshes other servers' record caches (harbor:collection, proposed). */
    public record RecordUpdated(
            String fish, String league, UUID player, String name, double size, Instant caughtAt) {}

    static final Channel<RecordUpdated> RECORDS =
            new Channel<>("harbor:collection", RecordUpdated.class);

    private static final String BUFF_SOURCE = "codex_season";
    private static final StatKey HINT_STAT = StatKey.of("feature_condition_hint");

    /** One server record of this week (billboard 'new records this week' page). */
    public record WeeklyRecord(FishId fish, RecordEntry entry) {}

    private final HarborApi api;
    private final Tasks tasks;
    private final Logger log;
    private final SeasonLink seasonLink;
    private final RecordBoard records;
    private Index index;

    /** Region denominators and species index (rebuilt on every reload). */
    record Index(Map<RegionId, Set<FishId>> regionFish, Set<FishId> all, Set<FishId> seasonal) {
        static Index of(ContentSnapshot snapshot, SeasonId season) {
            Map<RegionId, Set<FishId>> regions = new HashMap<>();
            Set<FishId> all = new HashSet<>();
            Set<FishId> seasonal = new HashSet<>();
            for (FishDef fish : snapshot.all(FishDef.TYPE).values()) {
                if (fish.season() > season.value()) {
                    continue;
                }
                all.add(fish.id());
                seasonal.add(fish.id());
                if (fish.grade() != Grade.MYTHIC) {
                    regions.computeIfAbsent(fish.region(), r -> new HashSet<>()).add(fish.id());
                }
            }
            return new Index(Map.copyOf(regions), Set.copyOf(all), Set.copyOf(seasonal));
        }
    }

    public CollectionManager(HarborApi api, Tasks tasks, Logger log, SeasonLink seasonLink) {
        this.api = api;
        this.tasks = tasks;
        this.log = log;
        this.seasonLink = seasonLink;
        this.records = new RecordBoard(api, tasks, log, seasonLink);
        this.index = Index.of(api.content().snapshot(), api.infra().season());
    }

    public void start() {
        records.start();
    }

    SeasonLink seasonLink() {
        return seasonLink;
    }

    CodexRules rules() {
        return api.content().snapshot().single(CodexRules.TYPE);
    }

    CodexData data(UUID uuid) {
        return api.players().get(uuid).map(p -> p.get(COMPONENT)).orElse(CodexData.EMPTY);
    }

    // ------------------------------------------------------------------ CodexProvider ·
    // CodexService

    /** Fishing's first-catch multiplier and codex hunter weight: based on the season codex. */
    @Override
    public boolean hasCaught(UUID player, FishId fish) {
        return data(player).season().containsKey(fish);
    }

    @Override
    public Optional<CodexEntry> entry(UUID player, FishId fish, CodexScope scope) {
        CodexData data = data(player);
        return Optional.ofNullable(
                (scope == CodexScope.TOTAL ? data.total() : data.season()).get(fish));
    }

    @Override
    public boolean isRegistered(UUID player, FishId fish, CodexScope scope) {
        return entry(player, fish, scope).isPresent();
    }

    @Override
    public double percent(UUID player, CodexScope scope, @Nullable RegionId region) {
        Map<FishId, CodexEntry> map =
                scope == CodexScope.TOTAL ? data(player).total() : data(player).season();
        Set<FishId> denominator =
                region == null
                        ? (scope == CodexScope.TOTAL ? index.all() : index.seasonal())
                        : index.regionFish().getOrDefault(region, Set.of());
        if (denominator.isEmpty()) {
            return 0;
        }
        long registered = denominator.stream().filter(map::containsKey).count();
        return 100.0 * registered / denominator.size();
    }

    @Override
    public void open(Player viewer, UUID owner, int page) {
        new CodexMenu(api, this).open(viewer, owner, page);
    }

    // ------------------------------------------------------------------ catch handling

    @EventHandler(priority = EventPriority.MONITOR)
    public void onCaught(FishCaughtEvent event) {
        Player player = event.getPlayer();
        HarborPlayer hp = api.players().get(player);
        FishDef fish = event.fish();
        double size = event.item().sizeCm();
        int quality = event.item().quality().stars();
        if (size < fish.sizeMin() - 0.05 || size > fish.sizeMax() + 0.05) {
            log.warn("크기가 범위를 벗어난 포획은 기록하지 않습니다: {} {} {}cm", player.getName(), fish.id(), size);
            return;
        }
        record(hp, fish, size, quality, event.item().caughtAt(), !event.released());
    }

    /**
     * Applies a catch to the codex and records.
     *
     * @param countsForRecord only direct catches count for records (traps and releases excluded)
     */
    void record(
            HarborPlayer hp,
            FishDef fish,
            double size,
            int quality,
            Instant at,
            boolean countsForRecord) {
        UUID uuid = hp.uuid();
        SeasonId season = hp.season();
        CodexData data = hp.get(COMPONENT);
        CodexEntry oldTotal = data.total().get(fish.id());
        CodexEntry oldSeason = data.season().get(fish.id());
        CodexEntry newTotal =
                oldTotal == null
                        ? CodexEntry.first(at, size, quality)
                        : oldTotal.merge(size, quality);
        CodexEntry newSeason =
                oldSeason == null
                        ? CodexEntry.first(at, size, quality)
                        : oldSeason.merge(size, quality);
        double percentBefore = percent(uuid, CodexScope.TOTAL, null);
        hp.set(COMPONENT, data.withCatch(fish.id(), newTotal, newSeason));
        api.writeBehind()
                .enqueue(
                        "player:" + uuid + ":collection:t:" + fish.id(),
                        c -> CollectionRepository.upsertCodex(c, uuid, null, fish.id(), newTotal));
        api.writeBehind()
                .enqueue(
                        "player:" + uuid + ":collection:s:" + fish.id(),
                        c ->
                                CollectionRepository.upsertCodex(
                                        c, uuid, season, fish.id(), newSeason));

        Player player = Bukkit.getPlayer(uuid);
        if (oldTotal == null) {
            Bukkit.getPluginManager()
                    .callEvent(
                            new CodexRegisteredEvent(
                                    uuid, fish.id(), CodexScope.TOTAL, fish.grade()));
        }
        if (oldSeason == null) {
            onFirstSeasonCatch(hp, player, fish);
        }
        if (oldTotal != null && size > oldTotal.maxSize() && fish.grade().isAtLeast(Grade.RARE)) {
            if (player != null) {
                api.ui()
                        .title(
                                player,
                                api.messages().get("collection.new_record.title"),
                                api.messages()
                                        .get(
                                                "collection.new_record.subtitle",
                                                Placeholder.unparsed("size", fmt(size))),
                                Duration.ofMillis(200),
                                Duration.ofSeconds(2),
                                Duration.ofMillis(500));
                HarborSounds.play(player, "harbor:catch.new_record"); // '15. Sound' 4.2 new record
            }
            Bukkit.getPluginManager()
                    .callEvent(
                            new RecordBrokenEvent(
                                    uuid,
                                    fish.id(),
                                    "all",
                                    RecordBrokenEvent.Scope.PERSONAL,
                                    size,
                                    oldTotal.maxSize()));
        }
        if (countsForRecord) {
            records.serverRecord(hp, fish, size, at);
        }
        totalMilestones(uuid, percentBefore, percent(uuid, CodexScope.TOTAL, null));
    }

    private void onFirstSeasonCatch(HarborPlayer hp, @Nullable Player player, FishDef fish) {
        UUID uuid = hp.uuid();
        CodexRules rules = rules();
        if (player != null) {
            // Custom advancement toasts come after the datapack is ready; notify with title and
            // sound for now
            api.ui()
                    .title(
                            player,
                            api.messages().get("collection.registered.title"),
                            api.messages()
                                    .get(
                                            "item.name." + fish.grade().key(),
                                            Placeholder.unparsed("name", fish.name())),
                            Duration.ofMillis(200),
                            Duration.ofSeconds(2),
                            Duration.ofMillis(500));
            HarborSounds.play(
                    player, "harbor:ui.codex_register"); // '15. Sound' 4.5 codex registration
        }
        Bukkit.getPluginManager()
                .callEvent(
                        new CodexRegisteredEvent(uuid, fish.id(), CodexScope.SEASON, fish.grade()));
        if (fish.grade().isAtLeast(rules.firstCatchMinGrade()) && rules.firstCatchMulbineul() > 0) {
            String ref = "codex1:" + hp.season().value() + ":" + uuid + ":" + fish.id().id().name();
            var _ =
                    api.currency()
                            .give(
                                    uuid,
                                    Currency.MULBINEUL,
                                    rules.firstCatchMulbineul(),
                                    LedgerReason.CODEX_FIRST_CATCH,
                                    ref);
        }
        // Region completion (season codex, mythic excluded)
        Set<FishId> regionFish = index.regionFish().getOrDefault(fish.region(), Set.of());
        if (!regionFish.isEmpty()
                && regionFish.stream().allMatch(hp.get(COMPONENT).season()::containsKey)) {
            String region = fish.region().id().name();
            // Titles are unique per (uuid, title), so completing again next season only grants
            // water scales again
            var _ =
                    seasonLink.grant(
                            uuid,
                            CodexTitles.regionTitle(rules.regionTitlePrefix(), fish.region()));
            if (rules.regionCompleteMulbineul() > 0) {
                String ref = "codexr:" + hp.season().value() + ":" + region + ":" + uuid;
                var _ =
                        api.currency()
                                .give(
                                        uuid,
                                        Currency.MULBINEUL,
                                        rules.regionCompleteMulbineul(),
                                        LedgerReason.CODEX_REWARD,
                                        ref);
            }
            if (player != null) {
                api.messages()
                        .send(
                                player,
                                "collection.region_complete",
                                Placeholder.unparsed("region", regionName(fish.region())));
            }
            Bukkit.getPluginManager()
                    .callEvent(
                            new CodexMilestoneEvent(
                                    uuid,
                                    CodexMilestoneEvent.Kind.REGION_COMPLETE,
                                    region,
                                    100,
                                    rules.regionTitlePrefix() + "/" + region));
        }
        applySeasonBuffs(uuid);
    }

    private void totalMilestones(UUID uuid, double before, double after) {
        for (CodexRules.Milestone m : rules().totalMilestones()) {
            if (before < m.percent() && after >= m.percent()) {
                Bukkit.getPluginManager()
                        .callEvent(
                                new CodexMilestoneEvent(
                                        uuid,
                                        CodexMilestoneEvent.Kind.TOTAL_PERCENT,
                                        "total_" + m.percent(),
                                        m.percent(),
                                        m.title()));
                if (!m.title().isEmpty()) {
                    var _ = seasonLink.grant(uuid, m.title());
                }
                Player player = Bukkit.getPlayer(uuid);
                if (player != null) {
                    api.messages()
                            .send(
                                    player,
                                    "collection.milestone",
                                    Placeholder.unparsed("percent", String.valueOf(m.percent())));
                }
            }
        }
    }

    /**
     * Season codex completion buffs (source codex_season): recomputed and registered by
     * replacement.
     */
    void applySeasonBuffs(UUID uuid) {
        api.modifiers().removeSource(uuid, BUFF_SOURCE);
        double percent = percent(uuid, CodexScope.SEASON, null);
        for (CodexRules.SeasonBuff buff : rules().seasonBuffs()) {
            if (percent >= buff.percent()) {
                for (StatBonus bonus : buff.modifiers()) {
                    api.modifiers()
                            .add(uuid, Modifier.add(bonus.stat(), bonus.value(), BUFF_SOURCE));
                }
            }
        }
    }

    // ------------------------------------------------------------------ biggest-fish records

    /** Leagues: 1st tier -> tier1 and all, 2nd tier -> tier2 and all, 3rd tier -> all. */
    static List<String> leagues(JobTier tier) {
        return switch (tier) {
            case FIRST -> List.of("tier1", "all");
            case SECOND -> List.of("tier2", "all");
            case THIRD -> List.of("all");
        };
    }

    /**
     * This week's server records (newest first, only those this server received; empty after a
     * restart).
     */
    public List<WeeklyRecord> weeklyRecords() {
        return records.weeklyRecords();
    }

    /** Region species (mythic excluded, billboard region page). */
    public Set<FishId> regionFish(RegionId region) {
        return index.regionFish().getOrDefault(region, Set.of());
    }

    @Override
    public Optional<Double> personalBest(UUID player, FishId fish) {
        return entry(player, fish, CodexScope.TOTAL).map(CodexEntry::maxSize);
    }

    @Override
    public List<RecordEntry> serverTop(FishId fish, String league, int limit) {
        return records.serverTop(fish, league, limit);
    }

    /** Admin: deletes a fraudulent record and removes it from the cache. */
    public void removeRecord(FishId fish, String league, UUID player) {
        records.removeRecord(fish, league, player);
    }

    // ------------------------------------------------------------------ hints

    @Override
    public int level(UUID player, FishId fish) {
        int stored = data(player).hints().getOrDefault(fish, 0);
        int fromSkill = (int) Math.floor(api.modifiers().value(player, HINT_STAT));
        return Math.min(MAX_LEVEL, Math.max(stored, fromSkill));
    }

    @Override
    public boolean grant(UUID player, FishId fish, int level, String source) {
        Optional<HarborPlayer> hp = api.players().get(player);
        int target = Math.clamp(level, 0, MAX_LEVEL);
        if (hp.isEmpty() || hp.get().get(COMPONENT).hints().getOrDefault(fish, 0) >= target) {
            return false;
        }
        hp.get().set(COMPONENT, hp.get().get(COMPONENT).withHint(fish, target));
        api.writeBehind()
                .enqueue(
                        "player:" + player + ":collection:h:" + fish,
                        c -> CollectionRepository.upsertHint(c, player, fish, target, source));
        return true;
    }

    @Override
    public Optional<FishId> grantRandom(UUID player, @Nullable RegionId region, String source) {
        CodexData data = data(player);
        ContentSnapshot snapshot = api.content().snapshot();
        // Region order: the requested region, then regions by recommended level, highest first
        List<RegionDef> regions = new ArrayList<>(snapshot.all(RegionDef.TYPE).values());
        regions.sort(Comparator.comparingInt(RegionDef::recLevel).reversed());
        if (region != null) {
            snapshot.get(RegionDef.TYPE, region.id())
                    .ifPresent(
                            r -> {
                                regions.remove(r);
                                regions.addFirst(r);
                            });
        }
        for (RegionDef r : regions) {
            Optional<FishDef> pick =
                    snapshot.all(FishDef.TYPE).values().stream()
                            .filter(
                                    f ->
                                            f.region().equals(r.id())
                                                    && !data.season().containsKey(f.id())
                                                    && level(player, f.id()) < MAX_LEVEL)
                            .min(
                                    Comparator.comparingInt((FishDef f) -> level(player, f.id()))
                                            .thenComparing(FishDef::grade));
            if (pick.isPresent()
                    && grant(player, pick.get().id(), level(player, pick.get().id()) + 1, source)) {
                return Optional.of(pick.get().id());
            }
        }
        return Optional.empty();
    }

    // ------------------------------------------------------------------ admin

    public void adminSet(UUID player, FishDef fish, double size, int quality) {
        api.players()
                .get(player)
                .ifPresent(hp -> record(hp, fish, size, quality, Instant.now(), false));
    }

    public void adminRemove(UUID player, FishId fish) {
        api.players()
                .get(player)
                .ifPresent(
                        hp -> {
                            hp.set(COMPONENT, hp.get(COMPONENT).without(fish));
                            SeasonId season = hp.season();
                            var _ =
                                    api.database()
                                            .asyncVoid(
                                                    c ->
                                                            CollectionRepository.deleteCodex(
                                                                    c, player, season, fish));
                            applySeasonBuffs(player);
                        });
    }

    // ------------------------------------------------------------------ events

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        applySeasonBuffs(uuid);
        // Season titles: retry failed grants and grant once what was earned before Season existed
        // (after the flag cache is loaded)
        var _ =
                tasks.later(
                        40,
                        () -> {
                            if (Bukkit.getPlayer(uuid) == null) {
                                return;
                            }
                            seasonLink.retry(uuid);
                            seasonLink.backfill(
                                    uuid,
                                    CodexTitles.earned(
                                            rules(),
                                            percent(uuid, CodexScope.TOTAL, null),
                                            index.regionFish(),
                                            data(uuid).season().keySet()));
                        });
    }

    @EventHandler
    public void onWeeklyReset(WeeklyResetEvent event) {
        records.clearWeekly();
    }

    @EventHandler
    public void onReload(ContentReloadedEvent event) {
        index = Index.of(event.snapshot(), api.infra().season());
        api.players().online().forEach(p -> applySeasonBuffs(p.uuid()));
    }

    String regionName(RegionId region) {
        return api.content()
                .snapshot()
                .get(RegionDef.TYPE, region.id())
                .map(RegionDef::name)
                .orElse(region.id().name());
    }

    public static String fmt(double size) {
        return String.format(Locale.ROOT, "%.1f", size);
    }
}
