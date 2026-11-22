package wiki.creeper.harbor.wildlife.runtime;

import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import wiki.creeper.harbor.wildlife.sim.Frame;
import wiki.creeper.harbor.wildlife.sim.PlayerSnap;
import wiki.creeper.harbor.wildlife.sim.SimWorld;

/**
 * Simulation thread ('17-3' 9.4 thread stage 2). One dedicated platform thread
 * (harbor-wildlife-sim; compute-heavy, so not a virtual thread) simulates every fish each period
 * (100ms) and swaps in the output frame ({@link Frame}) (double buffer: main reads only the last
 * completed frame). Requests from the main thread are queued ({@link #submit}) and run at the start
 * of the next step.
 */
final class SimEngine implements Runnable {

    private final SimWorld world;
    private final Logger log;
    private final Queue<Consumer<SimWorld>> queue = new ConcurrentLinkedQueue<>();
    private volatile List<PlayerSnap> players = List.of();
    private volatile Frame latest = Frame.EMPTY;
    private volatile boolean running;
    private volatile long periodNanos;
    private final AtomicLong maxNanos = new AtomicLong();
    private final AtomicLong sumNanos = new AtomicLong();
    private final AtomicLong samples = new AtomicLong();
    private @Nullable Thread thread;

    SimEngine(SimWorld world, Logger log, int periodMillis) {
        this.world = world;
        this.log = log;
        this.periodNanos = Math.max(20, periodMillis) * 1_000_000L;
    }

    void start() {
        if (running) {
            return;
        }
        running = true;
        thread = Thread.ofPlatform().name("harbor-wildlife-sim").daemon(true).start(this);
    }

    void stop() {
        running = false;
        Thread t = thread;
        if (t != null) {
            LockSupport.unpark(t);
            try {
                t.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        thread = null;
    }

    void period(int millis) {
        periodNanos = Math.max(20, millis) * 1_000_000L;
    }

    /** Request to run on the simulation thread (at the start of the next step). */
    void submit(Consumer<SimWorld> command) {
        queue.add(command);
    }

    void players(List<PlayerSnap> snapshot) {
        players = List.copyOf(snapshot);
    }

    Frame latest() {
        return latest;
    }

    /** Recent compute time stats (milliseconds): average, max. Reading resets them. */
    double[] takeStats() {
        long n = samples.getAndSet(0);
        long sum = sumNanos.getAndSet(0);
        double avg = n == 0 ? 0 : sum / (double) n / 1e6;
        double max = maxNanos.getAndSet(0) / 1e6;
        return new double[] {avg, max};
    }

    @Override
    public void run() {
        long next = System.nanoTime();
        while (running) {
            long start = System.nanoTime();
            Consumer<SimWorld> cmd;
            while ((cmd = queue.poll()) != null) {
                try {
                    cmd.accept(world);
                } catch (RuntimeException e) {
                    log.warn("야생 개체 요청 처리 실패", e);
                }
            }
            long period = periodNanos;
            try {
                Frame frame = world.step(period / 1e9, players, start);
                latest = frame;
                long took = frame.computeNanos();
                sumNanos.addAndGet(took);
                samples.incrementAndGet();
                maxNanos.accumulateAndGet(took, Math::max);
            } catch (RuntimeException e) {
                log.warn("야생 개체 계산 실패", e);
            }
            next += period;
            long wait = next - System.nanoTime();
            if (wait > 0) {
                LockSupport.parkNanos(wait);
            } else {
                next = System.nanoTime(); // if behind, don't catch up; resume from the next period
            }
        }
    }
}
