package wiki.creeper.harbor.core.task;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.jspecify.annotations.Nullable;

/**
 * 틱 단위로 이어지는 연출·스크립트 (컷씬, 포획 연출, 미니게임 단계, 보스 패턴 예고). Kotlin 코루틴 대신 쓴다.
 *
 * <pre>{@code
 * Sequence.of(plugin, player)
 *     .run(p -> showTitle(p))
 *     .waitTicks(20)
 *     .run(p -> moveCamera(p))
 *     .repeat(5, 10, p -> spawnParticles(p))
 *     .onCancel(p -> restore(p))
 *     .start();
 * }</pre>
 *
 * 플레이어가 나가거나 서버를 이동하면 자동으로 취소하고 onCancel을 실행한다. 메인 스레드에서만 쓴다.
 */
public final class Sequence {

    private static final Map<UUID, Set<Runner>> ACTIVE = new ConcurrentHashMap<>();

    private sealed interface Step permits Run, Wait, Repeat {}

    private record Run(Consumer<Player> action) implements Step {}

    private record Wait(int ticks) implements Step {}

    private record Repeat(int times, int intervalTicks, Consumer<Player> action) implements Step {}

    private final Plugin plugin;
    private final Player player;
    private final List<Step> steps = new ArrayList<>();
    private @Nullable Consumer<Player> onCancel;
    private @Nullable Consumer<Player> onComplete;

    private Sequence(Plugin plugin, Player player) {
        this.plugin = plugin;
        this.player = player;
    }

    public static Sequence of(Plugin plugin, Player player) {
        return new Sequence(plugin, player);
    }

    public Sequence run(Consumer<Player> action) {
        steps.add(new Run(action));
        return this;
    }

    public Sequence waitTicks(int ticks) {
        if (ticks < 1) {
            throw new IllegalArgumentException("대기는 1틱 이상이어야 합니다");
        }
        steps.add(new Wait(ticks));
        return this;
    }

    /** action을 times번, intervalTicks 간격으로 실행한다. */
    public Sequence repeat(int times, int intervalTicks, Consumer<Player> action) {
        if (times < 1 || intervalTicks < 1) {
            throw new IllegalArgumentException("반복 횟수와 간격은 1 이상이어야 합니다");
        }
        steps.add(new Repeat(times, intervalTicks, action));
        return this;
    }

    /** 중간에 취소되었을 때 원상복구. */
    public Sequence onCancel(Consumer<Player> action) {
        this.onCancel = action;
        return this;
    }

    public Sequence onComplete(Consumer<Player> action) {
        this.onComplete = action;
        return this;
    }

    public Handle start() {
        Runner runner = new Runner(List.copyOf(steps), onCancel, onComplete);
        ACTIVE.computeIfAbsent(player.getUniqueId(), k -> ConcurrentHashMap.newKeySet())
                .add(runner);
        runner.task = Bukkit.getScheduler().runTaskTimer(plugin, runner::tick, 0L, 1L);
        return runner;
    }

    /** 플레이어의 시퀀스를 모두 취소한다 (Core가 퇴장·서버 이동 때 부른다). */
    public static void cancelAll(UUID player) {
        Set<Runner> runners = ACTIVE.remove(player);
        if (runners != null) {
            runners.forEach(Runner::cancel);
        }
    }

    /** 실행 중인 시퀀스. */
    public interface Handle {
        void cancel();

        boolean isRunning();
    }

    private final class Runner implements Handle {

        private final List<Step> steps;
        private final @Nullable Consumer<Player> cancelAction;
        private final @Nullable Consumer<Player> completeAction;
        private @Nullable BukkitTask task;
        private int index;
        private int remaining = -1;
        private int repeated;
        private boolean finished;

        Runner(
                List<Step> steps,
                @Nullable Consumer<Player> cancelAction,
                @Nullable Consumer<Player> completeAction) {
            this.steps = steps;
            this.cancelAction = cancelAction;
            this.completeAction = completeAction;
        }

        void tick() {
            if (finished) {
                return;
            }
            if (!player.isOnline()) {
                cancel();
                return;
            }
            try {
                while (index < steps.size()) {
                    if (!advance(steps.get(index))) {
                        return;
                    }
                }
                finish();
                if (completeAction != null) {
                    completeAction.accept(player);
                }
            } catch (RuntimeException e) {
                plugin.getSLF4JLogger().error("시퀀스 실행 중 오류 ({})", player.getName(), e);
                cancel();
            }
        }

        /** 한 단계를 진행한다. 이번 틱에 더 진행할 수 있으면 true. */
        private boolean advance(Step step) {
            return switch (step) {
                case Run(Consumer<Player> action) -> {
                    action.accept(player);
                    index++;
                    yield true;
                }
                case Wait(int ticks) -> countDown(ticks);
                case Repeat(int times, int interval, Consumer<Player> action) -> {
                    if (remaining > 0) {
                        yield countDown(interval);
                    }
                    action.accept(player);
                    repeated++;
                    if (repeated >= times) {
                        repeated = 0;
                        remaining = -1;
                        index++;
                        yield true;
                    }
                    remaining = interval;
                    yield false;
                }
            };
        }

        /** 대기 단계: 처음 만나면 시작하고, 끝나면 다음 단계로. */
        private boolean countDown(int ticks) {
            if (remaining < 0) {
                remaining = ticks;
                return false;
            }
            remaining--;
            if (remaining > 0) {
                return false;
            }
            remaining = -1;
            if (steps.get(index) instanceof Wait) {
                index++;
            }
            return true;
        }

        @Override
        public void cancel() {
            if (finished) {
                return;
            }
            finish();
            if (cancelAction != null) {
                try {
                    cancelAction.accept(player);
                } catch (RuntimeException e) {
                    plugin.getSLF4JLogger().error("시퀀스 원상복구 중 오류 ({})", player.getName(), e);
                }
            }
        }

        private void finish() {
            finished = true;
            BukkitTask t = task;
            if (t != null) {
                t.cancel();
            }
            Set<Runner> runners = ACTIVE.get(player.getUniqueId());
            if (runners != null) {
                runners.remove(this);
            }
        }

        @Override
        public boolean isRunning() {
            return !finished;
        }
    }
}
