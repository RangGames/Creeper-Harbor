package wiki.creeper.harbor.common.rig;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RigPlaybackTest {

    /** 몸통이 20틱 동안 위아래로 0.5 움직이는 반복 동작 + 10틱짜리 한 번 동작. */
    private static final String RIG =
            """
            id: rig/fish/playback
            default_animation: swim
            bones:
              - name: body
                part: {item_model: "harbor:rig/test/body"}
                hitbox: {width: 1, height: 1}
            animations:
              swim:
                length: 20
                events:
                  - {t: 0, name: stroke}
                bones:
                  body:
                    position:
                      - {t: 0, v: [0, 0, 0]}
                      - {t: 10, v: [0, 0.5, 0]}
                      - {t: 20, v: [0, 0, 0]}
              leap:
                length: 10
                mode: once
                events:
                  - {t: 4, name: splash}
                bones:
                  body:
                    position:
                      - {t: 0, v: [0, 0, 0]}
                      - {t: 10, v: [0, 2, 0]}
              sink:
                length: 6
                mode: hold
                bones:
                  body:
                    position:
                      - {t: 0, v: [0, 0, 0]}
                      - {t: 6, v: [0, -1, 0]}
            """;

    record Sent(long tick, int part, double y, int duration) {}

    RigPlayback playback;
    List<Sent> sent;
    long now;

    @BeforeEach
    void setUp() {
        RigDef def = RigDefTest.parseOk(RIG);
        BakedRig baked =
                RigBaker.bake(
                        def, new RigBaker.Settings(10, new Xform.Tolerance(0.005, 1.2, 0.005), 8));
        playback = new RigPlayback(baked);
        sent = new ArrayList<>();
        now = 1000;
    }

    void tick(int ticks) {
        for (int i = 0; i < ticks; i++) {
            long at = now;
            playback.update(
                    at,
                    (part, target, duration) ->
                            sent.add(new Sent(at, part, target.translation().y(), duration)));
            now++;
        }
    }

    @Test
    void sendsOnlyAtKeyBoundariesAndLoops() {
        assertTrue(playback.play("swim", 0, 0, now));
        tick(41);
        // 키(프레임 압축 후)는 0·8·10·18·20틱 근처. 명령은 구간에 들어설 때만 나가므로 41틱 동안 프레임 수(21×2)보다 훨씬 적다
        assertTrue(sent.size() < 15, "명령 수 " + sent.size());
        // 첫 명령: 다음 키로 가는 보간
        Sent first = sent.getFirst();
        assertEquals(1000, first.tick());
        assertTrue(first.duration() > 0);
        // 한 바퀴(20틱) 뒤 같은 구간에 다시 들어서면 같은 명령이 다시 나간다
        List<Sent> atStart = sent.stream().filter(s -> (s.tick() - 1000) % 20 == 0).toList();
        assertEquals(3, atStart.size());
        assertEquals(atStart.get(0).y(), atStart.get(1).y(), 1e-9);
        assertEquals(atStart.get(0).duration(), atStart.get(1).duration());
    }

    @Test
    void durationsMatchKeySpacingAndSpeed() {
        playback.play("swim", 0, 0, now);
        tick(1);
        int normal = sent.getFirst().duration();
        sent.clear();
        playback.setSpeed(2, now);
        tick(1);
        assertEquals(5, sent.getFirst().duration());
        assertEquals(10, normal);
    }

    @Test
    void playOnceReturnsToDefaultAnimation() {
        playback.play("swim", 0, 0, now);
        tick(5);
        assertTrue(playback.playOnce("leap", null, 0, now));
        assertEquals("leap", playback.animation());
        tick(10);
        assertEquals("leap", playback.animation());
        tick(1);
        assertEquals("swim", playback.animation(), "한 번 재생 뒤 default_animation");
    }

    @Test
    void playOnceWithExplicitReturn() {
        playback.playOnce("leap", "sink", 0, now);
        tick(12);
        assertEquals("sink", playback.animation());
        tick(30);
        // hold: 마지막 자세 유지
        assertEquals("sink", playback.animation());
        assertEquals(-1, playback.pose(0, now).translation().y(), 1e-9);
    }

    @Test
    void blendSendsFirstPoseOverBlendTicks() {
        playback.play("swim", 0, 0, now);
        tick(3);
        sent.clear();
        playback.play("leap", 4, 0, now);
        tick(1);
        Sent blend = sent.getFirst();
        assertEquals(0, blend.y(), 1e-9);
        assertEquals(4, blend.duration());
        sent.clear();
        tick(3);
        assertTrue(sent.isEmpty(), "이어 붙이는 동안은 다시 보내지 않는다");
        tick(1);
        assertFalse(sent.isEmpty(), "이어 붙이기가 끝나면 동작 재생");
    }

    @Test
    void phaseAndResync() {
        playback.play("swim", 0, 0, now);
        playback.setPhase(5, now);
        assertEquals(5, playback.frame(now));
        assertEquals(10.0, playback.localTime(now), 1e-9);
        tick(1);
        int count = sent.size();
        tick(1);
        assertEquals(count, sent.size());
        playback.resync();
        tick(1);
        assertEquals(count + 1, sent.size(), "재동기화하면 지금 구간을 다시 보낸다");
        // 프레임 번호는 한 바퀴로 접힌다
        playback.setPhase(25, now);
        assertEquals(5, playback.frame(now));
    }

    @Test
    void stopReturnsToRestAndSpeedZeroFreezes() {
        playback.play("swim", 0, 0, now);
        tick(5);
        playback.setSpeed(0, now);
        sent.clear();
        tick(5);
        assertEquals(1, sent.size());
        assertEquals(0, sent.getFirst().duration());
        playback.stop(0, now);
        assertNull(playback.animation());
        assertFalse(playback.play("missing", 0, 0, now));
    }

    @Test
    void hitboxFollowsAnimation() {
        playback.play("swim", 0, 0, now);
        assertEquals(0, playback.hitbox(0, now).y(), 1e-9);
        assertEquals(0.5, playback.hitbox(0, now + 10).y(), 1e-9);
        assertEquals(0.25, playback.hitbox(0, now + 25).y(), 1e-9);
    }

    @Test
    void firesEventsOncePerCrossingAndPerLoop() {
        List<String> fired = new ArrayList<>();
        playback.setEventSink((anim, event) -> fired.add(anim + ":" + event + "@" + (now - 1000)));
        playback.play("swim", 0, 0, now);
        tick(41); // 0·20·40틱에 stroke
        assertEquals(List.of("swim:stroke@0", "swim:stroke@20", "swim:stroke@40"), fired);
        fired.clear();
        playback.playOnce("leap", null, 0, now);
        long start = now - 1000;
        tick(12); // leap 4틱에 splash, 끝나면 swim으로 돌아가 0틱 stroke
        assertEquals(List.of("leap:splash@" + (start + 4), "swim:stroke@" + (start + 10)), fired);
        fired.clear();
        // 오래 갱신이 끊겼다가 돌아오면 그사이 이벤트는 버린다
        now += 100;
        tick(1);
        assertTrue(fired.isEmpty(), fired::toString);
    }
}
