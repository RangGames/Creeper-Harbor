package wiki.creeper.harbor.jobs.service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import wiki.creeper.harbor.common.content.ContentSnapshot;
import wiki.creeper.harbor.common.content.type.Balance;
import wiki.creeper.harbor.common.content.type.RegionDef;
import wiki.creeper.harbor.common.db.SqlConsumer;
import wiki.creeper.harbor.common.id.SkillId;
import wiki.creeper.harbor.common.ledger.LedgerEntry;
import wiki.creeper.harbor.common.ledger.LedgerError;
import wiki.creeper.harbor.common.ledger.LedgerReason;
import wiki.creeper.harbor.common.model.Currency;
import wiki.creeper.harbor.common.model.JobLine;
import wiki.creeper.harbor.common.model.JobTier;
import wiki.creeper.harbor.common.model.Quality;
import wiki.creeper.harbor.common.model.SeasonId;
import wiki.creeper.harbor.common.util.Result;
import wiki.creeper.harbor.core.api.Dialogs;
import wiki.creeper.harbor.core.api.HarborApi;
import wiki.creeper.harbor.core.api.HarborPlayer;
import wiki.creeper.harbor.core.api.Modifier;
import wiki.creeper.harbor.core.api.PlayerComponent;
import wiki.creeper.harbor.core.api.StatKey;
import wiki.creeper.harbor.core.api.event.ContentReloadedEvent;
import wiki.creeper.harbor.core.task.Sequence;
import wiki.creeper.harbor.core.task.Tasks;
import wiki.creeper.harbor.jobs.api.AdvanceResult;
import wiki.creeper.harbor.jobs.api.CapstoneState;
import wiki.creeper.harbor.jobs.api.ExpGrant;
import wiki.creeper.harbor.jobs.api.ExpSource;
import wiki.creeper.harbor.jobs.api.JobState;
import wiki.creeper.harbor.jobs.api.JobsService;
import wiki.creeper.harbor.jobs.api.event.JobAdvancedEvent;
import wiki.creeper.harbor.jobs.api.event.JobExpGainEvent;
import wiki.creeper.harbor.jobs.api.event.JobLevelUpEvent;
import wiki.creeper.harbor.jobs.api.event.JobLineChangedEvent;
import wiki.creeper.harbor.jobs.api.event.SkillAllocatedEvent;
import wiki.creeper.harbor.jobs.api.event.SkillResetEvent;
import wiki.creeper.harbor.jobs.content.JobLineDef;
import wiki.creeper.harbor.jobs.content.JobsBalance;
import wiki.creeper.harbor.jobs.content.SkillNode;
import wiki.creeper.harbor.jobs.data.JobsData;
import wiki.creeper.harbor.jobs.data.JobsRepository;

/**
 * Jobs 핵심 로직 (docs 20 2.4): 경험치·레벨, 전직, 스킬 투자·초기화·계열 변경, 보정자 등록. 메인 스레드 전용. 재화가 걸린 변경은 모두
 * CurrencyService.atomic으로 원장 기록과 모듈 테이블 갱신을 한 트랜잭션에 묶는다.
 */
public final class JobsManager implements JobsService, Listener {

    public static final PlayerComponent<JobsData> COMPONENT =
            new PlayerComponent<>("jobs", JobsRepository::load, JobsRepository::saveState);

    static final StatKey EXP_GAIN = StatKey.of("exp_gain");
    private static final Duration DIALOG_TIMEOUT = Duration.ofMinutes(5);

    private final Plugin plugin;
    private final HarborApi api;
    private final Tasks tasks;
    private final Logger log;
    private final Map<UUID, Set<String>> sources = new HashMap<>();
    private final Set<UUID> busy = new HashSet<>();
    private LevelCurve curve;
    private @Nullable CapstoneService capstones;
    private @Nullable CatchUpService catchUp;
    private java.util.function.@Nullable Predicate<Player> advanceCutscene;

    public JobsManager(Plugin plugin, HarborApi api, Tasks tasks, Logger log) {
        this.plugin = plugin;
        this.api = api;
        this.tasks = tasks;
        this.log = log;
        this.curve = LevelCurve.of(balance());
    }

    /** 전직 컷씬 (docs 20 0.3: NPC 선택 의존의 CutsceneService). 재생을 시작했으면 true를 돌려주고, 없으면 기본 타이틀 연출을 쓴다. */
    public void setAdvanceCutscene(java.util.function.@Nullable Predicate<Player> cutscene) {
        this.advanceCutscene = cutscene;
    }

    public void attach(CapstoneService capstoneService, CatchUpService catchUpService) {
        this.capstones = capstoneService;
        this.catchUp = catchUpService;
    }

    // ------------------------------------------------------------------ 조회

    public JobsBalance balance() {
        return new JobsBalance(api.content().snapshot().single(Balance.TYPE));
    }

    public ContentSnapshot content() {
        return api.content().snapshot();
    }

    public LevelCurve curve() {
        return curve;
    }

    public Optional<JobLineDef> lineDef(@Nullable JobLine line) {
        if (line == null) {
            return Optional.empty();
        }
        return content().all(JobLineDef.TYPE).values().stream()
                .filter(d -> d.line() == line)
                .findFirst();
    }

    public JobsData data(HarborPlayer hp) {
        return hp.get(COMPONENT);
    }

    @Override
    public JobState state(UUID player) {
        HarborPlayer hp = api.players().get(player).orElseThrow();
        int cap = levelCap(hp.jobTier());
        return new JobState(
                hp.jobLine(),
                hp.jobTier(),
                hp.level(),
                hp.exp(),
                hp.level() >= cap ? 0 : curve.need(hp.level()),
                cap);
    }

    @Override
    public Quality qualityCap(UUID player) {
        JobTier tier = api.players().get(player).map(HarborPlayer::jobTier).orElse(JobTier.FIRST);
        return Quality.of(Math.clamp(balance().qualityCap(tier), Quality.MIN, Quality.MAX));
    }

    int levelCap(JobTier tier) {
        return Math.min(balance().levelCap(tier), curve.maxLevel());
    }

    // ------------------------------------------------------------------ 경험치

    @Override
    public long grantExp(UUID player, ExpGrant grant) {
        Optional<HarborPlayer> found = api.players().get(player);
        if (found.isEmpty() || grant.base() <= 0) {
            return 0;
        }
        HarborPlayer hp = found.get();
        JobsBalance b = balance();
        double amount = grant.base();
        // 지역 감쇠: 권장 레벨 + gap 이상이면 ×mult (골드·품질에는 손대지 않음)
        if (grant.source() == ExpSource.FISHING && grant.region() != null) {
            Optional<RegionDef> region = content().get(RegionDef.TYPE, grant.region().id());
            if (region.isPresent()
                    && region.get().recLevel() > 0
                    && hp.level() >= region.get().recLevel() + b.regionDecayGap()) {
                amount *= b.regionDecayMult();
            }
        }
        amount *= api.modifiers().value(player, EXP_GAIN);
        JobExpGainEvent event = new JobExpGainEvent(player, grant.source(), amount);
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled()) {
            return 0;
        }
        long granted = (long) Math.floor(event.amount());
        addExp(hp, granted);
        return granted;
    }

    /** EXP를 더하고 단계별 레벨 상한까지 레벨업한다. 상한 뒤 EXP는 다음 1레벨분까지 overflow에 보관. */
    public void addExp(HarborPlayer hp, long amount) {
        if (amount <= 0) {
            return;
        }
        int level = hp.level();
        long exp = hp.exp() + amount;
        int cap = levelCap(hp.jobTier());
        List<Integer> reached = new ArrayList<>();
        while (level < cap && exp >= curve.need(level)) {
            exp -= curve.need(level);
            level++;
            reached.add(level);
        }
        if (level >= cap) {
            JobsData data = data(hp);
            long keep = Math.min(data.overflowExp() + exp, curve.need(level));
            hp.set(COMPONENT, data.withOverflow(keep));
            exp = 0;
        }
        hp.setJob(hp.jobLine().orElse(null), hp.jobTier(), level, exp);
        for (int reachedLevel : reached) {
            onLevelUp(hp, reachedLevel);
        }
    }

    private void onLevelUp(HarborPlayer hp, int level) {
        Bukkit.getPluginManager().callEvent(new JobLevelUpEvent(hp.uuid(), level));
        // 참조 ID가 유니크라서 레벨이 되감겨도 같은 레벨 보상은 다시 나가지 않는다
        String ref = "lvl:" + hp.season().value() + ":" + hp.uuid() + ":" + level;
        var _ = api.currency().give(hp.uuid(), Currency.MULBINEUL, 1, LedgerReason.LEVEL_UP, ref);
        Player player = Bukkit.getPlayer(hp.uuid());
        if (player != null) {
            api.ui()
                    .title(
                            player,
                            api.messages()
                                    .get(
                                            "jobs.level_up.title",
                                            Placeholder.unparsed("level", String.valueOf(level))),
                            api.messages().get("jobs.level_up.subtitle"),
                            Duration.ofMillis(250),
                            Duration.ofSeconds(2),
                            Duration.ofMillis(500));
        }
        if (catchUp != null) {
            catchUp.apply(hp.uuid());
        }
    }

    @Override
    public boolean claimTierFirstQuality(UUID player, Quality quality) {
        Optional<HarborPlayer> found = api.players().get(player);
        if (found.isEmpty()) {
            return false;
        }
        HarborPlayer hp = found.get();
        JobsData data = data(hp);
        boolean eligible =
                (hp.jobTier() == JobTier.SECOND && quality.stars() == 4 && !data.tierFirstQ4())
                        || (hp.jobTier() == JobTier.THIRD
                                && quality.stars() == 5
                                && !data.tierFirstQ5());
        if (!eligible) {
            return false;
        }
        hp.set(COMPONENT, data.withTierFirst(quality.stars()));
        String ref = "tfq:" + hp.season().value() + ":" + player + ":" + quality.stars();
        var _ =
                api.currency()
                        .give(
                                player,
                                Currency.MULBINEUL,
                                balance().tierFirstQualityBonus(),
                                LedgerReason.TIER_FIRST_QUALITY,
                                ref);
        return true;
    }

    // ------------------------------------------------------------------ 전직

    @Override
    public CompletableFuture<AdvanceResult> requestAdvance(Player player, JobTier target) {
        HarborPlayer hp = api.players().get(player);
        JobsBalance b = balance();
        if (busy.contains(hp.uuid())) {
            return CompletableFuture.completedFuture(AdvanceResult.BUSY);
        }
        if (target.level() != hp.jobTier().level() + 1) {
            return CompletableFuture.completedFuture(AdvanceResult.WRONG_TIER);
        }
        if (hp.level() < b.advanceLevel(target)) {
            return CompletableFuture.completedFuture(AdvanceResult.LEVEL_TOO_LOW);
        }
        long cost = b.advanceCost(target);
        if (hp.balance(Currency.GOLD) < cost) {
            return CompletableFuture.completedFuture(AdvanceResult.NOT_ENOUGH_GOLD);
        }
        CompletableFuture<AdvanceResult> result = new CompletableFuture<>();
        var _ =
                tasks.later(
                        DIALOG_TIMEOUT.toSeconds() * 20,
                        () -> result.complete(AdvanceResult.CANCELLED));
        if (target == JobTier.SECOND) {
            List<JobLine> lines = List.of(JobLine.values());
            List<Dialogs.Choice> choices = new ArrayList<>();
            for (JobLine line : lines) {
                String name = lineDef(line).map(d -> d.name(2)).orElse(line.key());
                choices.add(new Dialogs.Choice(Component.text(name), passiveSummary(line)));
            }
            api.dialogs()
                    .choose(
                            player,
                            api.messages().get("jobs.advance.choose_title"),
                            List.of(api.messages().get("jobs.advance.choose_body")),
                            choices,
                            2,
                            index ->
                                    confirmAdvance(player, target, lines.get(index), cost, result));
        } else {
            hp.jobLine()
                    .ifPresentOrElse(
                            line -> confirmAdvance(player, target, line, cost, result),
                            () -> result.complete(AdvanceResult.FAILED));
        }
        return result;
    }

    private Component passiveSummary(JobLine line) {
        StringBuilder text = new StringBuilder();
        lineDef(line)
                .ifPresent(
                        def ->
                                def.passives().stream()
                                        .filter(p -> p.tier() == 2)
                                        .forEach(
                                                p ->
                                                        text.append(p.stat())
                                                                .append(' ')
                                                                .append(signed(p.value()))
                                                                .append('\n')));
        return Component.text(text.toString().strip());
    }

    private void confirmAdvance(
            Player player,
            JobTier target,
            JobLine line,
            long cost,
            CompletableFuture<AdvanceResult> result) {
        String name = lineDef(line).map(d -> d.name(target.level())).orElse(line.key());
        api.dialogs()
                .confirm(
                        player,
                        api.messages().get("jobs.advance.confirm_title"),
                        List.of(
                                api.messages()
                                        .get(
                                                "jobs.advance.confirm_body",
                                                Placeholder.unparsed("job", name),
                                                Placeholder.unparsed(
                                                        "cost", String.valueOf(cost)))),
                        api.messages().get("jobs.advance.yes"),
                        api.messages().get("jobs.advance.no"),
                        ok -> {
                            if (!ok) {
                                result.complete(AdvanceResult.CANCELLED);
                                return;
                            }
                            commitAdvance(player, target, line, cost, result);
                        });
    }

    private void commitAdvance(
            Player player,
            JobTier target,
            JobLine line,
            long cost,
            CompletableFuture<AdvanceResult> result) {
        HarborPlayer hp = api.players().get(player);
        UUID uuid = hp.uuid();
        SeasonId season = hp.season();
        if (!busy.add(uuid)) {
            result.complete(AdvanceResult.BUSY);
            return;
        }
        String ref = "adv:" + season.value() + ":" + uuid + ":" + target.level();
        var _ =
                api.currency()
                        .atomic(
                                List.of(
                                        new LedgerEntry(
                                                uuid,
                                                Currency.GOLD,
                                                -cost,
                                                LedgerReason.JOB_ADVANCE,
                                                ref)),
                                c -> JobsRepository.updateJob(c, uuid, season, line, target))
                        .thenAccept(
                                r -> {
                                    busy.remove(uuid);
                                    switch (r) {
                                        case Result.Ok<List<Long>, LedgerError> _ -> {
                                            afterAdvance(hp, target, line);
                                            result.complete(AdvanceResult.OK);
                                        }
                                        case Result.Err<List<Long>, LedgerError>(
                                                        LedgerError error) ->
                                                result.complete(
                                                        error == LedgerError.INSUFFICIENT_FUNDS
                                                                ? AdvanceResult.NOT_ENOUGH_GOLD
                                                                : AdvanceResult.FAILED);
                                    }
                                });
    }

    /** 커밋 뒤 메인 스레드: 캐시 → 자동 패시브 → overflow 반영 → 이벤트 → 컷씬. */
    void afterAdvance(HarborPlayer hp, JobTier target, JobLine line) {
        hp.setJob(line, target, hp.level(), hp.exp());
        rebuildModifiers(hp);
        JobsData data = data(hp);
        if (data.overflowExp() > 0) {
            hp.set(COMPONENT, data.withOverflow(0));
            addExp(hp, data.overflowExp());
        }
        Bukkit.getPluginManager().callEvent(new JobAdvancedEvent(hp.uuid(), target, line));
        Player player = Bukkit.getPlayer(hp.uuid());
        if (player != null) {
            String name = lineDef(line).map(d -> d.name(target.level())).orElse(line.key());
            var cutscene = advanceCutscene;
            if (cutscene != null && cutscene.test(player)) {
                api.messages().send(player, "jobs.advance.done", Placeholder.unparsed("job", name));
                return;
            }
            Sequence.of(plugin, player)
                    .run(
                            p ->
                                    api.ui()
                                            .title(
                                                    p,
                                                    api.messages()
                                                            .get("jobs.advance.cutscene_title"),
                                                    Component.text(name),
                                                    Duration.ofMillis(500),
                                                    Duration.ofSeconds(3),
                                                    Duration.ofSeconds(1)))
                    .waitTicks(60)
                    .run(
                            p ->
                                    api.messages()
                                            .send(
                                                    p,
                                                    "jobs.advance.done",
                                                    Placeholder.unparsed("job", name)))
                    .start();
        }
    }

    // ------------------------------------------------------------------ 스킬

    public Map<SkillId, SkillNode> nodes() {
        Map<SkillId, SkillNode> result = new HashMap<>();
        content().all(SkillNode.TYPE).values().forEach(n -> result.put(n.id(), n));
        return result;
    }

    /** 트리에 투자한 물비늘 합계. */
    public long pointsIn(JobsData data, Predicate<SkillNode> filter) {
        Map<SkillId, SkillNode> nodes = nodes();
        long sum = 0;
        for (Map.Entry<SkillId, Integer> e : data.skills().entrySet()) {
            SkillNode node = nodes.get(e.getKey());
            if (node != null && filter.test(node)) {
                sum += node.invested(e.getValue());
            }
        }
        return sum;
    }

    /** 투자 가능 여부 (docs 20 2.4 검사 순서). OK면 투자 가능. */
    public InvestResult checkInvest(HarborPlayer hp, SkillId nodeId) {
        SkillNode node = nodes().get(nodeId);
        if (node == null) {
            return InvestResult.UNKNOWN_NODE;
        }
        JobsBalance b = balance();
        JobsData data = data(hp);
        JobLine myLine = hp.jobLine().orElse(null);
        int tier = hp.jobTier().level();
        switch (node.kind()) {
            case COMMON -> {}
            case LINE_SECOND -> {
                if (tier < 2) {
                    return InvestResult.JOB_LOCKED;
                }
                if (node.line() != myLine) {
                    // 다른 계열 2차 트리는 3차 전직 후 합계 40까지
                    long other =
                            pointsIn(
                                    data,
                                    n ->
                                            n.kind() == SkillNode.Kind.LINE_SECOND
                                                    && n.line() != myLine);
                    if (tier < 3 || other + node.cost() > b.otherLineLimit()) {
                        return InvestResult.JOB_LOCKED;
                    }
                }
            }
            case LINE_THIRD -> {
                if (tier < 3 || node.line() != myLine) {
                    return InvestResult.JOB_LOCKED;
                }
            }
            case CAPSTONE -> {
                long third =
                        pointsIn(
                                data,
                                n -> n.kind() == SkillNode.Kind.LINE_THIRD && n.line() == myLine);
                if (tier < 3 || node.line() != myLine || third < b.capstoneRequires()) {
                    return InvestResult.JOB_LOCKED;
                }
            }
        }
        long inTree = pointsIn(data, n -> n.tree().equals(node.tree()) && n.line() == node.line());
        if (inTree < b.tierPoints(node.tier()) || inTree < node.pointsInTree()) {
            return InvestResult.TIER_LOCKED;
        }
        for (SkillId required : node.requiredNodes()) {
            if (data.rank(required) <= 0) {
                return InvestResult.REQUIRES_NODES;
            }
        }
        if (data.rank(nodeId) >= node.maxRank()) {
            return InvestResult.MAX_RANK;
        }
        if (hp.balance(Currency.MULBINEUL) < node.cost()) {
            return InvestResult.NOT_ENOUGH_MULBINEUL;
        }
        return InvestResult.OK;
    }

    /** 한 랭크 투자. 물비늘 차감(SKILL_INVEST)과 skill_alloc 갱신을 한 트랜잭션으로. */
    public CompletableFuture<InvestResult> invest(Player player, SkillId nodeId) {
        HarborPlayer hp = api.players().get(player);
        UUID uuid = hp.uuid();
        InvestResult check = checkInvest(hp, nodeId);
        if (check != InvestResult.OK) {
            return CompletableFuture.completedFuture(check);
        }
        if (!busy.add(uuid)) {
            return CompletableFuture.completedFuture(InvestResult.BUSY);
        }
        SkillNode node = Objects.requireNonNull(nodes().get(nodeId));
        int rank = data(hp).rank(nodeId) + 1;
        SeasonId season = hp.season();
        String ref = "inv:" + UUID.randomUUID();
        return api.currency()
                .atomic(
                        List.of(
                                new LedgerEntry(
                                        uuid,
                                        Currency.MULBINEUL,
                                        -node.cost(),
                                        LedgerReason.SKILL_INVEST,
                                        ref)),
                        c -> JobsRepository.setRank(c, uuid, season, nodeId, rank))
                .thenApply(
                        r -> {
                            busy.remove(uuid);
                            if (r
                                    instanceof
                                    Result.Err<List<Long>, LedgerError>(LedgerError error)) {
                                return error == LedgerError.INSUFFICIENT_FUNDS
                                        ? InvestResult.NOT_ENOUGH_MULBINEUL
                                        : InvestResult.FAILED;
                            }
                            hp.set(COMPONENT, data(hp).withRank(nodeId, rank));
                            rebuildModifiers(hp);
                            Bukkit.getPluginManager()
                                    .callEvent(new SkillAllocatedEvent(uuid, nodeId, rank));
                            return InvestResult.OK;
                        });
    }

    /**
     * 공통 트리 초기화: 시즌 1회 무료, 이후 골드. 공통 트리 투자분은 100% 환급 (제안값).
     *
     * @param charge false면 비용 없이 (관리자)
     * @param refund false면 환급 없이 (관리자)
     */
    public CompletableFuture<Boolean> resetCommon(UUID uuid, boolean charge, boolean refund) {
        HarborPlayer hp = api.players().get(uuid).orElseThrow();
        JobsData data = data(hp);
        Map<SkillId, SkillNode> nodes = nodes();
        List<SkillId> common =
                data.skills().keySet().stream()
                        .filter(
                                id ->
                                        nodes.containsKey(id)
                                                && Objects.requireNonNull(nodes.get(id)).kind()
                                                        == SkillNode.Kind.COMMON)
                        .toList();
        long invested = pointsIn(data, n -> n.kind() == SkillNode.Kind.COMMON);
        boolean free = !data.freeResetUsed();
        long cost = charge && !free ? balance().commonResetCost() : 0;
        String ref = "rst:" + UUID.randomUUID();
        List<LedgerEntry> entries = new ArrayList<>();
        if (cost > 0) {
            entries.add(new LedgerEntry(uuid, Currency.GOLD, -cost, LedgerReason.SKILL_RESET, ref));
        }
        if (refund && invested > 0) {
            entries.add(
                    new LedgerEntry(
                            uuid, Currency.MULBINEUL, invested, LedgerReason.SKILL_REFUND, ref));
        }
        JobsData next = (charge && free ? data.withFreeResetUsed() : data);
        Map<SkillId, Integer> kept = new HashMap<>(data.skills());
        common.forEach(kept::remove);
        JobsData after = next.withSkills(kept);
        SeasonId season = hp.season();
        SqlConsumer extra =
                c -> {
                    JobsRepository.deleteNodes(c, uuid, season, common);
                    JobsRepository.saveState(c, uuid, season, after);
                };
        long refunded = refund ? invested : 0;
        return runChange(uuid, entries, extra)
                .thenApply(
                        ok -> {
                            if (ok) {
                                hp.set(COMPONENT, after);
                                rebuildModifiers(hp);
                                Bukkit.getPluginManager()
                                        .callEvent(new SkillResetEvent(uuid, "common", refunded));
                            }
                            return ok;
                        });
    }

    /** 계열 변경: 골드 차감, 계열 트리 투자분 × 0.8 내림 환급, 공통 트리는 유지. */
    public CompletableFuture<Boolean> changeLine(UUID uuid, JobLine newLine, boolean charge) {
        HarborPlayer hp = api.players().get(uuid).orElseThrow();
        JobLine oldLine = hp.jobLine().orElse(null);
        if (hp.jobTier() == JobTier.FIRST || oldLine == null || oldLine == newLine) {
            return CompletableFuture.completedFuture(false);
        }
        JobsData data = data(hp);
        Map<SkillId, SkillNode> nodes = nodes();
        List<SkillId> lineNodes =
                data.skills().keySet().stream()
                        .filter(
                                id ->
                                        !nodes.containsKey(id)
                                                || Objects.requireNonNull(nodes.get(id)).kind()
                                                        != SkillNode.Kind.COMMON)
                        .toList();
        long invested = pointsIn(data, n -> n.kind() != SkillNode.Kind.COMMON);
        long refund = (long) Math.floor(invested * balance().refundRatio());
        long cost = charge ? balance().lineChangeCost(hp.jobTier()) : 0;
        String ref = "line:" + UUID.randomUUID();
        List<LedgerEntry> entries = new ArrayList<>();
        if (cost > 0) {
            entries.add(new LedgerEntry(uuid, Currency.GOLD, -cost, LedgerReason.LINE_CHANGE, ref));
        }
        if (refund > 0) {
            entries.add(
                    new LedgerEntry(
                            uuid, Currency.MULBINEUL, refund, LedgerReason.SKILL_REFUND, ref));
        }
        Map<SkillId, Integer> kept = new HashMap<>(data.skills());
        lineNodes.forEach(kept::remove);
        SeasonId season = hp.season();
        JobTier tier = hp.jobTier();
        SqlConsumer extra =
                c -> {
                    JobsRepository.deleteNodes(c, uuid, season, lineNodes);
                    JobsRepository.updateJob(c, uuid, season, newLine, tier);
                };
        return runChange(uuid, entries, extra)
                .thenApply(
                        ok -> {
                            if (ok) {
                                hp.setJob(newLine, tier, hp.level(), hp.exp());
                                hp.set(COMPONENT, data.withSkills(kept).withCapstoneCleared());
                                rebuildModifiers(hp);
                                Bukkit.getPluginManager()
                                        .callEvent(new JobLineChangedEvent(uuid, oldLine, newLine));
                            }
                            return ok;
                        });
    }

    /** 재화 기록이 있으면 atomic, 없으면 추가 SQL만 한 트랜잭션으로. 결과는 메인 스레드. */
    private CompletableFuture<Boolean> runChange(
            UUID uuid, List<LedgerEntry> entries, SqlConsumer extra) {
        if (!busy.add(uuid)) {
            return CompletableFuture.completedFuture(false);
        }
        CompletableFuture<Boolean> done =
                entries.isEmpty()
                        ? api.database().asyncVoid(extra).thenApply(_ -> true)
                        : api.currency().atomic(entries, extra).thenApply(Result::isOk);
        return done.exceptionally(
                        e -> {
                            log.error("직업 데이터 변경 실패: {}", uuid, e);
                            return false;
                        })
                .thenApplyAsync(
                        ok -> {
                            busy.remove(uuid);
                            return ok;
                        },
                        tasks.main());
    }

    /** 관리자 초기화: scope = common · line · all. refund면 투자분 100% 환급, 비용 없음. */
    public CompletableFuture<Boolean> adminReset(UUID uuid, String scope, boolean refund) {
        if (scope.equals("common")) {
            return resetCommon(uuid, false, refund);
        }
        HarborPlayer hp = api.players().get(uuid).orElseThrow();
        JobsData data = data(hp);
        Map<SkillId, SkillNode> nodes = nodes();
        Predicate<SkillId> target =
                id ->
                        scope.equals("all")
                                || !nodes.containsKey(id)
                                || Objects.requireNonNull(nodes.get(id)).kind()
                                        != SkillNode.Kind.COMMON;
        List<SkillId> removed = data.skills().keySet().stream().filter(target).toList();
        long invested = pointsIn(data, n -> target.test(n.id()));
        List<LedgerEntry> entries = new ArrayList<>();
        if (refund && invested > 0) {
            entries.add(
                    new LedgerEntry(
                            uuid,
                            Currency.MULBINEUL,
                            invested,
                            LedgerReason.SKILL_REFUND,
                            "adm:" + UUID.randomUUID()));
        }
        Map<SkillId, Integer> kept = new HashMap<>(data.skills());
        removed.forEach(kept::remove);
        SeasonId season = hp.season();
        long refunded = refund ? invested : 0;
        return runChange(uuid, entries, c -> JobsRepository.deleteNodes(c, uuid, season, removed))
                .thenApply(
                        ok -> {
                            if (ok) {
                                hp.set(COMPONENT, data.withSkills(kept));
                                rebuildModifiers(hp);
                                Bukkit.getPluginManager()
                                        .callEvent(new SkillResetEvent(uuid, scope, refunded));
                            }
                            return ok;
                        });
    }

    /** 관리자: 단계·계열 강제 지정 (골드 차감 없음). */
    public CompletableFuture<Boolean> forceTier(UUID uuid, JobTier tier, @Nullable JobLine line) {
        HarborPlayer hp = api.players().get(uuid).orElseThrow();
        SeasonId season = hp.season();
        JobLine nextLine =
                tier == JobTier.FIRST
                        ? null
                        : line != null ? line : hp.jobLine().orElse(JobLine.EXPLORE);
        return runChange(
                        uuid,
                        List.of(),
                        c -> JobsRepository.updateJob(c, uuid, season, nextLine, tier))
                .thenApply(
                        ok -> {
                            if (ok) {
                                if (nextLine == null) {
                                    hp.setJob(
                                            null,
                                            tier,
                                            Math.min(hp.level(), levelCap(tier)),
                                            hp.exp());
                                    rebuildModifiers(hp);
                                } else {
                                    afterAdvance(hp, tier, nextLine);
                                }
                            }
                            return ok;
                        });
    }

    // ------------------------------------------------------------------ 보정자

    /** 노드 효과와 자동 패시브를 보정자로 다시 등록한다. 출처: skill:<노드>, job:<계열>:<단계>. 액티브 캡스톤의 효과는 사용할 때만 건다. */
    public void rebuildModifiers(HarborPlayer hp) {
        UUID uuid = hp.uuid();
        Set<String> old = sources.getOrDefault(uuid, Set.of());
        old.forEach(source -> api.modifiers().removeSource(uuid, source));
        Set<String> added = new HashSet<>();
        Map<SkillId, SkillNode> nodes = nodes();
        for (Map.Entry<SkillId, Integer> e : data(hp).skills().entrySet()) {
            SkillNode node = nodes.get(e.getKey());
            if (node == null || node.isActive()) {
                continue;
            }
            String source = "skill:" + node.id().id().path();
            for (SkillNode.Effect effect : node.effects()) {
                double value =
                        effect.op() == Modifier.Op.MULTIPLY
                                ? Math.pow(effect.valuePerRank(), e.getValue())
                                : effect.valuePerRank() * e.getValue();
                api.modifiers()
                        .add(uuid, new Modifier(effect.stat(), effect.op(), value, source, null));
            }
            added.add(source);
        }
        JobLine line = hp.jobLine().orElse(null);
        Optional<JobLineDef> def = lineDef(line);
        if (def.isPresent()) {
            for (JobLineDef.Passive passive : def.get().passives()) {
                if (hp.jobTier().level() >= passive.tier()) {
                    String source = "job:" + def.get().line().key() + ":" + passive.tier();
                    api.modifiers()
                            .add(uuid, Modifier.add(passive.stat(), passive.value(), source));
                    added.add(source);
                }
            }
        }
        sources.put(uuid, added);
    }

    /** 콘텐츠에서 사라진 노드에 투자된 포인트는 전액 환급하고 행을 지운다 (docs 20 2.4 모서리 상황). */
    private void refundRemovedNodes(HarborPlayer hp) {
        JobsData data = data(hp);
        Map<SkillId, SkillNode> nodes = nodes();
        for (Map.Entry<SkillId, Integer> e : data.skills().entrySet()) {
            if (nodes.containsKey(e.getKey())) {
                continue;
            }
            SkillId removed = e.getKey();
            UUID uuid = hp.uuid();
            SeasonId season = hp.season();
            // 원래 비용을 모르므로 투자 기록(원장)에서 되돌릴 수 없다. 관리자 확인용으로 로그를 남기고 행만 지운다
            log.warn("사라진 스킬 노드 {}에 {}랭크 투자 ({}). 관리자 환급 필요", removed, e.getValue(), hp.name());
            var _ =
                    api.database()
                            .asyncVoid(
                                    c ->
                                            JobsRepository.deleteNodes(
                                                    c, uuid, season, List.of(removed)));
            hp.set(COMPONENT, data(hp).withRank(removed, 0));
        }
    }

    // ------------------------------------------------------------------ 캡스톤

    @Override
    public CapstoneState capstone(UUID player) {
        return capstones == null
                ? new CapstoneState(Optional.empty(), Duration.ZERO, -1)
                : capstones.state(player);
    }

    @Override
    public CompletableFuture<Boolean> consumeWeeklyUse(UUID player) {
        return capstones == null
                ? CompletableFuture.completedFuture(false)
                : capstones.consumeWeeklyUse(player);
    }

    // ------------------------------------------------------------------ 이벤트

    @EventHandler(priority = EventPriority.NORMAL)
    public void onJoin(PlayerJoinEvent event) {
        api.players()
                .get(event.getPlayer().getUniqueId())
                .ifPresent(
                        hp -> {
                            refundRemovedNodes(hp);
                            rebuildModifiers(hp);
                            if (catchUp != null) {
                                catchUp.apply(hp.uuid());
                            }
                            if (capstones != null) {
                                capstones.restoreCooldown(event.getPlayer());
                            }
                        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        sources.remove(event.getPlayer().getUniqueId());
        busy.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onReload(ContentReloadedEvent event) {
        curve = LevelCurve.of(balance());
        api.players().online().forEach(this::rebuildModifiers);
    }

    public static String signed(double value) {
        return (value >= 0 ? "+" : "") + value;
    }
}
