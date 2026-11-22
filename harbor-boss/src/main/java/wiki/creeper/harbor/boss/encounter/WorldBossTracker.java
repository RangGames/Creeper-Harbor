package wiki.creeper.harbor.boss.encounter;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.boss.api.BossRole;
import wiki.creeper.harbor.boss.api.Contribution;
import wiki.creeper.harbor.boss.logic.HpScaling;

/**
 * World boss-only progress: free participation, gauge rescaling by valid participants, 25% stage
 * notifications, resume restoration. Main thread only.
 */
final class WorldBossTracker {

    private final Encounter e;
    private final Map<UUID, Long> lastWorldActivity = new HashMap<>();
    private int worldStage;
    private long nextRescale;

    WorldBossTracker(Encounter e) {
        this.e = e;
    }

    /**
     * Most recent contribution time (for counting valid participants). Party bosses only record it.
     */
    void activity(UUID player, long now) {
        lastWorldActivity.put(player, now);
    }

    /**
     * A player within the radius becomes a participant on their first contribution (regardless of
     * party).
     */
    @Nullable Member join(Player p, BossRole role) {
        if (!e.def.isWorld() || !e.fight.state().fighting() || !e.inArena(BossItems.loc(p))) {
            return null;
        }
        if (e.mgr.encounterOfPlayer(p.getUniqueId()).filter(x -> x != e).isPresent()) {
            return null;
        }
        Member m = new Member(p.getUniqueId(), p.getName(), role, false);
        m.chose = true;
        e.members.put(m.uuid, m);
        e.mgr.bind(m.uuid, e);
        e.roster.enter(p, m, false);
        e.send(m.uuid, "boss.world.joined");
        return m;
    }

    /**
     * Every tick (world boss only): when the rescale interval comes, resets the gauge and checks
     * the stage.
     */
    void tick(long now) {
        if (now >= nextRescale) {
            nextRescale = now + e.bb.worldRescaleTicks();
            rescale(now);
        }
        checkStage();
    }

    /**
     * Every 10 seconds: resets the max gauge from valid participants (contributed in the last 60
     * seconds, minimum 10). Progress is kept.
     */
    private void rescale(long now) {
        int effective =
                HpScaling.effectiveParticipants(lastWorldActivity, now, e.bb.worldActiveTicks());
        e.fight.rescale(
                HpScaling.worldMaxHp(e.def.hp().base(), effective, e.bb.worldMinParticipants()));
    }

    int effectiveParticipants() {
        return HpScaling.effectiveParticipants(
                lastWorldActivity, e.mgr.now(), e.bb.worldActiveTicks());
    }

    /** 25% stage (0 start, 1 = 75% or less, 2 = 50% or less, 3 = 25% or less). */
    int stage() {
        double percent = e.fight.hpRatio() * 100;
        return percent <= 25 ? 3 : percent <= 50 ? 2 : percent <= 75 ? 1 : 0;
    }

    private void checkStage() {
        int s = stage();
        if (s > worldStage) {
            worldStage = s;
            e.mgr.worldStage(e, s);
        }
    }

    /**
     * resume: restores from saved progress and contributions. Without active time (older save
     * format), starts from 0.
     */
    void restore(double hpRatio, Map<UUID, Contribution> saved, Map<UUID, Long> activeTicks) {
        e.fight.restore(hpRatio, 1.0, true);
        worldStage = stage();
        for (Contribution c : saved.values()) {
            Member m =
                    new Member(
                            c.player(),
                            String.valueOf(c.player()).substring(0, 8),
                            c.role(),
                            false);
            Player p = Bukkit.getPlayer(c.player());
            if (p != null) {
                m = new Member(c.player(), p.getName(), c.role(), false);
                m.rememberIp(p);
            }
            m.damage = c.damage();
            m.tensionTicks = Math.round(c.tension() * 20);
            m.repair = c.repair();
            m.evasion = c.evasion();
            // Active time before the interruption must carry over, or players would fall below the
            // minimum active ratio
            m.activeTicks = activeTicks.getOrDefault(c.player(), 0L);
            m.chose = true;
            m.absentSince = p == null ? e.mgr.now() : -1;
            e.members.put(c.player(), m);
            e.mgr.bind(c.player(), e);
        }
    }
}
