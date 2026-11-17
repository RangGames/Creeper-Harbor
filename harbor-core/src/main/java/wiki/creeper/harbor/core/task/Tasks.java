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
 * 작업 예약 도우미. 모듈마다 하나씩 두고, 모듈이 꺼질 때 {@link #cancelAll()}로 남은 작업을 모두 취소한다.
 *
 * <ul>
 *   <li>sync / later / timer: 메인 스레드 (Bukkit 스케줄러)
 *   <li>async: 가상 스레드. DB·Redis·파일 입출력은 여기서 하고, 결과 반영은 {@link #main()}으로 돌려서 한다
 * </ul>
 */
public final class Tasks {

    private final Plugin plugin;
    private final ExecutorService virtual = Executors.newVirtualThreadPerTaskExecutor();
    private final Executor main = this::runOnMain;

    public Tasks(Plugin plugin) {
        this.plugin = plugin;
    }

    /** 다음 틱에 메인 스레드에서 실행한다. */
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

    /** 가상 스레드 실행기. */
    public Executor virtual() {
        return virtual;
    }

    /**
     * 메인 스레드 실행기. {@code future.thenAcceptAsync(result -> …, tasks.main())}처럼 결과를 메인 스레드로 넘길 때 쓴다. 이미 메인
     * 스레드면 바로 실행하고, 플러그인이 꺼졌으면 버린다.
     */
    public Executor main() {
        return main;
    }

    /** 이 모듈의 예약 작업을 모두 취소한다. */
    public void cancelAll() {
        Bukkit.getScheduler().cancelTasks(plugin);
        virtual.shutdownNow();
    }

    private void runOnMain(Runnable task) {
        if (Bukkit.isPrimaryThread()) {
            task.run();
        } else if (plugin.isEnabled()) {
            var unused = Bukkit.getScheduler().runTask(plugin, task);
        }
    }
}
