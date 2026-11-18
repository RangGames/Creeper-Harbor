package wiki.creeper.harbor.fishing.session;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import net.kyori.adventure.bossbar.BossBar;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.core.api.HarborSounds;
import wiki.creeper.harbor.fishing.api.MinigameResult;
import wiki.creeper.harbor.fishing.api.TensionSession;
import wiki.creeper.harbor.fishing.api.TensionSpec;

/** Boss 앵커용 텐션 세션 (FishingManager가 20Hz로 진행하고 UI를 그린다). 메인 스레드 전용. */
final class TensionSessionImpl implements TensionSession {

    private final UUID player;
    private final Minigame game;
    private final TensionSpec spec;
    private final List<Consumer<MinigameResult>> listeners = new ArrayList<>();
    private final Runnable onClosed;
    @Nullable BossBar bar;
    boolean borderWarn;
    float baseYaw;

    /** 릴 감기 반복 소리 (Shift 감는 동안). */
    HarborSounds.Loop reel = HarborSounds.Loop.NONE;

    private @Nullable MinigameResult finished;

    TensionSessionImpl(UUID player, Minigame game, TensionSpec spec, Runnable onClosed) {
        this.player = player;
        this.game = game;
        this.spec = spec;
        this.onClosed = onClosed;
        game.setEndOnCaught(spec.endOnCaught());
    }

    Minigame game() {
        return game;
    }

    TensionSpec spec() {
        return spec;
    }

    @Override
    public UUID player() {
        return player;
    }

    @Override
    public double tension() {
        return game.tension();
    }

    @Override
    public double hpRatio() {
        return game.hpRatio();
    }

    @Override
    public int phase() {
        return game.phase();
    }

    @Override
    public boolean isActive() {
        return finished == null;
    }

    @Override
    public @Nullable MinigameResult result() {
        return finished;
    }

    @Override
    public void addTension(double delta) {
        game.addTension(delta);
    }

    @Override
    public void damage(double fraction) {
        game.damage(fraction);
    }

    @Override
    public void onEnd(Consumer<MinigameResult> listener) {
        MinigameResult done = finished;
        if (done != null) {
            listener.accept(done);
        } else {
            listeners.add(listener);
        }
    }

    @Override
    public void close(MinigameResult result) {
        game.abort(result);
        finish(result);
    }

    /** 끝 처리 (한 번만). */
    void finish(MinigameResult result) {
        if (finished != null) {
            return;
        }
        finished = result;
        reel.stop();
        onClosed.run();
        for (Consumer<MinigameResult> l : List.copyOf(listeners)) {
            l.accept(result);
        }
        listeners.clear();
    }
}
