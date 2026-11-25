package wiki.creeper.harbor.fishing.session;

import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.content.type.FishDef;
import wiki.creeper.harbor.fishing.api.SessionInfo;
import wiki.creeper.harbor.fishing.api.SessionState;

/** Builds the public {@link SessionInfo} snapshot of a player's fishing and tension sessions. */
final class SessionInfos {

    private SessionInfos() {}

    static SessionInfo of(@Nullable FishingSession s, @Nullable TensionSessionImpl t) {
        boolean tensionOn = t != null && t.isActive();
        Minigame game = s == null || s.state != SessionState.MINIGAME ? null : s.minigame;
        double tension = 0;
        double hp = 1;
        if (game != null) {
            tension = game.tension();
            hp = game.hpRatio();
        } else if (t != null && tensionOn) {
            tension = t.tension();
            hp = t.hpRatio();
        }
        if (s == null) {
            return new SessionInfo(
                    SessionState.IDLE,
                    null,
                    false,
                    false,
                    tensionOn,
                    tension,
                    hp,
                    null,
                    null,
                    null);
        }
        FishDef fish = s.fish;
        FishDef forced = s.forcedFish;
        return new SessionInfo(
                s.state,
                fish == null ? null : fish.id(),
                s.state == SessionState.BITE,
                game != null,
                tensionOn,
                tension,
                hp,
                s.region,
                s.bait,
                forced == null ? null : forced.id());
    }
}
