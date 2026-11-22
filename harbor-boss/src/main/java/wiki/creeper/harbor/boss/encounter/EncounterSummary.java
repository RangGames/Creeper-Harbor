package wiki.creeper.harbor.boss.encounter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import wiki.creeper.harbor.boss.api.BossRole;
import wiki.creeper.harbor.boss.api.Contribution;
import wiki.creeper.harbor.boss.api.EncounterInfo;
import wiki.creeper.harbor.boss.logic.ContributionCalc;

/** Read-only summaries of an encounter: contribution ranking and the public info view. */
final class EncounterSummary {

    private EncounterSummary() {}

    /** Contribution ranking (ContributionCalc). */
    static List<Contribution> contributions(Encounter e) {
        List<ContributionCalc.Stats> stats = new ArrayList<>();
        for (Member m : e.members.values()) {
            stats.add(m.stats());
        }
        List<ContributionCalc.Scored> scored =
                ContributionCalc.rank(
                        stats,
                        e.bb.weights(),
                        e.bb.topRatio(),
                        Math.max(1, e.fight.elapsed()),
                        e.bb.minScoreRatio(),
                        e.bb.minActiveRatio());
        List<Contribution> out = new ArrayList<>();
        for (ContributionCalc.Scored s : scored) {
            Member m = e.members.get(s.stats().player());
            BossRole role = m == null ? BossRole.HARPOONER : m.role;
            out.add(
                    new Contribution(
                            s.stats().player(),
                            role,
                            s.stats().damage(),
                            s.stats().tension(),
                            s.stats().repair(),
                            s.stats().evasion(),
                            s.score(),
                            s.rank(),
                            s.top(),
                            s.eligible()));
        }
        return out;
    }

    static EncounterInfo info(Encounter e) {
        Map<UUID, BossRole> roles = new LinkedHashMap<>();
        List<UUID> order = new ArrayList<>();
        order.add(e.leader);
        for (Member m : e.members.values()) {
            roles.put(m.uuid, m.role);
            if (!m.uuid.equals(e.leader)) {
                order.add(m.uuid);
            }
        }
        if (!e.members.containsKey(e.leader)) {
            order.remove(e.leader);
        }
        return new EncounterInfo(
                e.id,
                e.def.id(),
                e.finished() ? e.lastState() : e.fight.state(),
                e.leader,
                order,
                roles,
                e.mgr.serverId(),
                e.slot,
                e.fight.hpRatio(),
                e.fight.hull(),
                e.fight.hullMax(),
                e.fight.elapsed());
    }
}
