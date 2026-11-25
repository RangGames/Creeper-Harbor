package wiki.creeper.harbor.fishing.session;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.slf4j.Logger;
import wiki.creeper.harbor.common.redis.Channel;
import wiki.creeper.harbor.core.api.Dialogs;
import wiki.creeper.harbor.core.api.HarborApi;
import wiki.creeper.harbor.core.task.Tasks;
import wiki.creeper.harbor.fishing.data.FishingRepository;

/**
 * Anti-macro and anti-AFK (docs 20 3.4 MacroGuard). Scores are proposed values. Signals: regular
 * cast intervals +25, identical view angles +25, inhumanly fast bite reactions +20, reel/release
 * switch intervals constant to the tick +20, three hours of fishing without moving or chatting +10.
 * At 60 or above a confirmation Dialog is shown (random button order, 30 seconds); on failure or
 * timeout fishing is blocked for 5 minutes and it is recorded in fishing_flags. From the third
 * failure of the day (fishing.daily_zone) online admins on every server are alerted. No automatic
 * sanctions.
 */
final class MacroGuard {

    private static final int SAMPLES = 30;
    private static final int THRESHOLD = 60;
    private static final Duration BLOCK = Duration.ofMinutes(5);
    private static final Duration ANSWER_TIME = Duration.ofSeconds(30);

    /** Reel switch intervals needed before judging the rhythm (proposed value). */
    private static final int REEL_SAMPLES = 20;

    /**
     * At most this many distinct switch intervals (ticks) counts as "constant": a macro alternates
     * a fixed hold and a fixed release (proposed value).
     */
    private static final int REEL_DISTINCT = 2;

    private static final Duration AFK = Duration.ofHours(3);

    /** Failures per day before admins are alerted. */
    private static final int ALERT_FAILURES = 3;

    /** Permission of online admins who receive the alert (same as the Core ledger alerts). */
    static final String ALERT_PERMISSION = "harbor.admin.alerts";

    /** Cross-server admin alert (proposed channel harbor:fishing_alert). */
    record MacroAlert(String player, int failures) {}

    static final Channel<MacroAlert> ALERTS =
            new Channel<>("harbor:fishing_alert", MacroAlert.class);

    /** Per-player statistics. */
    private static final class Stats {
        final Deque<Long> castTimes = new ArrayDeque<>();
        final Deque<Long> reactions = new ArrayDeque<>();
        final Deque<String> looks = new ArrayDeque<>();
        final Deque<Integer> reelIntervals = new ArrayDeque<>();
        int score;
        Instant lastDecay = Instant.now();
        Instant blockedUntil = Instant.EPOCH;
        boolean challenging;
        final List<String> reasons = new ArrayList<>();
    }

    private final HarborApi api;
    private final Tasks tasks;
    private final Logger log;
    private final Supplier<ZoneId> dailyZone;
    private final Map<UUID, Stats> stats = new HashMap<>();

    /** Last movement or chat (epoch millis). Written from the async chat thread too. */
    private final Map<UUID, Long> activity = new ConcurrentHashMap<>();

    MacroGuard(HarborApi api, Tasks tasks, Logger log, Supplier<ZoneId> dailyZone) {
        this.api = api;
        this.tasks = tasks;
        this.log = log;
        this.dailyZone = dailyZone;
    }

    /** Subscribes to admin alerts from every server (handler hands off to the main thread). */
    void start() {
        api.bus()
                .subscribe(
                        ALERTS,
                        envelope -> {
                            MacroAlert alert = envelope.payload();
                            var _ = tasks.sync(() -> showAlert(alert));
                        });
    }

    /** Movement or chat (any thread). */
    void activity(UUID player) {
        activity.put(player, System.currentTimeMillis());
    }

    /** Reel/release switch intervals (ticks) of one finished minigame. */
    void recordReelSwitches(Player player, List<Integer> intervals) {
        if (intervals.isEmpty()) {
            return;
        }
        Stats s = stats.computeIfAbsent(player.getUniqueId(), k -> new Stats());
        for (int interval : intervals) {
            push(s.reelIntervals, interval);
        }
    }

    boolean blocked(Player player) {
        Stats s = stats.get(player.getUniqueId());
        return s != null && Instant.now().isBefore(s.blockedUntil);
    }

    void recordCast(Player player) {
        Stats s = stats.computeIfAbsent(player.getUniqueId(), k -> new Stats());
        activity.putIfAbsent(player.getUniqueId(), System.currentTimeMillis());
        decay(s);
        push(s.castTimes, System.nanoTime());
        Location loc = FishingManager.loc(player);
        push(s.looks, loc.getYaw() + "/" + loc.getPitch());
        evaluate(player, s);
    }

    void recordReaction(Player player, long nanos) {
        Stats s = stats.computeIfAbsent(player.getUniqueId(), k -> new Stats());
        push(s.reactions, nanos);
    }

    int score(UUID player) {
        Stats s = stats.get(player);
        return s == null ? 0 : s.score;
    }

    List<String> reasons(UUID player) {
        Stats s = stats.get(player);
        return s == null ? List.of() : List.copyOf(s.reasons);
    }

    void reset(UUID player) {
        stats.remove(player);
        activity.remove(player);
    }

    private static <T> void push(Deque<T> deque, T value) {
        deque.addLast(value);
        while (deque.size() > SAMPLES) {
            deque.pollFirst();
        }
    }

    /** −5 every 10 minutes. */
    private static void decay(Stats s) {
        long steps = Duration.between(s.lastDecay, Instant.now()).toMinutes() / 10;
        if (steps > 0) {
            s.score = (int) Math.max(0, s.score - steps * 5);
            s.lastDecay = Instant.now();
        }
    }

    private void evaluate(Player player, Stats s) {
        int score = 0;
        List<String> reasons = new ArrayList<>();
        if (s.castTimes.size() >= SAMPLES && intervalStdDevMillis(s.castTimes) < 150) {
            score += 25;
            reasons.add("cast_interval");
        }
        if (s.looks.size() >= 20 && s.looks.stream().distinct().count() == 1) {
            score += 25;
            reasons.add("same_look");
        }
        if (s.reactions.stream().filter(n -> n < Duration.ofMillis(120).toNanos()).count() >= 10) {
            score += 20;
            reasons.add("fast_reaction");
        }
        if (s.reelIntervals.size() >= REEL_SAMPLES
                && s.reelIntervals.stream().distinct().count() <= REEL_DISTINCT) {
            score += 20;
            reasons.add("reel_rhythm");
        }
        Long last = activity.get(player.getUniqueId());
        if (last != null && System.currentTimeMillis() - last >= AFK.toMillis()) {
            score += 10;
            reasons.add("no_activity");
        }
        s.score = Math.max(s.score, score);
        s.reasons.clear();
        s.reasons.addAll(reasons);
        if (s.score >= THRESHOLD && !s.challenging) {
            challenge(player, s);
        }
    }

    private static double intervalStdDevMillis(Deque<Long> times) {
        List<Long> list = new ArrayList<>(times);
        List<Double> intervals = new ArrayList<>();
        for (int i = 1; i < list.size(); i++) {
            intervals.add((list.get(i) - list.get(i - 1)) / 1_000_000.0);
        }
        double mean = intervals.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double variance =
                intervals.stream().mapToDouble(v -> (v - mean) * (v - mean)).average().orElse(0);
        return Math.sqrt(variance);
    }

    /** A simple multiple-choice question. The correct button position is random. */
    private void challenge(Player player, Stats s) {
        s.challenging = true;
        List<Integer> order = new ArrayList<>(List.of(0, 1, 2));
        Collections.shuffle(order);
        List<Dialogs.Choice> choices = new ArrayList<>();
        for (int index : order) {
            choices.add(
                    new Dialogs.Choice(
                            api.messages().get("fishing.macro.answer." + index),
                            Component.empty()));
        }
        boolean[] answered = {false};
        api.dialogs()
                .choose(
                        player,
                        api.messages().get("fishing.macro.title"),
                        List.of(api.messages().get("fishing.macro.question")),
                        choices,
                        3,
                        picked -> {
                            answered[0] = true;
                            s.challenging = false;
                            if (order.get(picked) == 0) {
                                s.score = 0;
                                s.castTimes.clear();
                                s.reactions.clear();
                                s.looks.clear();
                                s.reelIntervals.clear();
                                api.messages().send(player, "fishing.macro.passed");
                            } else {
                                fail(player, s, "wrong_answer");
                            }
                        });
        var _ =
                tasks.later(
                        ANSWER_TIME.toSeconds() * 20,
                        () -> {
                            if (!answered[0]) {
                                s.challenging = false;
                                fail(player, s, "timeout");
                            }
                        });
    }

    private void fail(Player player, Stats s, String action) {
        s.blockedUntil = Instant.now().plus(BLOCK);
        api.messages().send(player, "fishing.macro.blocked");
        UUID uuid = player.getUniqueId();
        int score = s.score;
        String reasons = String.join(",", s.reasons);
        log.info("낚시 매크로 의심: {} 점수 {} ({}) {}", player.getName(), score, reasons, action);
        String name = player.getName();
        Instant dayStart = LocalDate.now(dailyZone.get()).atStartOfDay(dailyZone.get()).toInstant();
        var _ =
                api.database()
                        .async(
                                c -> {
                                    FishingRepository.insertFlag(c, uuid, score, reasons, action);
                                    return FishingRepository.countFailuresSince(c, uuid, dayStart);
                                })
                        .thenAccept(
                                failures -> {
                                    if (failures >= ALERT_FAILURES) {
                                        api.bus().publish(ALERTS, new MacroAlert(name, failures));
                                    }
                                })
                        .exceptionally(
                                e -> {
                                    log.warn("매크로 의심 기록 실패: {}", String.valueOf(e));
                                    return null;
                                });
    }

    /** Admin alert: day failures reached the threshold (logged and shown to online admins). */
    private void showAlert(MacroAlert alert) {
        log.warn("낚시 매크로 확인 오늘 {}회 실패: {}", alert.failures(), alert.player());
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (online.hasPermission(ALERT_PERMISSION)) {
                api.messages()
                        .send(
                                online,
                                "fishing.macro.admin_alert",
                                Placeholder.unparsed("player", alert.player()),
                                Placeholder.unparsed("count", String.valueOf(alert.failures())));
            }
        }
    }
}
