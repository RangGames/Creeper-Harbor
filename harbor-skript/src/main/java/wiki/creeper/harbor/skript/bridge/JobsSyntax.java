package wiki.creeper.harbor.skript.bridge;

import ch.njol.skript.classes.Changer.ChangeMode;
import ch.njol.skript.util.Timespan;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Function;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import wiki.creeper.harbor.common.id.SkillId;
import wiki.creeper.harbor.common.model.JobLine;
import wiki.creeper.harbor.common.model.JobTier;
import wiki.creeper.harbor.common.model.Keyed;
import wiki.creeper.harbor.jobs.api.CapstoneState;
import wiki.creeper.harbor.jobs.api.ExpGrant;
import wiki.creeper.harbor.jobs.api.ExpSource;
import wiki.creeper.harbor.jobs.api.InvestResult;
import wiki.creeper.harbor.jobs.api.JobState;
import wiki.creeper.harbor.jobs.api.JobsService;
import wiki.creeper.harbor.jobs.api.SkillNodeInfo;
import wiki.creeper.harbor.jobs.api.event.CapstoneUsedEvent;
import wiki.creeper.harbor.jobs.api.event.JobAdvancedEvent;
import wiki.creeper.harbor.jobs.api.event.JobExpGainEvent;
import wiki.creeper.harbor.jobs.api.event.JobLevelUpEvent;
import wiki.creeper.harbor.jobs.api.event.JobLineChangedEvent;
import wiki.creeper.harbor.jobs.api.event.SkillAllocatedEvent;
import wiki.creeper.harbor.jobs.api.event.SkillResetEvent;
import wiki.creeper.harbor.skript.Ids;
import wiki.creeper.harbor.skript.lambda.Ctx;
import wiki.creeper.harbor.skript.lambda.Ext;
import wiki.creeper.harbor.skript.lambda.Registrar;

/**
 * When Harbor-Jobs is enabled: job state (level, EXP, next level EXP, level cap, quality cap,
 * capstone), EXP grants, job advancement requests, weekly capstone use, and every Jobs event.
 */
public final class JobsSyntax {

    private JobsSyntax() {}

    private static Optional<JobsService> jobs() {
        return Shared.api().service(JobsService.class);
    }

    public static void register(Registrar r) {
        state(r);
        actions(r);
        skills(r);
        admin(r);
        events(r);
    }

    // ---------------------------------------------------------------- State

    private static <T> List<T> perPlayer(Ctx c, Function<Player, T> f) {
        return perPlayer(c, 0, f);
    }

    private static <T> List<T> perPlayer(Ctx c, int index, Function<Player, T> f) {
        return c.players(index).stream()
                .filter(p -> Shared.api().players().get(p.getUniqueId()).isPresent())
                .map(f)
                .toList();
    }

    private static List<Number> jobNumber(Ctx c, Function<JobState, Number> f) {
        Optional<JobsService> s = jobs();
        if (s.isEmpty()) {
            return List.of();
        }
        return perPlayer(c, p -> f.apply(s.get().state(p.getUniqueId())));
    }

    private static void state(Registrar r) {
        Ext.expr(r, Number.class, "harbor job level")
                .register(
                        c -> jobNumber(c, JobState::level),
                        "[the] harbor job level of %players%",
                        "%players%'[s] harbor job level");
        Ext.expr(r, Number.class, "harbor job exp")
                .register(
                        c -> jobNumber(c, JobState::exp),
                        "[the] harbor job exp[erience] of %players%",
                        "%players%'[s] harbor job exp[erience]");
        Ext.expr(r, Number.class, "harbor next level exp")
                .register(
                        c -> jobNumber(c, JobState::nextLevelExp),
                        "[the] harbor next level exp[erience] of %players%",
                        "%players%'[s] harbor next level exp[erience]");
        Ext.expr(r, Number.class, "harbor level cap")
                .register(
                        c -> jobNumber(c, JobState::levelCap),
                        "[the] harbor level cap of %players%",
                        "%players%'[s] harbor level cap");
        Ext.expr(r, Number.class, "harbor quality cap")
                .register(
                        c ->
                                jobs().map(
                                                s ->
                                                        perPlayer(
                                                                c,
                                                                p ->
                                                                        (Number)
                                                                                s.qualityCap(
                                                                                                p
                                                                                                        .getUniqueId())
                                                                                        .stars()))
                                        .orElse(List.of()),
                        "[the] harbor quality cap of %players%",
                        "%players%'[s] harbor quality cap");

        Ext.expr(r, String.class, "harbor capstone")
                .register(
                        c ->
                                capstones(c).stream()
                                        .flatMap(cs -> cs.node().stream())
                                        .map(Object::toString)
                                        .toList(),
                        "[the] harbor capstone [skill] of %players%");
        Ext.expr(r, Timespan.class, "harbor capstone cooldown")
                .register(
                        c ->
                                capstones(c).stream()
                                        .map(
                                                cs ->
                                                        new Timespan(
                                                                Timespan.TimePeriod.MILLISECOND,
                                                                Math.max(
                                                                        0,
                                                                        cs.cooldownLeft()
                                                                                .toMillis())))
                                        .toList(),
                        "[the] harbor capstone cooldown of %players%");
        Ext.expr(r, Number.class, "harbor capstone weekly uses")
                .register(
                        c -> capstones(c).stream().map(cs -> (Number) cs.weeklyUsesLeft()).toList(),
                        "[the] harbor capstone weekly uses [left] of %players%");
    }

    private static List<CapstoneState> capstones(Ctx c) {
        Optional<JobsService> s = jobs();
        if (s.isEmpty()) {
            return List.of();
        }
        return perPlayer(c, p -> s.get().capstone(p.getUniqueId()));
    }

    // ---------------------------------------------------------------- Actions

    private static void actions(Registrar r) {
        Ext.effect(
                r,
                "harbor give job exp",
                c -> {
                    Optional<JobsService> s = jobs();
                    double base = c.number(0).map(Number::doubleValue).orElse(0.0);
                    if (s.isEmpty() || !(base > 0) || Double.isInfinite(base)) {
                        c.set(2, List.of());
                        return;
                    }
                    List<Object> granted = new java.util.ArrayList<>();
                    for (Player p : c.players(1)) {
                        if (Shared.api().players().get(p.getUniqueId()).isEmpty()) {
                            continue;
                        }
                        granted.add(
                                s.get()
                                        .grantExp(
                                                p.getUniqueId(),
                                                new ExpGrant(
                                                        ExpSource.ADMIN,
                                                        base,
                                                        null,
                                                        Shared.ref())));
                    }
                    c.set(2, granted);
                },
                "harbor (give|grant) %number% job exp[erience] to %players% [and (store|save)"
                        + " [the] (result|granted amount) in %-objects%]");

        Ext.await(
                r,
                "harbor request job advance",
                new int[] {2},
                c -> {
                    Optional<JobsService> s = jobs();
                    Optional<Player> p = c.player(0);
                    int tier = c.number(1).map(Number::intValue).orElse(0);
                    if (s.isEmpty() || p.isEmpty() || tier < 1 || tier > 3) {
                        return null;
                    }
                    return Ext.value(
                            s.get()
                                    .requestAdvance(p.get(), JobTier.of(tier))
                                    .thenApply(res -> res.name().toLowerCase(Locale.ROOT)));
                },
                "make %player% request harbor job advance[ment] to tier %number% [and (store|save)"
                        + " [the] result in %-objects%]");

        Ext.await(
                r,
                "harbor consume capstone use",
                new int[] {1},
                c -> {
                    Optional<JobsService> s = jobs();
                    Optional<OfflinePlayer> p = c.offlinePlayer(0);
                    if (s.isEmpty() || p.isEmpty()) {
                        return null;
                    }
                    return Ext.value(s.get().consumeWeeklyUse(p.get().getUniqueId()));
                },
                "harbor consume [a] weekly capstone use of %offlineplayer% [and (store|save) [the]"
                        + " result in %-objects%]");
    }

    // ---------------------------------------------------------------- Skills

    private static Optional<SkillId> skill(Ctx c, int index) {
        return c.string(index).flatMap(s -> Ids.parse(s, SkillId.PREFIX)).map(SkillId::new);
    }

    private static Optional<SkillNodeInfo> node(Ctx c, int index) {
        Optional<JobsService> s = jobs();
        Optional<SkillId> id = skill(c, index);
        if (s.isEmpty() || id.isEmpty()) {
            return Optional.empty();
        }
        return s.get().skillNode(id.get());
    }

    private static String key(Enum<?> e) {
        return e.name().toLowerCase(Locale.ROOT);
    }

    private static void skills(Registrar r) {
        Ext.expr(r, Number.class, "harbor skill rank")
                .perArg(1)
                .register(
                        c -> {
                            Optional<JobsService> s = jobs();
                            Optional<SkillId> id = skill(c, 0);
                            if (s.isEmpty() || id.isEmpty()) {
                                return List.of();
                            }
                            return perPlayer(
                                    c,
                                    1,
                                    p -> s.get().skills(p.getUniqueId()).getOrDefault(id.get(), 0));
                        },
                        "[the] harbor skill rank of %string% (of|for) %players%");
        Ext.expr(r, String.class, "harbor skills of")
                .list()
                .register(
                        c ->
                                jobs().flatMap(s -> c.player(0).map(p -> s.skills(p.getUniqueId())))
                                        .map(
                                                m ->
                                                        m.entrySet().stream()
                                                                .filter(e -> e.getValue() > 0)
                                                                .map(e -> e.getKey().toString())
                                                                .sorted()
                                                                .toList())
                                        .orElse(List.of()),
                        "[all] [the] harbor skills of %player%",
                        "[all] [the] %player%'[s] harbor skills");
        Ext.expr(r, Number.class, "harbor skill points")
                .register(
                        c ->
                                jobs().map(
                                                s ->
                                                        perPlayer(
                                                                c,
                                                                0,
                                                                p ->
                                                                        (Number)
                                                                                s.skillPoints(
                                                                                        p
                                                                                                .getUniqueId())))
                                        .orElse(List.of()),
                        "[the] harbor skill points of %players%",
                        "%players%'[s] harbor skill points");
        Ext.expr(r, String.class, "harbor skill nodes")
                .list()
                .register(
                        c ->
                                jobs().map(
                                                s ->
                                                        s.skillNodes().stream()
                                                                .filter(
                                                                        n ->
                                                                                c.string(0)
                                                                                        .map(
                                                                                                t ->
                                                                                                        t
                                                                                                                .equals(
                                                                                                                        n
                                                                                                                                .tree()))
                                                                                        .orElse(
                                                                                                true))
                                                                .map(n -> n.id().toString())
                                                                .toList())
                                        .orElse(List.of()),
                        "[all] [the] harbor skill nodes [in tree %-string%]");
        // mark 0 name, 1 tree, 2 line
        Ext.expr(r, String.class, "harbor skill text")
                .single()
                .register(
                        c ->
                                node(c, 0)
                                        .map(
                                                n ->
                                                        switch (c.mark()) {
                                                            case 1 -> List.of(n.tree());
                                                            case 2 ->
                                                                    n.line()
                                                                            .map(
                                                                                    l ->
                                                                                            List.of(
                                                                                                    l
                                                                                                            .key()))
                                                                            .orElse(
                                                                                    List
                                                                                            .<String>
                                                                                                    of());
                                                            default -> List.of(n.name());
                                                        })
                                        .orElse(List.of()),
                        "[the] harbor skill (0:name|1:tree|2:line) of %string%");
        // mark 0 tier, 1 max rank, 2 cost
        Ext.expr(r, Number.class, "harbor skill number")
                .single()
                .register(
                        c ->
                                node(c, 0)
                                        .map(
                                                n ->
                                                        List.<Number>of(
                                                                switch (c.mark()) {
                                                                    case 1 -> n.maxRank();
                                                                    case 2 -> n.cost();
                                                                    default -> n.tier();
                                                                }))
                                        .orElse(List.of()),
                        "[the] harbor skill (0:tier|1:max rank|2:cost) of %string%");
        Ext.expr(r, String.class, "harbor skill required nodes")
                .list()
                .register(
                        c ->
                                node(c, 0)
                                        .map(
                                                n ->
                                                        n.requires().stream()
                                                                .map(Object::toString)
                                                                .toList())
                                        .orElse(List.of()),
                        "[the] harbor skill requirements of %string%",
                        "[the] required harbor skills of %string%");

        Ext.expr(r, String.class, "harbor skill invest check")
                .single()
                .register(
                        c -> {
                            Optional<JobsService> s = jobs();
                            Optional<SkillId> id = skill(c, 0);
                            Optional<Player> p = c.player(1);
                            if (s.isEmpty() || id.isEmpty() || p.isEmpty()) {
                                return List.of();
                            }
                            return List.of(
                                    key(s.get().checkInvest(p.get().getUniqueId(), id.get())));
                        },
                        "[the] harbor skill invest check of %string% for %player%");
        Ext.condition(
                r,
                "can invest in harbor skill",
                c -> {
                    Optional<JobsService> s = jobs();
                    Optional<SkillId> id = skill(c, 1);
                    Optional<Player> p = c.player(0);
                    return s.isPresent()
                            && id.isPresent()
                            && p.isPresent()
                            && s.get().checkInvest(p.get().getUniqueId(), id.get())
                                    == InvestResult.OK;
                },
                "%player% can invest in harbor skill %string%",
                "%player% (can't|cannot|can not) invest in harbor skill %string%");

        Ext.await(
                r,
                "harbor invest in skill",
                new int[] {2},
                c -> {
                    Optional<JobsService> s = jobs();
                    Optional<Player> p = c.player(0);
                    Optional<SkillId> id = skill(c, 1);
                    if (s.isEmpty() || p.isEmpty() || id.isEmpty()) {
                        return null;
                    }
                    return Ext.value(s.get().invest(p.get(), id.get()).thenApply(JobsSyntax::key));
                },
                "make %player% invest in harbor skill %string% [and (store|save) [the] result in"
                        + " %-objects%]");
        Ext.await(
                r,
                "harbor reset common skills",
                new int[] {1},
                c -> {
                    Optional<JobsService> s = jobs();
                    Optional<Player> p = c.player(0);
                    if (s.isEmpty() || p.isEmpty()) {
                        return null;
                    }
                    return Ext.value(s.get().resetCommonSkills(p.get().getUniqueId()));
                },
                "make %player% reset [the] harbor common skills [and (store|save) [the] result in"
                        + " %-objects%]");
        Ext.await(
                r,
                "harbor change job line",
                new int[] {2},
                c -> {
                    Optional<JobsService> s = jobs();
                    Optional<Player> p = c.player(0);
                    Optional<JobLine> line = c.string(1).flatMap(JobsSyntax::line);
                    if (s.isEmpty() || p.isEmpty() || line.isEmpty()) {
                        return null;
                    }
                    return Ext.value(s.get().changeLine(p.get().getUniqueId(), line.get()));
                },
                "make %player% change [the] harbor job line to %string% [and (store|save) [the]"
                        + " result in %-objects%]");
    }

    private static Optional<JobLine> line(String raw) {
        return Keyed.fromKey(JobLine.class, raw.strip().toLowerCase(Locale.ROOT));
    }

    // ---------------------------------------------------------------- Admin

    private static void admin(Registrar r) {
        // mark bits: 0 common, 1 line, 2 all; 4 with refund (marks are XORed)
        Ext.await(
                r,
                "harbor admin reset skills",
                new int[] {1},
                c -> {
                    Optional<JobsService> s = jobs();
                    Optional<Player> p = c.player(0);
                    if (s.isEmpty() || p.isEmpty()) {
                        return null;
                    }
                    int mark = c.mark();
                    String scope =
                            switch (mark & 3) {
                                case 1 -> "line";
                                case 2 -> "all";
                                default -> "common";
                            };
                    return Ext.value(
                            s.get()
                                    .adminResetSkills(
                                            p.get().getUniqueId(), scope, (mark & 4) != 0));
                },
                "harbor admin reset (0:common|1:line|2:all) skills of %player% [4:with refund]"
                        + " [and (store|save) [the] result in %-objects%]");
        Ext.await(
                r,
                "harbor admin set job tier",
                new int[] {3},
                c -> {
                    Optional<JobsService> s = jobs();
                    Optional<Player> p = c.player(0);
                    int tier = c.number(1).map(Number::intValue).orElse(0);
                    Optional<JobLine> line = c.string(2).flatMap(JobsSyntax::line);
                    if (s.isEmpty()
                            || p.isEmpty()
                            || tier < 1
                            || tier > 3
                            || (c.has(2) && line.isEmpty())) {
                        return null;
                    }
                    return Ext.value(
                            s.get()
                                    .adminSetTier(
                                            p.get().getUniqueId(),
                                            JobTier.of(tier),
                                            line.orElse(null)));
                },
                "harbor admin set [the] job tier of %player% to %number% [with line %-string%]"
                        + " [and (store|save) [the] result in %-objects%]");
        Ext.effect(
                r,
                "harbor admin set job level",
                c -> {
                    Optional<JobsService> s = jobs();
                    int level = c.number(1).map(Number::intValue).orElse(0);
                    long exp = c.number(2).map(Number::longValue).orElse(0L);
                    if (s.isEmpty() || level < 1) {
                        return;
                    }
                    for (Player p : c.players(0)) {
                        s.get().adminSetLevel(p.getUniqueId(), level, exp);
                    }
                },
                "harbor admin set [the] job level of %players% to %number% [with exp[erience]"
                        + " %-number%]");
    }

    // ---------------------------------------------------------------- Events

    private static void events(Registrar r) {
        r.event(
                "Harbor Level Up",
                JobLevelUpEvent.class,
                "직업 레벨이 오른 뒤 (event-offlineplayer, event-number = 새 레벨)",
                "harbor level up");
        r.value(
                JobLevelUpEvent.class,
                OfflinePlayer.class,
                e -> Bukkit.getOfflinePlayer(e.player()));
        r.value(JobLevelUpEvent.class, Player.class, e -> Bukkit.getPlayer(e.player()));
        r.value(JobLevelUpEvent.class, Number.class, JobLevelUpEvent::level);

        r.event(
                "Harbor Job Advance",
                JobAdvancedEvent.class,
                "전직한 뒤 (event-offlineplayer, event-number = 새 단계, event-string = 계열)",
                "harbor job advance");
        r.value(
                JobAdvancedEvent.class,
                OfflinePlayer.class,
                e -> Bukkit.getOfflinePlayer(e.player()));
        r.value(JobAdvancedEvent.class, Player.class, e -> Bukkit.getPlayer(e.player()));
        r.value(JobAdvancedEvent.class, Number.class, e -> e.tier().level());
        r.value(JobAdvancedEvent.class, String.class, e -> e.line().key());

        r.event(
                "Harbor Job Exp Gain",
                JobExpGainEvent.class,
                "직업 경험치를 받기 직전 (취소 가능. event-number = 받을 양, event-string = 출처, harbor exp gain"
                        + " amount로 바꿀 수 있음)",
                "harbor job exp gain");
        Ext.playerValues(r, JobExpGainEvent.class, JobExpGainEvent::player);
        r.value(JobExpGainEvent.class, Number.class, JobExpGainEvent::amount);
        r.value(
                JobExpGainEvent.class,
                String.class,
                e -> e.source().name().toLowerCase(Locale.ROOT));
        Ext.expr(r, Number.class, "harbor exp gain amount")
                .in(JobExpGainEvent.class)
                .changer(
                        Number.class,
                        (c, delta, mode) -> {
                            if (!(c.event() instanceof JobExpGainEvent e)) {
                                return;
                            }
                            double d =
                                    delta != null
                                                    && delta.length > 0
                                                    && delta[0] instanceof Number n
                                            ? n.doubleValue()
                                            : 0;
                            if (Double.isNaN(d) || Double.isInfinite(d)) {
                                return;
                            }
                            switch (mode) {
                                case SET -> e.setAmount(d);
                                case ADD -> e.setAmount(e.amount() + d);
                                case REMOVE -> e.setAmount(e.amount() - d);
                                case DELETE, RESET -> e.setAmount(0);
                                default -> {}
                            }
                        },
                        ChangeMode.SET,
                        ChangeMode.ADD,
                        ChangeMode.REMOVE,
                        ChangeMode.DELETE,
                        ChangeMode.RESET)
                .register(
                        c ->
                                c.event() instanceof JobExpGainEvent e
                                        ? List.of(e.amount())
                                        : List.of(),
                        "[the] harbor exp gain amount");

        r.event(
                "Harbor Job Line Change",
                JobLineChangedEvent.class,
                "계열을 바꾼 뒤 (event-string = 새 계열, harbor previous job line = 이전 계열)",
                "harbor job line change");
        Ext.playerValues(r, JobLineChangedEvent.class, JobLineChangedEvent::player);
        r.value(JobLineChangedEvent.class, String.class, e -> e.to().key());
        Ext.expr(r, String.class, "harbor previous job line")
                .in(JobLineChangedEvent.class)
                .register(
                        c ->
                                c.event() instanceof JobLineChangedEvent e
                                        ? List.of(e.from().key())
                                        : List.of(),
                        "[the] harbor previous job line");

        r.event(
                "Harbor Skill Invest",
                SkillAllocatedEvent.class,
                "스킬에 투자한 뒤 (event-string = 스킬 ID, event-number = 새 등급)",
                "harbor skill (invest|allocate)");
        Ext.playerValues(r, SkillAllocatedEvent.class, SkillAllocatedEvent::player);
        r.value(SkillAllocatedEvent.class, String.class, e -> e.node().toString());
        r.value(SkillAllocatedEvent.class, Number.class, SkillAllocatedEvent::rank);

        r.event(
                "Harbor Skill Reset",
                SkillResetEvent.class,
                "스킬을 초기화한 뒤 (event-string = 범위 common·line·all, event-number = 돌려받은 물비늘)",
                "harbor skill reset");
        Ext.playerValues(r, SkillResetEvent.class, SkillResetEvent::player);
        r.value(SkillResetEvent.class, String.class, SkillResetEvent::scope);
        r.value(SkillResetEvent.class, Number.class, SkillResetEvent::refunded);

        r.event(
                "Harbor Capstone Use",
                CapstoneUsedEvent.class,
                "캡스톤을 쓴 뒤 (event-string = 캡스톤 스킬 ID)",
                "harbor capstone use");
        Ext.playerValues(r, CapstoneUsedEvent.class, CapstoneUsedEvent::player);
        r.value(CapstoneUsedEvent.class, String.class, e -> e.node().toString());
    }
}
