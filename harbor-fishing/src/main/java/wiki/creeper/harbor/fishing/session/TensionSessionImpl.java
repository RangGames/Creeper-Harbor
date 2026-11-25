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

/**
 * Tension session for the Boss anchor (FishingManager advances it at 20Hz and draws the UI). Main
 * thread only.
 */
final class TensionSessionImpl implements TensionSession {

    private final UUID player;
    private final Minigame game;
    private final TensionSpec spec;
    private final List<Consumer<MinigameResult>> listeners = new ArrayList<>();
    private final Runnable onClosed;
    @Nullable BossBar bar;
    boolean borderWarn;
    float baseYaw;

    /** Reel looping sound (while reeling with Shift). */
    HarborSounds.Loop reel = HarborSounds.Loop.NONE;

    final RodBend rod = new RodBend();

    private @Nullable MinigameResult finished;

    /** Rod lowered: the session does not advance (see {@link TensionSession#paused}). */
    boolean paused;

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
    public boolean paused() {
        return paused;
    }

    @Override
    public void setSafeZone(double low, double high) {
        game.setSafeZone(low, high);
    }

    @Override
    public boolean inSafeZone() {
        return game.inSafeZone();
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

    /** End handling (once only). */
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
