package wiki.creeper.harbor.skript.bridge;

import ch.njol.skript.util.Date;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import wiki.creeper.harbor.season.api.HallOfFameEntry;
import wiki.creeper.harbor.season.api.HallOfFameService;
import wiki.creeper.harbor.season.api.League;
import wiki.creeper.harbor.season.api.RankEntry;
import wiki.creeper.harbor.season.api.RankingService;
import wiki.creeper.harbor.season.api.SeasonClock;
import wiki.creeper.harbor.season.api.TitleId;
import wiki.creeper.harbor.season.api.TitleService;
import wiki.creeper.harbor.season.api.WeeklyLimitService;
import wiki.creeper.harbor.season.api.event.DailyResetEvent;
import wiki.creeper.harbor.season.api.event.HallOfFameUpdatedEvent;
import wiki.creeper.harbor.season.api.event.PlayerSeasonEnteredEvent;
import wiki.creeper.harbor.season.api.event.SeasonWeekChangedEvent;
import wiki.creeper.harbor.season.api.event.TitleEquippedEvent;
import wiki.creeper.harbor.season.api.event.TitleGrantedEvent;
import wiki.creeper.harbor.season.api.event.WeeklyResetEvent;
import wiki.creeper.harbor.skript.lambda.Args;
import wiki.creeper.harbor.skript.lambda.Ctx;
import wiki.creeper.harbor.skript.lambda.Ext;
import wiki.creeper.harbor.skript.lambda.Registrar;
import wiki.creeper.harbor.skript.lambda.Spec;

/**
 * When Harbor-Season is enabled: season clock (week, name, dates, state), titles (ownership,
 * equipping, display, admin grant and revoke), weekly limits, rankings, the hall of fame and every
 * Season event.
 */
public final class SeasonSyntax {

    private SeasonSyntax() {}

    private static <T> Optional<T> service(Class<T> type) {
        return Shared.api().service(type);
    }

    private static Optional<SeasonClock> clock() {
        return service(SeasonClock.class);
    }

    private static Optional<TitleService> titles() {
        return service(TitleService.class);
    }

    public static void register(Registrar r) {
        clockSyntax(r);
        titleSyntax(r);
        limitSyntax(r);
        rankingSyntax(r);
        events(r);
    }

    // ---------------------------------------------------------------- Clock

    private static void clockSyntax(Registrar r) {
        r.number(
                Spec.single("harbor season week"),
                (e, x) -> clock().map(c -> List.<Number>of(c.week())).orElse(List.of()),
                "[the] harbor season week");
        r.text(
                Spec.single("harbor season name"),
                (e, x) -> clock().map(c -> List.of(c.displayName())).orElse(List.of()),
                "[the] harbor season name");
        r.number(
                Spec.single("harbor season weeks"),
                (e, x) -> clock().map(c -> List.<Number>of(c.weeks())).orElse(List.of()),
                "[the] harbor season (length|weeks)");
        r.number(
                Spec.single("harbor season week shift"),
                (e, x) -> clock().map(c -> List.<Number>of(c.weekShiftHours())).orElse(List.of()),
                "[the] harbor season week shift [hours]");
        r.text(
                Spec.single("harbor season state"),
                (e, x) ->
                        clock().map(c -> List.of(c.state().name().toLowerCase(Locale.ROOT)))
                                .orElse(List.of()),
                "[the] harbor season (state|phase)");
        r.condition(
                Spec.single("harbor season extended"),
                (e, x) -> clock().map(SeasonClock::extended).orElse(false),
                "[the] harbor season is (extended|in overtime)",
                "[the] harbor season (isn't|is not) (extended|in overtime)");
        // pattern 0 start, 1 end, 2 next daily reset, 3 next weekly reset, 4 start of week n
        Ext.expr(r, Date.class, "harbor season date")
                .single()
                .register(
                        c ->
                                clock().flatMap(
                                                clock ->
                                                        switch (c.pattern()) {
                                                            case 0 ->
                                                                    Optional.of(
                                                                            Shared.date(
                                                                                    clock
                                                                                            .startsAt()));
                                                            case 1 ->
                                                                    Optional.of(
                                                                            Shared.date(
                                                                                    clock
                                                                                            .endsAt()));
                                                            case 2 ->
                                                                    Optional.of(
                                                                            Shared.date(
                                                                                    clock
                                                                                            .nextDailyReset()));
                                                            case 3 ->
                                                                    Optional.of(
                                                                            Shared.date(
                                                                                    clock
                                                                                            .nextWeeklyReset()));
                                                            default ->
                                                                    c.number(0)
                                                                            .map(Number::intValue)
                                                                            .filter(w -> w >= 1)
                                                                            .map(
                                                                                    w ->
                                                                                            Shared
                                                                                                    .date(
                                                                                                            clock
                                                                                                                    .weekStart(
                                                                                                                            w)));
                                                        })
                                        .map(List::of)
                                        .orElse(List.of()),
                        "[the] harbor season start [date]",
                        "[the] harbor season end [date]",
                        "[the] next harbor daily reset [date]",
                        "[the] next harbor weekly reset [date]",
                        "[the] harbor season week %number% start [date]");
    }

    // ---------------------------------------------------------------- Titles

    private static Optional<TitleId> title(Ctx c, int index) {
        return c.string(index).flatMap(TitleId::tryOf);
    }

    private static void titleSyntax(Registrar r) {
        r.condition(
                Spec.of("harbor title owned"),
                (e, x) -> {
                    Optional<TitleId> title = Args.string(e, x, 1).flatMap(TitleId::tryOf);
                    Optional<TitleService> t = titles();
                    return title.isPresent()
                            && t.isPresent()
                            && Args.all(e, x, p -> t.get().has(p.getUniqueId(), title.get()));
                },
                "%players% (has|have) harbor title %string%",
                "%players% (doesn't|does not|don't|do not) have harbor title %string%");
        r.text(
                Spec.of("equipped harbor title"),
                (e, x) ->
                        titles().map(
                                        t ->
                                                Args.perPlayer(
                                                        e,
                                                        x,
                                                        p ->
                                                                t.equipped(p.getUniqueId())
                                                                        .map(TitleId::toString)
                                                                        .orElse(null)))
                                .orElse(List.of()),
                "[the] equipped harbor title of %players%");
        r.effect(
                Spec.of("equip harbor title"),
                (e, x) -> {
                    Optional<TitleId> title = Args.string(e, x, 0).flatMap(TitleId::tryOf);
                    Optional<TitleService> t = titles();
                    if (title.isEmpty() || t.isEmpty()) {
                        return;
                    }
                    for (Player p : Args.players(e, x, 1)) {
                        var _ = t.get().equip(p.getUniqueId(), title.get());
                    }
                },
                "equip harbor title %string% (for|to|on) %players%");
        r.effect(
                Spec.of("unequip harbor title"),
                (e, x) ->
                        titles().ifPresent(
                                        t -> {
                                            for (Player p : Args.players(e, x, 0)) {
                                                var _ = t.equip(p.getUniqueId(), null);
                                            }
                                        }),
                "unequip harbor title (of|for|from) %players%");

        Ext.expr(r, String.class, "harbor title display")
                .single()
                .register(
                        c ->
                                titles().flatMap(
                                                t ->
                                                        title(c, 0)
                                                                .map(
                                                                        id ->
                                                                                Shared.legacy(
                                                                                        t.display(
                                                                                                id))))
                                        .map(List::of)
                                        .orElse(List.of()),
                        "[the] harbor title (display [name]|name) of %string%");
        Ext.expr(r, String.class, "equipped harbor title display")
                .register(
                        c ->
                                titles().map(
                                                t ->
                                                        c.players(0).stream()
                                                                .flatMap(
                                                                        p ->
                                                                                t
                                                                                        .equippedDisplay(
                                                                                                p
                                                                                                        .getUniqueId())
                                                                                        .stream())
                                                                .map(Shared::legacy)
                                                                .toList())
                                        .orElse(List.of()),
                        "[the] equipped harbor title (display [name]|name) of %players%");

        Ext.await(
                r,
                "harbor admin grant title",
                new int[] {2},
                c -> {
                    Optional<TitleService> t = titles();
                    Optional<TitleId> id = title(c, 0);
                    if (t.isEmpty() || id.isEmpty()) {
                        return null;
                    }
                    List<CompletableFuture<String>> results = new ArrayList<>();
                    for (OfflinePlayer p : c.offline(1)) {
                        results.add(
                                t.get()
                                        .grant(p.getUniqueId(), id.get(), Shared.ref())
                                        .thenApply(g -> g.name().toLowerCase(Locale.ROOT)));
                    }
                    return Ext.one(Shared.all(results));
                },
                "harbor admin grant title %string% to %offlineplayers% [and (store|save) [the]"
                        + " result[s] in %-objects%]");
        Ext.await(
                r,
                "harbor admin revoke title",
                new int[] {2},
                c -> {
                    Optional<TitleService> t = titles();
                    Optional<TitleId> id = title(c, 0);
                    if (t.isEmpty() || id.isEmpty()) {
                        return null;
                    }
                    List<CompletableFuture<Boolean>> results = new ArrayList<>();
                    for (OfflinePlayer p : c.offline(1)) {
                        results.add(t.get().revoke(p.getUniqueId(), id.get()));
                    }
                    return Ext.one(Shared.all(results));
                },
                "harbor admin revoke title %string% from %offlineplayers% [and (store|save) [the]"
                        + " result[s] in %-objects%]");
        Ext.await(
                r,
                "harbor load titles",
                new int[] {1},
                c -> {
                    Optional<TitleService> t = titles();
                    Optional<OfflinePlayer> p = c.offlinePlayer(0);
                    if (t.isEmpty() || p.isEmpty()) {
                        return null;
                    }
                    return Ext.one(
                            t.get()
                                    .list(p.get().getUniqueId())
                                    .thenApply(
                                            list ->
                                                    list.stream()
                                                            .map(o -> o.title().toString())
                                                            .toList()));
                },
                "harbor load [owned] titles of %offlineplayer% into %objects%");
        Ext.await(
                r,
                "harbor check title",
                new int[] {2},
                c -> {
                    Optional<TitleService> t = titles();
                    Optional<OfflinePlayer> p = c.offlinePlayer(0);
                    Optional<TitleId> id = title(c, 1);
                    if (t.isEmpty() || p.isEmpty() || id.isEmpty()) {
                        return null;
                    }
                    return Ext.value(t.get().hasAsync(p.get().getUniqueId(), id.get()));
                },
                "harbor check [if] %offlineplayer% (has|owns) title %string% and (store|save)"
                        + " [the] result in %objects%");
    }

    // ---------------------------------------------------------------- Weekly limits

    private static Optional<String> limitKey(Ctx c, int index) {
        return c.string(index)
                .map(s -> s.strip().toLowerCase(Locale.ROOT))
                .filter(k -> WeeklyLimitService.KEY.matcher(k).matches());
    }

    private static void limitSyntax(Registrar r) {
        r.number(
                Spec.single("harbor weekly limit week"),
                (e, x) ->
                        service(WeeklyLimitService.class)
                                .map(s -> List.<Number>of(s.currentWeek()))
                                .orElse(List.of()),
                "[the] harbor weekly limit week");
        Ext.await(
                r,
                "harbor load weekly limit",
                new int[] {3, 4},
                c -> {
                    Optional<WeeklyLimitService> s = service(WeeklyLimitService.class);
                    Optional<String> key = limitKey(c, 0);
                    Optional<OfflinePlayer> p = c.offlinePlayer(1);
                    int limit = c.number(2).map(Number::intValue).orElse(0);
                    if (s.isEmpty() || key.isEmpty() || p.isEmpty() || limit < 1) {
                        return null;
                    }
                    return s.get()
                            .remaining(p.get().getUniqueId(), key.get(), limit)
                            .thenApply(
                                    res ->
                                            new List<?>[] {
                                                List.of((long) res.remaining()),
                                                List.of((long) res.used())
                                            });
                },
                "harbor load weekly limit %string% of %offlineplayer% with limit %number% into"
                        + " %objects% [with used count in %-objects%]");
        Ext.await(
                r,
                "harbor consume weekly limit",
                new int[] {3},
                c -> {
                    Optional<WeeklyLimitService> s = service(WeeklyLimitService.class);
                    Optional<String> key = limitKey(c, 0);
                    Optional<OfflinePlayer> p = c.offlinePlayer(1);
                    int limit = c.number(2).map(Number::intValue).orElse(0);
                    if (s.isEmpty() || key.isEmpty() || p.isEmpty() || limit < 1) {
                        return null;
                    }
                    return Ext.value(
                            s.get()
                                    .tryConsume(p.get().getUniqueId(), key.get(), limit)
                                    .thenApply(res -> res.consumed()));
                },
                "harbor consume weekly limit %string% of %offlineplayer% with limit %number% [and"
                        + " (store|save) [the] result in %-objects%]");
        Ext.await(
                r,
                "harbor refund weekly limit",
                new int[] {3},
                c -> {
                    Optional<WeeklyLimitService> s = service(WeeklyLimitService.class);
                    Optional<String> key = limitKey(c, 0);
                    Optional<OfflinePlayer> p = c.offlinePlayer(1);
                    if (s.isEmpty() || key.isEmpty() || p.isEmpty()) {
                        return null;
                    }
                    UUID uuid = p.get().getUniqueId();
                    Optional<Number> week = c.number(2);
                    return Ext.value(
                            week.isPresent()
                                    ? s.get().refund(uuid, key.get(), week.get().intValue())
                                    : s.get().refund(uuid, key.get()));
                },
                "harbor refund weekly limit %string% of %offlineplayer% [in week %-number%] [and"
                        + " (store|save) [the] result in %-objects%]");
    }

    // ---------------------------------------------------------------- Rankings · hall of fame

    private static League league(Ctx c, int index) {
        return c.string(index).flatMap(s -> League.parse(s.strip())).orElse(League.ALL);
    }

    private static List<RankEntry> entries(Ctx c, int boardAt, int leagueAt) {
        Optional<RankingService> s = service(RankingService.class);
        Optional<String> board = c.string(boardAt).map(String::strip);
        if (s.isEmpty() || board.isEmpty()) {
            return List.of();
        }
        return s.get().board(board.get(), league(c, leagueAt)).entries();
    }

    private static void rankingSyntax(Registrar r) {
        // pattern 0 rank, 1 score
        Ext.expr(r, Number.class, "harbor ranking of player")
                .register(
                        c -> {
                            Optional<RankingService> s = service(RankingService.class);
                            Optional<String> board = c.string(1).map(String::strip);
                            if (s.isEmpty() || board.isEmpty()) {
                                return List.of();
                            }
                            League league = league(c, 2);
                            List<Number> out = new ArrayList<>();
                            for (Player p : c.players(0)) {
                                s.get()
                                        .rankOf(p.getUniqueId(), board.get(), league)
                                        .ifPresent(
                                                e ->
                                                        out.add(
                                                                c.pattern() == 0
                                                                        ? e.rank()
                                                                        : e.value()));
                            }
                            return out;
                        },
                        "[the] harbor rank of %players% (on|in) [board] %string% [in league"
                                + " %-string%]",
                        "[the] harbor ranking score of %players% (on|in) [board] %string% [in"
                                + " league %-string%]");
        Ext.expr(r, String.class, "harbor ranking names")
                .list()
                .register(
                        c -> entries(c, 0, 1).stream().map(RankEntry::name).toList(),
                        "[the] harbor ranking names (of|on|in) [board] %string% [in league"
                                + " %-string%]");
        Ext.expr(r, Number.class, "harbor ranking scores")
                .list()
                .register(
                        c -> entries(c, 0, 1).stream().map(e -> (Number) e.value()).toList(),
                        "[the] harbor ranking scores (of|on|in) [board] %string% [in league"
                                + " %-string%]");
        Ext.expr(r, OfflinePlayer.class, "harbor ranking players")
                .list()
                .register(
                        c -> {
                            List<OfflinePlayer> out = new ArrayList<>();
                            for (RankEntry e : entries(c, 0, 1)) {
                                UUID uuid = e.uuid();
                                if (uuid != null) {
                                    out.add(Shared.offline(uuid));
                                }
                            }
                            return out;
                        },
                        "[the] harbor ranking players (of|on|in) [board] %string% [in league"
                                + " %-string%]");
        Ext.expr(r, String.class, "harbor ranking boards")
                .list()
                .register(
                        c ->
                                service(RankingService.class)
                                        .map(s -> s.boards().stream().map(b -> b.id()).toList())
                                        .orElse(List.of()),
                        "[all] [of] [the] harbor ranking boards");
        Ext.await(
                r,
                "harbor admin refresh rankings",
                new int[] {0},
                c -> service(RankingService.class).map(s -> Ext.value(s.refreshNow())).orElse(null),
                "harbor admin refresh rankings [and (store|save) [the] result in %-objects%]");

        Ext.await(
                r,
                "harbor load hall of fame",
                new int[] {2, 3, 4},
                c -> {
                    Optional<HallOfFameService> s = service(HallOfFameService.class);
                    Optional<String> category = c.string(0).map(String::strip);
                    Optional<Integer> season =
                            c.number(1)
                                    .map(Number::intValue)
                                    .or(() -> clock().map(k -> k.current().value()));
                    if (s.isEmpty() || category.isEmpty() || season.isEmpty()) {
                        return null;
                    }
                    return s.get()
                            .entries(season.get(), category.get())
                            .thenApply(
                                    list ->
                                            new List<?>[] {
                                                list.stream()
                                                        .map(HallOfFameEntry::nameAtTime)
                                                        .toList(),
                                                list.stream().map(e -> (Object) e.value()).toList(),
                                                list.stream().map(HallOfFameEntry::subject).toList()
                                            });
                },
                "harbor load hall of fame %string% [of season %-number%] into %objects% [with"
                        + " values in %-objects%] [with subjects in %-objects%]");
    }

    // ---------------------------------------------------------------- Events

    private static void events(Registrar r) {
        r.event(
                "Harbor Daily Reset",
                DailyResetEvent.class,
                "매일 초기화 시각 (서버마다 따로. event-string = 새 게임 날짜 yyyy-mm-dd)",
                "harbor daily reset");
        r.value(DailyResetEvent.class, String.class, e -> e.gameDay().toString());

        r.event(
                "Harbor Weekly Reset",
                WeeklyResetEvent.class,
                "매주 초기화 시각 (서버마다 따로. event-number = 초기화 직후 주차)",
                "harbor weekly reset");
        r.value(WeeklyResetEvent.class, Number.class, WeeklyResetEvent::week);

        r.event(
                "Harbor Season Week Change",
                SeasonWeekChangedEvent.class,
                "시즌 주차가 바뀐 첫 틱 (event-number = 새 주차, harbor previous season week = 이전 주차)",
                "harbor season week change");
        r.value(SeasonWeekChangedEvent.class, Number.class, SeasonWeekChangedEvent::week);
        Ext.expr(r, Number.class, "harbor previous season week")
                .in(SeasonWeekChangedEvent.class)
                .register(
                        c ->
                                c.event() instanceof SeasonWeekChangedEvent e
                                        ? List.of(e.previousWeek())
                                        : List.of(),
                        "[the] harbor previous season week");

        r.event(
                "Harbor Title Grant",
                TitleGrantedEvent.class,
                "칭호를 새로 얻은 뒤 (event-offlineplayer, event-string = 칭호 ID)",
                "harbor title grant");
        Ext.playerValues(r, TitleGrantedEvent.class, TitleGrantedEvent::player);
        r.value(TitleGrantedEvent.class, String.class, e -> e.title().toString());

        r.event(
                "Harbor Title Equip",
                TitleEquippedEvent.class,
                "장착 칭호가 바뀐 뒤 (event-string = 칭호 ID, 해제면 값 없음)",
                "harbor title equip");
        Ext.playerValues(r, TitleEquippedEvent.class, TitleEquippedEvent::player);
        r.value(
                TitleEquippedEvent.class,
                String.class,
                e -> {
                    TitleId title = e.title();
                    return title == null ? null : title.toString();
                });

        r.event(
                "Harbor Season Enter",
                PlayerSeasonEnteredEvent.class,
                "새 시즌 첫 접속 정리가 끝난 뒤 (event-player, event-number = 시즌 번호)",
                "harbor season enter");
        r.value(PlayerSeasonEnteredEvent.class, Player.class, PlayerSeasonEnteredEvent::player);
        r.value(PlayerSeasonEnteredEvent.class, Number.class, e -> e.season().value());
        // pattern 0 previous season, 1 removed stacks
        Ext.expr(r, Number.class, "harbor season enter value")
                .in(PlayerSeasonEnteredEvent.class)
                .register(
                        c ->
                                c.event() instanceof PlayerSeasonEnteredEvent e
                                        ? List.of(
                                                c.pattern() == 0
                                                        ? e.previousSeason()
                                                        : e.removedStacks())
                                        : List.of(),
                        "[the] harbor previous season",
                        "[the] harbor removed stack count");

        r.event(
                "Harbor Hall Of Fame Update",
                HallOfFameUpdatedEvent.class,
                "명예의 전당 잠정 1위가 바뀐 뒤 (event-offlineplayer, event-string = 부문, event-number = 기록"
                        + " 값)",
                "harbor hall of fame update");
        r.value(
                HallOfFameUpdatedEvent.class,
                OfflinePlayer.class,
                e -> Shared.offline(e.entry().player()));
        r.value(
                HallOfFameUpdatedEvent.class,
                Player.class,
                e -> org.bukkit.Bukkit.getPlayer(e.entry().player()));
        r.value(HallOfFameUpdatedEvent.class, String.class, e -> e.entry().category());
        r.value(HallOfFameUpdatedEvent.class, Number.class, e -> e.entry().value());
        Ext.expr(r, String.class, "harbor hall of fame subject")
                .in(HallOfFameUpdatedEvent.class)
                .register(
                        c ->
                                c.event() instanceof HallOfFameUpdatedEvent e
                                        ? List.of(e.entry().subject())
                                        : List.of(),
                        "[the] harbor hall of fame subject");
        Ext.expr(r, OfflinePlayer.class, "harbor hall of fame previous holder")
                .in(HallOfFameUpdatedEvent.class)
                .register(
                        c -> {
                            if (c.event() instanceof HallOfFameUpdatedEvent e) {
                                HallOfFameEntry prev = e.previous();
                                if (prev != null) {
                                    return List.of(Shared.offline(prev.player()));
                                }
                            }
                            return List.of();
                        },
                        "[the] harbor hall of fame previous (holder|leader)");
    }
}
