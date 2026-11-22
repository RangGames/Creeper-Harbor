package wiki.creeper.harbor.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;
import wiki.creeper.harbor.proxy.QueueLines.Line;

class QueueLinesTest {

    private static boolean all(String s) {
        return true;
    }

    private static List<String> drain(QueueLines<String> lines, Predicate<String> ready) {
        List<String> order = new ArrayList<>();
        String next;
        while ((next = lines.pollNext(ready)) != null) {
            order.add(next);
        }
        return order;
    }

    @Test
    void normalLineGetsAtLeastOneOfEveryFour() {
        QueueLines<String> lines = new QueueLines<>(4);
        for (int i = 0; i < 9; i++) {
            lines.add(Line.PRIORITY, "p" + i);
        }
        for (int i = 0; i < 3; i++) {
            lines.add(Line.NORMAL, "n" + i);
        }
        List<String> order = drain(lines, QueueLinesTest::all);
        assertEquals(
                List.of("p0", "p1", "p2", "n0", "p3", "p4", "p5", "n1", "p6", "p7", "p8", "n2"),
                order);
        // Any 4 consecutive entries include at least 1 from the normal line (while the normal line
        // is non-empty)
        for (int i = 0; i + 4 <= order.size(); i++) {
            assertTrue(
                    order.subList(i, i + 4).stream().anyMatch(s -> s.startsWith("n")), "구간 " + i);
        }
    }

    @Test
    void graceFirstThenPriorityThenNormal() {
        QueueLines<String> lines = new QueueLines<>(4);
        lines.add(Line.NORMAL, "n0");
        lines.add(Line.PRIORITY, "p0");
        lines.add(Line.GRACE, "g0");
        assertEquals(List.of("g0", "p0", "n0"), drain(lines, QueueLinesTest::all));
    }

    @Test
    void graceAdmissionsAlsoCountTowardNormalShare() {
        QueueLines<String> lines = new QueueLines<>(2);
        lines.add(Line.GRACE, "g0");
        lines.add(Line.GRACE, "g1");
        lines.add(Line.NORMAL, "n0");
        assertEquals(List.of("g0", "n0", "g1"), drain(lines, QueueLinesTest::all));
    }

    @Test
    void onlyNormalOrOnlyPriorityDrainsInOrder() {
        QueueLines<String> normal = new QueueLines<>(4);
        normal.add(Line.NORMAL, "a");
        normal.add(Line.NORMAL, "b");
        assertEquals(List.of("a", "b"), drain(normal, QueueLinesTest::all));

        QueueLines<String> priority = new QueueLines<>(4);
        for (String s : List.of("a", "b", "c", "d", "e")) {
            priority.add(Line.PRIORITY, s);
        }
        assertEquals(List.of("a", "b", "c", "d", "e"), drain(priority, QueueLinesTest::all));
    }

    @Test
    void normalArrivingAfterLongPriorityRunIsServedNext() {
        QueueLines<String> lines = new QueueLines<>(4);
        for (int i = 0; i < 5; i++) {
            lines.add(Line.PRIORITY, "p" + i);
        }
        assertEquals("p0", lines.pollNext(QueueLinesTest::all));
        assertEquals("p1", lines.pollNext(QueueLinesTest::all));
        assertEquals("p2", lines.pollNext(QueueLinesTest::all));
        assertEquals("p3", lines.pollNext(QueueLinesTest::all));
        lines.add(Line.NORMAL, "n0");
        assertEquals("n0", lines.pollNext(QueueLinesTest::all));
        assertEquals("p4", lines.pollNext(QueueLinesTest::all));
    }

    @Test
    void shareOfOneMeansNormalAlwaysFirst() {
        QueueLines<String> lines = new QueueLines<>(1);
        lines.add(Line.PRIORITY, "p0");
        lines.add(Line.NORMAL, "n0");
        lines.add(Line.NORMAL, "n1");
        assertEquals(List.of("n0", "n1", "p0"), drain(lines, QueueLinesTest::all));
    }

    @Test
    void notReadyEntriesKeepTheirPlace() {
        QueueLines<String> lines = new QueueLines<>(4);
        lines.addFront(Line.GRACE, "cleaning");
        lines.add(Line.NORMAL, "n0");
        Set<String> waiting = Set.of("cleaning");
        assertEquals("n0", lines.pollNext(s -> !waiting.contains(s)));
        assertNull(lines.pollNext(s -> !waiting.contains(s)));
        assertEquals(1, lines.position("cleaning").orElseThrow());
        assertEquals("cleaning", lines.pollNext(QueueLinesTest::all));
    }

    @Test
    void notReadyNormalDoesNotForceNormalTurn() {
        QueueLines<String> lines = new QueueLines<>(2);
        lines.add(Line.PRIORITY, "p0");
        lines.add(Line.PRIORITY, "p1");
        lines.add(Line.NORMAL, "waiting");
        Predicate<String> ready = s -> !s.equals("waiting");
        assertEquals(List.of("p0", "p1"), drain(lines, ready));
        assertTrue(lines.contains("waiting"));
    }

    @Test
    void positionsAddRemoveAndDuplicates() {
        QueueLines<String> lines = new QueueLines<>(4);
        assertTrue(lines.add(Line.NORMAL, "a"));
        assertFalse(lines.add(Line.PRIORITY, "a"));
        lines.add(Line.PRIORITY, "b");
        lines.add(Line.GRACE, "c");
        assertEquals(List.of("c", "b", "a"), lines.snapshot());
        assertEquals(3, lines.position("a").orElseThrow());
        assertEquals(Line.PRIORITY, lines.lineOf("b").orElseThrow());
        assertTrue(lines.remove("b"));
        assertFalse(lines.remove("b"));
        assertEquals(2, lines.size());
        assertTrue(lines.position("b").isEmpty());
    }

    @Test
    void moveToPlacesAtOverallPosition() {
        QueueLines<String> lines = new QueueLines<>(4);
        lines.add(Line.GRACE, "g0");
        lines.add(Line.PRIORITY, "p0");
        lines.add(Line.NORMAL, "n0");
        lines.add(Line.NORMAL, "n1");
        lines.add(Line.NORMAL, "n2");

        assertTrue(lines.moveTo("n2", 1));
        assertEquals(List.of("n2", "g0", "p0", "n0", "n1"), lines.snapshot());
        assertEquals(Line.GRACE, lines.lineOf("n2").orElseThrow());

        assertTrue(lines.moveTo("g0", 4));
        assertEquals(4, lines.position("g0").orElseThrow());

        assertTrue(lines.moveTo("n2", 99));
        assertEquals(5, lines.position("n2").orElseThrow());
        assertEquals(Line.NORMAL, lines.lineOf("n2").orElseThrow());

        assertFalse(lines.moveTo("nobody", 1));
    }
}
