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
 * 계산 스레드 ('17-3' 9.4 스레드 2단계). 전용 플랫폼 스레드 하나(harbor-wildlife-sim, 계산 위주라 가상 스레드를 쓰지 않음)가
 * 주기(100ms)마다 모든 개체를 계산하고 출력 면({@link Frame})을 바꿔 끼운다 (이중 버퍼: 메인은 마지막 완성 면만 읽는다). 메인 스레드의 요청은
 * 줄({@link #submit})로 받아 다음 주기 시작에 실행한다.
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

    /** 계산 스레드에서 실행할 요청 (다음 주기 시작). */
    void submit(Consumer<SimWorld> command) {
        queue.add(command);
    }

    void players(List<PlayerSnap> snapshot) {
        players = List.copyOf(snapshot);
    }

    Frame latest() {
        return latest;
    }

    /** 최근 계산 시간 통계 (밀리초): 평균, 최대. 읽으면 초기화한다. */
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
                next = System.nanoTime(); // 밀렸으면 따라잡지 않고 다음 주기부터
            }
        }
    }
}
