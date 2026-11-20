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

/** 월드 보스 전용 진행: 자유 참가, 유효 참여자 기준 게이지 재조정, 25% 단계 알림, resume 복원. 메인 스레드 전용. */
final class WorldBossTracker {

    private final Encounter e;
    private final Map<UUID, Long> lastWorldActivity = new HashMap<>();
    private int worldStage;
    private long nextRescale;

    WorldBossTracker(Encounter e) {
        this.e = e;
    }

    /** 최근 기여 시각 (유효 참여자 계산용). 파티 보스에서도 기록만 한다. */
    void activity(UUID player, long now) {
        lastWorldActivity.put(player, now);
    }

    /** 반경 안 플레이어가 처음 기여하면 참가자가 된다 (파티 구분 없음). */
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

    /** 매 틱 (월드 보스만): 재조정 주기가 되면 게이지를 다시 정하고 단계를 확인한다. */
    void tick(long now) {
        if (now >= nextRescale) {
            nextRescale = now + e.bb.worldRescaleTicks();
            rescale(now);
        }
        checkStage();
    }

    /** 10초마다: 유효 참여자(최근 60초 기여, 최소 10명)로 최대 게이지를 다시 정한다. 진행률은 유지. */
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

    /** 25% 단위 단계 (0 시작, 1 = 75% 이하, 2 = 50% 이하, 3 = 25% 이하). */
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

    /** resume: 저장한 진행률·기여도로 되살린다. */
    void restore(double hpRatio, Map<UUID, Contribution> saved) {
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
            }
            m.damage = c.damage();
            m.tensionTicks = Math.round(c.tension() * 20);
            m.repair = c.repair();
            m.evasion = c.evasion();
            m.chose = true;
            m.absentSince = p == null ? e.mgr.now() : -1;
            e.members.put(c.player(), m);
            e.mgr.bind(c.player(), e);
        }
    }
}
