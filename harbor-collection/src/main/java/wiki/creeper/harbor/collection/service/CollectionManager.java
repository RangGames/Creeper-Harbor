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
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
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
import wiki.creeper.harbor.common.redis.Channels;
import wiki.creeper.harbor.core.api.HarborApi;
import wiki.creeper.harbor.core.api.HarborPlayer;
import wiki.creeper.harbor.core.api.Modifier;
import wiki.creeper.harbor.core.api.PlayerComponent;
import wiki.creeper.harbor.core.api.StatKey;
import wiki.creeper.harbor.core.api.event.ContentReloadedEvent;
import wiki.creeper.harbor.core.task.Tasks;
import wiki.creeper.harbor.fishing.api.CodexProvider;
import wiki.creeper.harbor.fishing.api.event.FishCaughtEvent;
import wiki.creeper.harbor.season.api.event.WeeklyResetEvent;

/** 도감·힌트·최대어 기록 (docs 20 4.4). 포획 처리는 메인 스레드 메모리만 쓰고, 도감·힌트는 지연 쓰기 큐, 기록은 가상 스레드 즉시 업서트. */
public final class CollectionManager
        implements CodexService, HintService, RecordService, CodexProvider, Listener {

    /** 도감 캐시. 쓰기는 포획마다 지연 쓰기 큐로 따로 넣으므로 컴포넌트 저장 함수는 아무것도 하지 않는다. */
    public static final PlayerComponent<CodexData> COMPONENT =
            new PlayerComponent<>(
                    "codex", CollectionRepository::load, (c, uuid, season, data) -> {});

    /** 다른 서버의 기록 캐시 갱신 (harbor:collection, 추가 제안). */
    public record RecordUpdated(
            String fish, String league, UUID player, String name, double size, Instant caughtAt) {}

    static final Channel<RecordUpdated> RECORDS =
            new Channel<>("harbor:collection", RecordUpdated.class);

    private static final int TOP = 10;
    private static final Duration ANNOUNCE_THROTTLE = Duration.ofSeconds(10);
    private static final String BUFF_SOURCE = "codex_season";
    private static final StatKey HINT_STAT = StatKey.of("feature_condition_hint");

    /** 이번 주 서버 신기록 한 줄 (전광판 '이번 주 신기록' 페이지). */
    public record WeeklyRecord(FishId fish, RecordEntry entry) {}

    private static final int WEEKLY_MAX = 30;

    private final HarborApi api;
    private final Tasks tasks;
    private final Logger log;
    private final SeasonLink seasonLink;
    private final Map<String, List<RecordEntry>> top = new ConcurrentHashMap<>();
    private final List<WeeklyRecord> weekly = new ArrayList<>();
    private final Map<FishId, Instant> lastAnnounce = new HashMap<>();
    private Index index;

    /** 지역 분모·어종 색인 (리로드마다 다시 만든다). */
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
        this.index = Index.of(api.content().snapshot(), api.infra().season());
    }

    public void start() {
        SeasonId season = api.infra().season();
        var _ =
                api.database()
                        .async(c -> CollectionRepository.topRecords(c, season, TOP))
                        .thenAcceptAsync(
                                rows -> {
                                    rows.forEach(
                                            r ->
                                                    put(
                                                            r.fish().toString(),
                                                            r.league(),
                                                            new RecordEntry(
                                                                    r.player(),
                                                                    r.name(),
                                                                    r.size(),
                                                                    r.caughtAt())));
                                    log.info("최대어 기록 캐시 {}행", rows.size());
                                },
                                tasks.main());
        api.bus()
                .subscribe(
                        RECORDS,
                        envelope -> {
                            if (!envelope.server().equals(api.infra().serverId())) {
                                RecordUpdated u = envelope.payload();
                                tasks.main()
                                        .execute(
                                                () -> {
                                                    RecordEntry entry =
                                                            new RecordEntry(
                                                                    u.player(),
                                                                    u.name(),
                                                                    u.size(),
                                                                    u.caughtAt());
                                                    if (put(u.fish(), u.league(), entry)
                                                            && u.league().equals("all")) {
                                                        weekly(u.fish(), entry);
                                                    }
                                                });
                            }
                        });
    }

    CodexRules rules() {
        return api.content().snapshot().single(CodexRules.TYPE);
    }

    CodexData data(UUID uuid) {
        return api.players().get(uuid).map(p -> p.get(COMPONENT)).orElse(CodexData.EMPTY);
    }

    // ------------------------------------------------------------------ CodexProvider ·
    // CodexService

    /** Fishing의 첫 포획 배율·도감 사냥꾼 가중치: 시즌 도감 기준. */
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

    // ------------------------------------------------------------------ 포획 처리

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
     * 도감·기록 반영.
     *
     * @param countsForRecord 직접 낚은 것만 기록 (통발·방류 제외)
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
                        "codex:" + uuid + ":t:" + fish.id(),
                        c -> CollectionRepository.upsertCodex(c, uuid, null, fish.id(), newTotal));
        api.writeBehind()
                .enqueue(
                        "codex:" + uuid + ":s:" + fish.id(),
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
            serverRecord(hp, fish, size, at);
        }
        totalMilestones(uuid, percentBefore, percent(uuid, CodexScope.TOTAL, null));
    }

    private void onFirstSeasonCatch(HarborPlayer hp, @Nullable Player player, FishDef fish) {
        UUID uuid = hp.uuid();
        CodexRules rules = rules();
        if (player != null) {
            // 커스텀 발전과제 토스트는 데이터팩 준비 후. 지금은 타이틀·사운드로 알린다
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
            player.playSound(player, Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.6f, 1.2f);
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
        // 지역 완성 (시즌 도감, 신화 제외)
        Set<FishId> regionFish = index.regionFish().getOrDefault(fish.region(), Set.of());
        if (!regionFish.isEmpty()
                && regionFish.stream().allMatch(hp.get(COMPONENT).season()::containsKey)) {
            String region = fish.region().id().name();
            // 칭호는 (uuid, 칭호) 유니크라 다음 시즌에 다시 완성해도 물비늘만 다시 받는다
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

    /** 시즌 도감 달성률 버프 (출처 codex_season): 다시 계산해 교체 등록. */
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

    // ------------------------------------------------------------------ 최대어 기록

    /** 리그: 1차 → tier1·all, 2차 → tier2·all, 3차 → all. */
    static List<String> leagues(JobTier tier) {
        return switch (tier) {
            case FIRST -> List.of("tier1", "all");
            case SECOND -> List.of("tier2", "all");
            case THIRD -> List.of("all");
        };
    }

    private void serverRecord(HarborPlayer hp, FishDef fish, double size, Instant at) {
        UUID uuid = hp.uuid();
        SeasonId season = hp.season();
        String name = hp.name();
        for (String league : leagues(hp.jobTier())) {
            Optional<RecordEntry> mine = mine(fish.id().toString(), league, uuid);
            if (mine.isPresent() && mine.get().size() >= size) {
                continue;
            }
            var _ =
                    api.database()
                            .asyncVoid(
                                    c ->
                                            CollectionRepository.upsertRecord(
                                                    c, fish.id(), season, league, uuid, size, at))
                            .thenRunAsync(
                                    () ->
                                            afterRecord(
                                                    fish,
                                                    league,
                                                    new RecordEntry(uuid, name, size, at)),
                                    tasks.main())
                            .exceptionally(
                                    e -> {
                                        log.error("기록 저장 실패: {} {} {}", fish.id(), league, uuid, e);
                                        return null;
                                    });
        }
    }

    private void afterRecord(FishDef fish, String league, RecordEntry entry) {
        String key = fish.id().toString();
        List<RecordEntry> before = top.getOrDefault(key + "|" + league, List.of());
        RecordEntry previousLeader = before.isEmpty() ? null : before.getFirst();
        var _ = put(key, league, entry);
        api.bus()
                .publish(
                        RECORDS,
                        new RecordUpdated(
                                key,
                                league,
                                entry.player(),
                                entry.name(),
                                entry.size(),
                                entry.caughtAt()));
        List<RecordEntry> after = top.getOrDefault(key + "|" + league, List.of());
        boolean newLeader =
                !after.isEmpty()
                        && after.getFirst().player().equals(entry.player())
                        && (previousLeader == null || entry.size() > previousLeader.size());
        if (!newLeader) {
            return;
        }
        double previous = previousLeader == null ? 0 : previousLeader.size();
        if (league.equals("all")) {
            weekly(key, entry);
            // 명예의 전당 잠정 기록 (Season이 시즌 마감 때 확정)
            seasonLink.submitBiggestFish(
                    fish.id().id(), entry.player(), entry.name(), entry.size(), entry.caughtAt());
        }
        Bukkit.getPluginManager()
                .callEvent(
                        new RecordBrokenEvent(
                                entry.player(),
                                fish.id(),
                                league,
                                RecordBrokenEvent.Scope.SERVER,
                                entry.size(),
                                previous));
        Instant now = Instant.now();
        Instant last = lastAnnounce.get(fish.id());
        if (league.equals("all")
                && previousLeader != null
                && (last == null || Duration.between(last, now).compareTo(ANNOUNCE_THROTTLE) > 0)) {
            lastAnnounce.put(fish.id(), now);
            Component msg =
                    api.messages()
                            .get(
                                    "collection.announce.server_record",
                                    Placeholder.unparsed("player", entry.name()),
                                    Placeholder.unparsed("name", fish.name()),
                                    Placeholder.unparsed("size", fmt(entry.size())));
            api.bus()
                    .publish(
                            Channels.ANNOUNCEMENTS,
                            new Channels.Announcement(MiniMessage.miniMessage().serialize(msg)));
        }
    }

    private Optional<RecordEntry> mine(String fish, String league, UUID uuid) {
        return top.getOrDefault(fish + "|" + league, List.of()).stream()
                .filter(e -> e.player().equals(uuid))
                .findFirst();
    }

    /** 캐시에 넣는다: 같은 사람은 큰 값 하나, 크기 내림차순·먼저 잡은 사람 우선, 상위 10. 이 기록으로 1위가 바뀌었으면 true. */
    private boolean put(String fish, String league, RecordEntry entry) {
        List<RecordEntry> before = top.getOrDefault(fish + "|" + league, List.of());
        RecordEntry previousLeader = before.isEmpty() ? null : before.getFirst();
        top.compute(
                fish + "|" + league,
                (k, list) -> {
                    List<RecordEntry> next = new ArrayList<>(list == null ? List.of() : list);
                    Optional<RecordEntry> existing =
                            next.stream()
                                    .filter(e -> e.player().equals(entry.player()))
                                    .findFirst();
                    if (existing.isPresent()) {
                        if (existing.get().size() >= entry.size()) {
                            return list;
                        }
                        next.remove(existing.get());
                    }
                    next.add(entry);
                    next.sort(
                            Comparator.comparingDouble(RecordEntry::size)
                                    .reversed()
                                    .thenComparing(RecordEntry::caughtAt));
                    return List.copyOf(next.subList(0, Math.min(TOP, next.size())));
                });
        List<RecordEntry> after = top.getOrDefault(fish + "|" + league, List.of());
        return !after.isEmpty()
                && after.getFirst().player().equals(entry.player())
                && (previousLeader == null || entry.size() > previousLeader.size());
    }

    private void weekly(String fish, RecordEntry entry) {
        try {
            weekly.addFirst(new WeeklyRecord(FishId.parse(fish), entry));
        } catch (IllegalArgumentException e) {
            return;
        }
        while (weekly.size() > WEEKLY_MAX) {
            weekly.removeLast();
        }
    }

    /** 이번 주 서버 신기록 (최신 순, 이 서버가 받은 것만. 재시작하면 비어 있다). */
    public List<WeeklyRecord> weeklyRecords() {
        return List.copyOf(weekly);
    }

    /** 지역 어종 (신화 제외, 전광판 지역 페이지). */
    public Set<FishId> regionFish(RegionId region) {
        return index.regionFish().getOrDefault(region, Set.of());
    }

    @Override
    public Optional<Double> personalBest(UUID player, FishId fish) {
        return entry(player, fish, CodexScope.TOTAL).map(CodexEntry::maxSize);
    }

    @Override
    public List<RecordEntry> serverTop(FishId fish, String league, int limit) {
        List<RecordEntry> list = top.getOrDefault(fish + "|" + league, List.of());
        return list.subList(0, Math.min(limit, list.size()));
    }

    /** 관리자: 부정 기록 삭제 후 캐시에서 뺀다. */
    public void removeRecord(FishId fish, String league, UUID player) {
        SeasonId season = api.infra().season();
        var _ =
                api.database()
                        .asyncVoid(
                                c ->
                                        CollectionRepository.deleteRecord(
                                                c, fish, season, league, player))
                        .thenRunAsync(
                                () ->
                                        top.computeIfPresent(
                                                fish + "|" + league,
                                                (k, list) ->
                                                        list.stream()
                                                                .filter(
                                                                        e ->
                                                                                !e.player()
                                                                                        .equals(
                                                                                                player))
                                                                .toList()),
                                tasks.main());
    }

    // ------------------------------------------------------------------ 힌트

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
                        "codex:" + player + ":h:" + fish,
                        c -> CollectionRepository.upsertHint(c, player, fish, target, source));
        return true;
    }

    @Override
    public Optional<FishId> grantRandom(UUID player, @Nullable RegionId region, String source) {
        CodexData data = data(player);
        ContentSnapshot snapshot = api.content().snapshot();
        // 지역 순서: 요청 지역 → 권장 레벨이 높은 지역부터
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

    // ------------------------------------------------------------------ 관리자

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

    // ------------------------------------------------------------------ 이벤트

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        applySeasonBuffs(uuid);
        // Season 칭호: 실패한 지급 재시도, Season이 생기기 전 달성분 한 번 지급 (플래그 캐시가 올라온 뒤)
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
        weekly.clear();
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
