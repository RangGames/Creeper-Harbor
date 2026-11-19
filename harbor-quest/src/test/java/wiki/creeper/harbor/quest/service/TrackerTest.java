package wiki.creeper.harbor.quest.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class TrackerTest {

    private static String arrowTo(double dx, double dz, double yaw) {
        return Tracker.arrow(Tracker.relativeAngle(dx, dz, yaw));
    }

    @Test
    void facingSouth() {
        // yaw 0 = +Z(남). 오른쪽은 -X(서)
        assertEquals("↑", arrowTo(0, 10, 0));
        assertEquals("→", arrowTo(-10, 0, 0));
        assertEquals("←", arrowTo(10, 0, 0));
        assertEquals("↓", arrowTo(0, -10, 0));
        assertEquals("↗", arrowTo(-10, 10, 0));
    }

    @Test
    void facingEastWithWrappedYaw() {
        // yaw -90 = 270 = +X(동). 오른쪽은 +Z(남)
        assertEquals("↑", arrowTo(10, 0, -90));
        assertEquals("↑", arrowTo(10, 0, 270));
        assertEquals("→", arrowTo(0, 10, -90));
        assertEquals("←", arrowTo(0, -10, 630));
    }
}
