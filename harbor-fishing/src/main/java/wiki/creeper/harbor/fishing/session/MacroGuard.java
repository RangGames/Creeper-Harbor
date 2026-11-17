package wiki.creeper.harbor.fishing.session;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.slf4j.Logger;
import wiki.creeper.harbor.core.api.Dialogs;
import wiki.creeper.harbor.core.api.HarborApi;
import wiki.creeper.harbor.core.task.Tasks;
import wiki.creeper.harbor.fishing.data.FishingRepository;

/**
 * 매크로 · AFK 방지 (docs 20 3.4 MacroGuard). 점수는 제안값. 60 이상이면 확인 Dialog(버튼 순서 무작위, 30초)를 띄우고, 실패·시간 초과면
 * 5분 동안 낚시를 막고 fishing_flags에 남긴다. 자동 제재는 하지 않는다.
 */
final class MacroGuard {

    private static final int SAMPLES = 30;
    private static final int THRESHOLD = 60;
    private static final Duration BLOCK = Duration.ofMinutes(5);
    private static final Duration ANSWER_TIME = Duration.ofSeconds(30);

    /** 플레이어별 통계. */
    private static final class Stats {
        final Deque<Long> castTimes = new ArrayDeque<>();
        final Deque<Long> reactions = new ArrayDeque<>();
        final Deque<String> looks = new ArrayDeque<>();
        int score;
        Instant lastDecay = Instant.now();
        Instant blockedUntil = Instant.EPOCH;
        boolean challenging;
        final List<String> reasons = new ArrayList<>();
    }

    private final HarborApi api;
    private final Tasks tasks;
    private final Logger log;
    private final Map<UUID, Stats> stats = new HashMap<>();

    MacroGuard(HarborApi api, Tasks tasks, Logger log) {
        this.api = api;
        this.tasks = tasks;
        this.log = log;
    }

    boolean blocked(Player player) {
        Stats s = stats.get(player.getUniqueId());
        return s != null && Instant.now().isBefore(s.blockedUntil);
    }

    void recordCast(Player player) {
        Stats s = stats.computeIfAbsent(player.getUniqueId(), k -> new Stats());
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
    }

    private static <T> void push(Deque<T> deque, T value) {
        deque.addLast(value);
        while (deque.size() > SAMPLES) {
            deque.pollFirst();
        }
    }

    /** 10분마다 −5. */
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

    /** 간단한 선택 문제. 정답 버튼 위치는 무작위. */
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
        var _ =
                api.database()
                        .asyncVoid(
                                c -> FishingRepository.insertFlag(c, uuid, score, reasons, action));
    }
}
