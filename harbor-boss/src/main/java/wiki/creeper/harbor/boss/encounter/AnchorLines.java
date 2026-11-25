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

    /**
     * In the safe band with the rod raised (a lowered rod does not hold the boss). The band is the
     * one set on the session in {@link #open}, so it is exactly the green part of the gauge.
     */
    boolean inSafe(TensionSession s) {
        return !s.paused() && s.inSafeZone();
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
        session.setSafeZone(e.bb.anchorSafeMin(), e.bb.anchorSafeMax());
        m.session = session;
        m.closingSession = false;
        session.onEnd(result -> sessionEnded(m, session, result));
        double ratio = e.fight.hpRatio();
        if (session.hpRatio() > ratio) {
            session.damage(session.hpRatio() - ratio);
        }
        if (m.anchorGuided) {
            e.mgr.api().ui().actionBar(p, e.msg("boss.anchor.rehooked"));
        } else {
            // First line of the fight: what the anchor does and how to hold it
            m.anchorGuided = true;
            e.send(m.uuid, "boss.anchor.hooked_on");
            e.send(m.uuid, "boss.anchor.guide");
        }
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
            // LINE_BROKE: tension reached the limit (reeled too hard). ESCAPED: the anchor left
            // or died (slack no longer ends the line)
            e.broadcast(
                    result == MinigameResult.LINE_BROKE
                            ? "boss.anchor.line_broke"
                            : "boss.anchor.line_lost",
                    Placeholder.unparsed("player", m.name),
                    Placeholder.unparsed("seconds", String.valueOf(e.bb.hookReopenTicks() / 20)));
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

    /**
     * Boss damage per tick of one anchor holding the safe band. Small parties reel harder since
     * they have no full harpoon crew (balance.yml boss.anchor.reel_party_mult).
     */
    double reelPerTick() {
        int party = e.def.isWorld() ? 0 : e.members.size();
        return e.fight.maxHp()
                * e.bb.anchorReelDamagePerSecond()
                * e.bb.anchorReelPartyMult(party)
                / 20.0;
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
