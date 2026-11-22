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
 * Tick-based chained effects and scripts (cutscenes, catch effects, minigame stages, boss pattern
 * telegraphs). Used instead of Kotlin coroutines.
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
 * Automatically cancelled, running onCancel, when the player leaves or moves servers. Main thread
 * only.
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

    /** Runs action times times, intervalTicks apart. */
    public Sequence repeat(int times, int intervalTicks, Consumer<Player> action) {
        if (times < 1 || intervalTicks < 1) {
            throw new IllegalArgumentException("반복 횟수와 간격은 1 이상이어야 합니다");
        }
        steps.add(new Repeat(times, intervalTicks, action));
        return this;
    }

    /** Rollback when cancelled midway. */
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

    /** Cancels all of a player's sequences (called by Core on quit and server transfer). */
    public static void cancelAll(UUID player) {
        Set<Runner> runners = ACTIVE.remove(player);
        if (runners != null) {
            runners.forEach(Runner::cancel);
        }
    }

    /** A running sequence. */
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

        /** Advances one step. True if more progress can be made this tick. */
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

        /** Wait step: starts when first reached and moves to the next step when done. */
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
