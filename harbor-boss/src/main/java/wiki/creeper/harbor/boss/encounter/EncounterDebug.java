package wiki.creeper.harbor.boss.encounter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.bukkit.Location;
import org.bukkit.entity.Interaction;

/** /harbor boss debug 출력: 진행 상태·판정 상자(위치에 파티클 표시)·참가자 기여 원자료. 메인 스레드 전용. */
final class EncounterDebug {

    private EncounterDebug() {}

    static List<String> lines(Encounter e) {
        List<String> out = new ArrayList<>();
        out.add(e.def.id() + " " + e.fight.state() + " slot=" + e.slot + " seed=" + e.seed);
        out.add(
                String.format(
                        "hp=%.1f/%.1f hull=%d/%d hook=%.2f elapsed=%ds",
                        e.fight.hp(),
                        e.fight.maxHp(),
                        e.fight.hull(),
                        e.fight.hullMax(),
                        e.fight.hookGauge(),
                        e.fight.elapsed() / 20));
        out.add("hitboxes=" + e.hitboxes().size() + " telegraph entities=" + e.telegraphEntities());
        for (Map.Entry<Interaction, String> box : e.hitboxes()) {
            Location l = BossItems.loc(box.getKey());
            out.add(
                    String.format(
                            " - %s @ %.1f %.1f %.1f (%.1f×%.1f)",
                            box.getValue(),
                            l.getX(),
                            l.getY(),
                            l.getZ(),
                            box.getKey().getInteractionWidth(),
                            box.getKey().getInteractionHeight()));
            l.getWorld().spawnParticle(org.bukkit.Particle.END_ROD, l, 20, 0.3, 0.3, 0.3, 0);
        }
        for (Member m : e.memberList()) {
            out.add(
                    String.format(
                            " * %s %s dmg=%.0f ten=%.0fs rep=%.0f eva=%.0f %s",
                            m.name,
                            m.role.key(),
                            m.damage,
                            m.tensionTicks / 20.0,
                            m.repair,
                            m.evasion,
                            m.dropped ? "dropped" : m.present() ? "present" : "absent"));
        }
        return out;
    }
}
