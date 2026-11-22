package wiki.creeper.harbor.boss.logic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

/** Final tug-of-war QTE judgment (docs 20 10.4). */
class QteTest {

    @Test
    void onlyPressEdgesCount() {
        Qte.InputState none = Qte.InputState.NONE;
        Qte.InputState w = new Qte.InputState(true, false, false, false, false, false);
        Qte.InputState wd = new Qte.InputState(true, false, false, true, false, false);
        assertEquals(Set.of(Qte.Key.FORWARD), w.newlyPressed(none));
        assertEquals(Set.of(), w.newlyPressed(w)); // holding
        assertEquals(Set.of(Qte.Key.RIGHT), wd.newlyPressed(w));
    }

    @Test
    void sequenceHasNoImmediateRepeats() {
        List<Qte.Key> seq = Qte.sequence(new SplittableRandom(42), 50);
        for (int i = 1; i < seq.size(); i++) {
            assertNotEquals(seq.get(i - 1), seq.get(i));
        }
    }

    @Test
    void trackScoresCorrectWrongAndTimeout() {
        Qte.Track t =
                new Qte.Track(
                        List.of(Qte.Key.FORWARD, Qte.Key.LEFT, Qte.Key.JUMP, Qte.Key.SNEAK),
                        20,
                        2,
                        0);
        assertTrue(t.press(Set.of(Qte.Key.FORWARD), 5)); // success
        assertTrue(t.press(Set.of(Qte.Key.RIGHT), 10)); // another key → failure
        t.tick(31); // within 10 + 20 + 2 → not yet
        assertEquals(2, t.index());
        t.tick(33); // 2-tick slack passed → timeout failure
        assertEquals(3, t.index());
        assertTrue(t.press(Set.of(Qte.Key.SNEAK), 40));
        assertTrue(t.done());
        assertEquals(2, t.successes());
        assertEquals(List.of(true, false, false, true), t.results());
        assertFalse(t.press(Set.of(Qte.Key.SNEAK), 41));
    }

    @Test
    void invertedFlipsDirection() {
        Qte.Track t = new Qte.Track(List.of(Qte.Key.LEFT, Qte.Key.JUMP), 20, 2, 0);
        t.setInverted(true);
        t.press(Set.of(Qte.Key.RIGHT), 1);
        t.press(Set.of(Qte.Key.JUMP), 2);
        assertEquals(2, t.successes());
    }

    @Test
    void roundRateExcludesFallenPlayers() {
        Qte.Track good =
                new Qte.Track(List.of(Qte.Key.FORWARD, Qte.Key.LEFT, Qte.Key.JUMP), 20, 2, 0);
        good.press(Set.of(Qte.Key.FORWARD), 1);
        good.press(Set.of(Qte.Key.LEFT), 2);
        good.press(Set.of(Qte.Key.JUMP), 3);
        Qte.Track fallen =
                new Qte.Track(List.of(Qte.Key.FORWARD, Qte.Key.LEFT, Qte.Key.JUMP), 20, 2, 0);
        assertEquals(0.5, Qte.rate(List.of(good, fallen)), 1e-9);
        assertFalse(Qte.allDone(List.of(good, fallen)));
        fallen.setExcluded(true);
        assertEquals(1.0, Qte.rate(List.of(good, fallen)), 1e-9);
        assertTrue(Qte.allDone(List.of(good, fallen)));
    }
}
