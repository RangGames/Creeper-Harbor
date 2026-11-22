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
import wiki.creeper.harbor.core.api.HarborApi;
import wiki.creeper.harbor.core.api.HarborPlayer;
import wiki.creeper.harbor.core.api.HarborSounds;
import wiki.creeper.harbor.core.api.Modifier;
import wiki.creeper.harbor.core.api.PlayerComponent;
import wiki.creeper.harbor.core.api.StatKey;
import wiki.creeper.harbor.core.api.event.ContentReloadedEvent;
import wiki.creeper.harbor.core.task.Tasks;
import wiki.creeper.harbor.jobs.api.AdvanceResult;
import wiki.creeper.harbor.jobs.api.CapstoneState;
import wiki.creeper.harbor.jobs.api.ExpGrant;
import wiki.creeper.harbor.jobs.api.ExpSource;
import wiki.creeper.harbor.jobs.api.InvestResult;
import wiki.creeper.harbor.jobs.api.JobState;
import wiki.creeper.harbor.jobs.api.JobsService;
import wiki.creeper.harbor.jobs.api.SkillNodeInfo;
import wiki.creeper.harbor.jobs.api.event.JobExpGainEvent;
import wiki.creeper.harbor.jobs.api.event.JobLevelUpEvent;
import wiki.creeper.harbor.jobs.api.event.SkillAllocatedEvent;
import wiki.creeper.harbor.jobs.content.JobLineDef;
import wiki.creeper.harbor.jobs.content.JobsBalance;
import wiki.creeper.harbor.jobs.content.SkillNode;
import wiki.creeper.harbor.jobs.data.JobsData;
import wiki.creeper.harbor.jobs.data.JobsRepository;

/**
 * Jobs core logic (docs 20 2.4): experience and levels, job advancement, skill investment, resets
 * and line changes, modifier registration. Main thread only. Every change involving currency uses
 * CurrencyService.atomic to bind the ledger entry and module table updates in one transaction.
 */
public final class JobsManager implements JobsService, Listener {

    public static final PlayerComponent<JobsData> COMPONENT =
            new PlayerComponent<>("jobs", JobsRepository::load, JobsRepository::saveState);

    static final StatKey EXP_GAIN = StatKey.of("exp_gain");

    /** Admin skill reset scopes. */
    public static final List<String> RESET_SCOPES = List.of("common", "line", "all");

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
    private final JobAdvancement advancement = new JobAdvancement(this);
    private final SkillResets resets = new SkillResets(this);

    public JobsManager(Plugin plugin, HarborApi api, Tasks tasks, Logger log) {
        this.plugin = plugin;
        this.api = api;
        this.tasks = tasks;
        this.log = log;
        this.curve = LevelCurve.of(balance());
    }

    /**
     * Job advancement cutscene (docs 20 0.3: CutsceneService via the optional NPC dependency).
     * Returns true if playback started; otherwise the default title effect is used.
     */
    public void setAdvanceCutscene(java.util.function.@Nullable Predicate<Player> cutscene) {
        this.advanceCutscene = cutscene;
    }

    public void attach(CapstoneService capstoneService, CatchUpService catchUpService) {
        this.capstones = capstoneService;
        this.catchUp = catchUpService;
    }

    // ------------------------------------------------------------------ Collaborator access

    HarborApi api() {
        return api;
    }

    Tasks tasks() {
        return tasks;
    }

    Logger log() {
        return log;
    }

    Plugin plugin() {
        return plugin;
    }

    /** Players with a currency change in flight (one at a time per player). */
    Set<UUID> busy() {
        return busy;
    }

    java.util.function.@Nullable Predicate<Player> advanceCutscene() {
        return advanceCutscene;
    }

    // ------------------------------------------------------------------ Lookup

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

    // ------------------------------------------------------------------ Experience

    @Override
    public long grantExp(UUID player, ExpGrant grant) {
        Optional<HarborPlayer> found = api.players().get(player);
        if (found.isEmpty() || grant.base() <= 0) {
            return 0;
        }
        HarborPlayer hp = found.get();
        JobsBalance b = balance();
        double amount = grant.base();
        // Region decay: ×mult at recommended level + gap or above (gold and quality untouched)
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

    /**
     * Adds EXP and levels up to the per-tier level cap. EXP beyond the cap is kept in overflow, up
     * to one more level's worth.
     */
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
        // The reference ID is unique, so even if the level is rewound the same level reward is not
        // granted again
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
            HarborSounds.play(player, "harbor:ui.level_up"); // '15. Sound' 4.5 level up
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

    // ------------------------------------------------------------------ Job advancement

    @Override
    public CompletableFuture<AdvanceResult> requestAdvance(Player player, JobTier target) {
        return advancement.request(player, target);
    }

    /** Main thread after commit: cache → automatic passives → apply overflow → event → cutscene. */
    void afterAdvance(HarborPlayer hp, JobTier target, JobLine line) {
        advancement.afterAdvance(hp, target, line);
    }

    // ------------------------------------------------------------------ Skills

    public Map<SkillId, SkillNode> nodes() {
        Map<SkillId, SkillNode> result = new HashMap<>();
        content().all(SkillNode.TYPE).values().forEach(n -> result.put(n.id(), n));
        return result;
    }

    /** Total mulbineul invested in a tree. */
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

    /** Whether investment is possible (docs 20 2.4 check order). OK means it is. */
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
                    // Other lines' 2nd tier trees allow up to 40 total after the 3rd advancement
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

    /**
     * Invests one rank. Mulbineul deduction (SKILL_INVEST) and the skill_alloc update in one
     * transaction.
     */
    @Override
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
     * Common tree reset: free once per season, then gold. Common tree investments are refunded 100%
     * (proposed value).
     *
     * @param charge false means no cost (admin)
     * @param refund false means no refund (admin)
     */
    public CompletableFuture<Boolean> resetCommon(UUID uuid, boolean charge, boolean refund) {
        return resets.resetCommon(uuid, charge, refund);
    }

    /**
     * Line change: deducts gold, refunds line tree investments × 0.8 rounded down, keeps the common
     * tree.
     */
    public CompletableFuture<Boolean> changeLine(UUID uuid, JobLine newLine, boolean charge) {
        return resets.changeLine(uuid, newLine, charge);
    }

    /**
     * Admin reset: scope = common · line · all. With refund, investments are refunded 100%; no
     * cost.
     */
    public CompletableFuture<Boolean> adminReset(UUID uuid, String scope, boolean refund) {
        return resets.adminReset(uuid, scope, refund);
    }

    /** Admin: forcibly sets tier and line (no gold deduction). */
    public CompletableFuture<Boolean> forceTier(UUID uuid, JobTier tier, @Nullable JobLine line) {
        return resets.forceTier(uuid, tier, line);
    }

    // ------------------------------------------------------------------ Modifiers

    /**
     * Re-registers node effects and automatic passives as modifiers. Sources: skill:<node>,
     * job:<line>:<tier>. Active capstone effects are applied only on use.
     */
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

    /**
     * Points invested in nodes removed from content are fully refunded and the rows deleted (docs
     * 20 2.4 edge cases).
     */
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
            // The original cost is unknown, so it cannot be reversed from the investment records
            // (ledger). Log it for admin review and just delete the row
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

    // ------------------------------------------------------------------ Capstones

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

    // ------------------------------------------------------------------ Skill API

    private static SkillNodeInfo info(SkillNode n) {
        return new SkillNodeInfo(
                n.id(),
                n.name(),
                n.tree(),
                Optional.ofNullable(n.line()),
                n.tier(),
                n.maxRank(),
                n.cost(),
                n.requiredNodes(),
                n.isActive());
    }

    @Override
    public List<SkillNodeInfo> skillNodes() {
        return content().all(SkillNode.TYPE).values().stream()
                .sorted(java.util.Comparator.comparing(n -> n.id().toString()))
                .map(JobsManager::info)
                .toList();
    }

    @Override
    public Optional<SkillNodeInfo> skillNode(SkillId node) {
        return content().get(SkillNode.TYPE, node.id()).map(JobsManager::info);
    }

    @Override
    public Map<SkillId, Integer> skills(UUID player) {
        return api.players().get(player).map(hp -> Map.copyOf(data(hp).skills())).orElse(Map.of());
    }

    @Override
    public long skillPoints(UUID player) {
        return api.players().get(player).map(hp -> hp.balance(Currency.MULBINEUL)).orElse(0L);
    }

    @Override
    public InvestResult checkInvest(UUID player, SkillId node) {
        return api.players()
                .get(player)
                .map(hp -> checkInvest(hp, node))
                .orElse(InvestResult.FAILED);
    }

    @Override
    public CompletableFuture<Boolean> resetCommonSkills(UUID player) {
        if (api.players().get(player).isEmpty()) {
            return CompletableFuture.completedFuture(false);
        }
        return resetCommon(player, true, true);
    }

    @Override
    public CompletableFuture<Boolean> changeLine(UUID player, JobLine line) {
        if (api.players().get(player).isEmpty()) {
            return CompletableFuture.completedFuture(false);
        }
        return changeLine(player, line, true);
    }

    @Override
    public CompletableFuture<Boolean> adminResetSkills(UUID player, String scope, boolean refund) {
        if (api.players().get(player).isEmpty() || !RESET_SCOPES.contains(scope)) {
            return CompletableFuture.completedFuture(false);
        }
        return adminReset(player, scope, refund);
    }

    @Override
    public CompletableFuture<Boolean> adminSetTier(
            UUID player, JobTier tier, @Nullable JobLine line) {
        if (api.players().get(player).isEmpty()) {
            return CompletableFuture.completedFuture(false);
        }
        return forceTier(player, tier, line);
    }

    @Override
    public boolean adminSetLevel(UUID player, int level, long exp) {
        Optional<HarborPlayer> found = api.players().get(player);
        if (found.isEmpty()) {
            return false;
        }
        HarborPlayer hp = found.get();
        int lv = Math.clamp(level, 1, curve.maxLevel());
        long need = curve.need(lv);
        long e = need <= 0 ? 0 : Math.clamp(exp, 0, need - 1);
        hp.setJob(hp.jobLine().orElse(null), hp.jobTier(), lv, e);
        return true;
    }

    // ------------------------------------------------------------------ Events

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
