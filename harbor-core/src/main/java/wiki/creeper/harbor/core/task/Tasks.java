package wiki.creeper.harbor.core.task;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * Task scheduling helper. One per module; when the module is disabled, {@link #cancelAll()} cancels
 * all remaining tasks.
 *
 * <ul>
 *   <li>sync / later / timer: main thread (Bukkit scheduler)
 *   <li>async: virtual threads. Do DB, Redis and file I/O here, and apply results by handing them
 *       back via {@link #main()}
 * </ul>
 */
public final class Tasks {

    private final Plugin plugin;
    private final ExecutorService virtual = Executors.newVirtualThreadPerTaskExecutor();
    private final Executor main = this::runOnMain;

    public Tasks(Plugin plugin) {
        this.plugin = plugin;
    }

    /** Runs on the main thread on the next tick. */
    public BukkitTask sync(Runnable task) {
        return Bukkit.getScheduler().runTask(plugin, task);
    }

    public BukkitTask later(long delayTicks, Runnable task) {
        return Bukkit.getScheduler().runTaskLater(plugin, task, delayTicks);
    }

    public BukkitTask timer(long delayTicks, long periodTicks, Runnable task) {
        return Bukkit.getScheduler().runTaskTimer(plugin, task, delayTicks, periodTicks);
    }

    public CompletableFuture<Void> async(Runnable task) {
        return CompletableFuture.runAsync(task, virtual);
    }

    public <T> CompletableFuture<T> supplyAsync(Supplier<T> task) {
        return CompletableFuture.supplyAsync(task, virtual);
    }

    /** Virtual thread executor. */
    public Executor virtual() {
        return virtual;
    }

    /**
     * Main thread executor. Use it to hand results to the main thread, as in {@code
     * future.thenAcceptAsync(result -> …, tasks.main())}. Runs immediately if already on the main
     * thread, and drops the task if the plugin is disabled.
     */
    public Executor main() {
        return main;
    }

    /** Cancels all scheduled tasks of this module. */
    public void cancelAll() {
        Bukkit.getScheduler().cancelTasks(plugin);
        virtual.shutdownNow();
    }

    private void runOnMain(Runnable task) {
        if (Bukkit.isPrimaryThread()) {
            task.run();
        } else if (plugin.isEnabled()) {
            var _ = Bukkit.getScheduler().runTask(plugin, task);
        }
    }
}
