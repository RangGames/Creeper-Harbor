package wiki.creeper.harbor.boss.encounter;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import wiki.creeper.harbor.boss.content.BossDef;
import wiki.creeper.harbor.boss.logic.Fight;
import wiki.creeper.harbor.boss.logic.Qte;

/** 최후의 당기기 QTE: 라운드 시작·입력·판정, 타이틀 글리프 표시. 판정 규칙은 순수 로직 {@link Qte}. 메인 스레드 전용. */
final class FinalTugQte {

    /** 입력 판정 여유 ±2틱 ('8. 시스템 상세 기획' 3.3). */
    private static final int INPUT_TOLERANCE = 2;

    private final Encounter e;
    private long qteGapUntil = -1;
    private long qteRoundEnd = -1;
    private boolean qteRunning;

    FinalTugQte(Encounter e) {
        this.e = e;
    }

    /** FINAL_TUG 진입: 진행 중 라운드를 멈추고 간격 뒤 첫 라운드를 연다. */
    void arm(long now) {
        qteRunning = false;
        qteGapUntil = now + e.bb.qteGapTicks();
    }

    /** 새로 눌린 키를 이 참가자의 트랙에 넣는다. */
    void press(Player p, Member m, Set<Qte.Key> pressed, long now) {
        Qte.Track track = m.track;
        if (track != null && qteRunning && track.press(pressed, now)) {
            if (track.results().getLast()) {
                m.activity(now);
                e.worldBoss.activity(m.uuid, now);
            }
            show(p, m);
        }
    }

    void tick(long now) {
        if (!qteRunning) {
            if (qteGapUntil >= 0 && now >= qteGapUntil) {
                startRound(now);
            }
            return;
        }
        List<Qte.Track> tracks = new ArrayList<>();
        for (Member m : e.members.values()) {
            Qte.Track t = m.track;
            if (t == null) {
                continue;
            }
            if (!m.present() || m.fallen(now)) {
                t.setExcluded(true);
            }
            int before = t.index();
            t.tick(now);
            if (t.index() != before) {
                Player p = Bukkit.getPlayer(m.uuid);
                if (p != null) {
                    show(p, m);
                }
            }
            tracks.add(t);
        }
        if (Qte.allDone(tracks) || now >= qteRoundEnd) {
            double rate = Qte.rate(tracks);
            boolean ok = rate >= pass();
            e.broadcast(
                    ok ? "boss.qte.round_ok" : "boss.qte.round_fail",
                    Placeholder.unparsed("rate", String.valueOf(Math.round(rate * 100))));
            qteRunning = false;
            for (Member m : e.members.values()) {
                m.track = null;
            }
            e.handle(e.fight.roundResult(ok));
            if (!e.finished() && e.fight.phase() instanceof Fight.FinalTug) {
                qteGapUntil = now + e.bb.qteGapTicks();
            }
        }
    }

    private void startRound(long now) {
        BossDef.Qte q =
                e.def.phase(BossDef.PhaseKind.FINAL_TUG)
                        .map(BossDef.PhaseDef::qte)
                        .orElse(BossDef.Qte.DEFAULT);
        int inputs = q.inputs() > 0 ? q.inputs() : e.bb.qteInputs();
        int window = q.windowTicks() > 0 ? q.windowTicks() : e.bb.qteWindowTicks();
        int eligible = 0;
        for (Member m : e.members.values()) {
            Player p = Bukkit.getPlayer(m.uuid);
            if (p == null || !m.present() || m.fallen(now)) {
                m.track = null;
                continue;
            }
            Qte.Track t =
                    new Qte.Track(Qte.sequence(e.random, inputs), window, INPUT_TOLERANCE, now);
            t.setInverted(m.invertedUntil > now);
            m.track = t;
            eligible++;
            show(p, m);
        }
        qteRunning = true;
        qteGapUntil = -1;
        qteRoundEnd = now + (long) inputs * (window + INPUT_TOLERANCE) + 20;
        if (eligible == 0) {
            // 모두 낙수·이탈이면 이번 라운드는 실패로 끝난다
            qteRoundEnd = now;
        }
        if (e.fight.phase() instanceof Fight.FinalTug(int round, int s, int f)) {
            e.broadcast(
                    "boss.qte.round",
                    Placeholder.unparsed("round", String.valueOf(round)),
                    Placeholder.unparsed("ok", String.valueOf(s)),
                    Placeholder.unparsed("fail", String.valueOf(f)));
        }
    }

    private double pass() {
        BossDef.Qte q =
                e.def.phase(BossDef.PhaseKind.FINAL_TUG)
                        .map(BossDef.PhaseDef::qte)
                        .orElse(BossDef.Qte.DEFAULT);
        return q.pass() > 0 ? q.pass() : e.bb.qtePass();
    }

    void end() {
        qteRunning = false;
        qteGapUntil = -1;
        for (Member m : e.members.values()) {
            m.track = null;
            Player p = Bukkit.getPlayer(m.uuid);
            if (p != null) {
                p.clearTitle();
            }
        }
    }

    /** 타이틀 글리프로 입력 순서를 보여준다 (끝난 것 초록·빨강, 지금 노랑, 남은 것 회색). */
    private void show(Player p, Member m) {
        Qte.Track t = m.track;
        if (t == null) {
            return;
        }
        List<Boolean> results = t.results();
        Component line = Component.empty();
        for (int i = 0; i < t.keys().size(); i++) {
            Component key = e.msg("boss.qte.key." + t.keys().get(i).key());
            String style =
                    i < results.size()
                            ? (results.get(i) ? "boss.qte.slot.ok" : "boss.qte.slot.fail")
                            : i == t.index() ? "boss.qte.slot.current" : "boss.qte.slot.next";
            line = line.append(e.msg(style, Placeholder.component("key", key)));
        }
        Component sub =
                t.done()
                        ? e.msg("boss.qte.wait")
                        : e.msg(
                                m.invertedUntil > e.mgr.now()
                                        ? "boss.qte.hint_inverted"
                                        : "boss.qte.hint");
        p.showTitle(
                Title.title(
                        line,
                        sub,
                        Title.Times.times(
                                Duration.ZERO, Duration.ofSeconds(3), Duration.ofMillis(150))));
    }
}
