package wiki.creeper.harbor.boss.encounter;

import java.util.Optional;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import wiki.creeper.harbor.boss.api.EncounterState;
import wiki.creeper.harbor.common.model.Grade;
import wiki.creeper.harbor.fishing.api.MinigameResult;
import wiki.creeper.harbor.fishing.api.TensionSession;
import wiki.creeper.harbor.fishing.api.TensionSpec;

/**
 * Anchor lines (Fishing TensionSession): opening, snap handling and closing, and syncing the boss
 * HP with the boss bar. Main thread only.
 */
final class AnchorLines {

    private final Encounter e;

    AnchorLines(Encounter e) {
        this.e = e;
    }

    boolean inSafe(TensionSession s) {
        double t = s.tension();
        return t >= e.bb.anchorSafeMin() && t <= e.bb.anchorSafeMax();
    }

    void open(Member m) {
        if (e.finished() || m.session != null || !m.present()) {
            return;
        }
        EncounterState s = e.fight.state();
        if (s != EncounterState.HOOK && s != EncounterState.COUNTER) {
            return;
        }
        Player p = Bukkit.getPlayer(m.uuid);
        if (p == null) {
            return;
        }
        Grade grade =
                switch (e.def.type()) {
                    case INTRO -> Grade.RARE;
                    case SEA -> Grade.EPIC;
                    default -> Grade.LEGENDARY;
                };
        TensionSpec spec =
                new TensionSpec(
                        e.bossName(),
                        grade,
                        1_000_000_000,
                        e.bb.anchorTensionMult(e.def.type()),
                        0,
                        true,
                        false);
        Optional<TensionSession> opened = e.mgr.fishing().openTension(p, spec);
        if (opened.isEmpty()) {
            m.reopenAt = e.mgr.now() + 20;
            return;
        }
        TensionSession session = opened.get();
        m.session = session;
        m.closingSession = false;
        session.onEnd(result -> sessionEnded(m, session, result));
        double ratio = e.fight.hpRatio();
        if (session.hpRatio() > ratio) {
            session.damage(session.hpRatio() - ratio);
        }
        e.send(m.uuid, "boss.anchor.hooked_on");
    }

    private void sessionEnded(Member m, TensionSession session, MinigameResult result) {
        if (java.util.Objects.equals(m.session, session)) {
            m.session = null;
        }
        if (m.closingSession || e.finished()) {
            m.closingSession = false;
            return;
        }
        if (result == MinigameResult.LINE_BROKE || result == MinigameResult.ESCAPED) {
            e.fight.lineBroken();
            e.broadcast("boss.anchor.line_broke", Placeholder.unparsed("player", m.name));
            m.reopenAt = e.mgr.now() + e.bb.hookReopenTicks();
        }
    }

    void close(Member m) {
        TensionSession s = m.session;
        if (s != null) {
            m.closingSession = true;
            m.session = null;
            if (s.isActive()) {
                s.close(MinigameResult.ABORTED);
            }
        }
        m.reopenAt = -1;
    }

    /** Syncs the anchor boss bar HP with the boss HP (can only decrease). */
    void mirror() {
        double ratio = e.fight.hpRatio();
        for (Member m : e.members.values()) {
            TensionSession s = m.session;
            if (s != null && s.isActive() && s.hpRatio() > ratio + 0.001) {
                s.damage(s.hpRatio() - ratio);
            }
        }
    }
}
