package wiki.creeper.harbor.collection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import wiki.creeper.harbor.collection.aquarium.TankLayout;
import wiki.creeper.harbor.collection.content.AquariumRules.Tank;
import wiki.creeper.harbor.collection.content.Box;
import wiki.creeper.harbor.common.id.FishId;

/** 수조 배치: 심해관 분리, 수조 칸 수, 동시 표시 상한과 교대. */
class TankLayoutTest {

    private static FishId fish(int i) {
        return FishId.parse("fish/f" + i);
    }

    private static List<FishId> fish(int from, int to) {
        List<FishId> list = new ArrayList<>();
        for (int i = from; i < to; i++) {
            list.add(fish(i));
        }
        return list;
    }

    private static Tank tank(String id, int slots, boolean deep) {
        return new Tank(id, Box.of(0, 0, 0, 3, 3, 3), slots, 0, deep, null);
    }

    @Test
    void fillsTanksInOrderAndSeparatesDeep() {
        List<Tank> tanks = List.of(tank("a", 2, false), tank("b", 2, false), tank("deep", 2, true));
        Map<FishId, String> layout =
                TankLayout.assign(fish(0, 6), tanks, Set.of(fish(1), fish(4)), 40, 0);
        assertEquals("a", layout.get(fish(0)));
        assertEquals("deep", layout.get(fish(1)));
        assertEquals("a", layout.get(fish(2)));
        assertEquals("b", layout.get(fish(3)));
        assertEquals("deep", layout.get(fish(4)));
        assertEquals("b", layout.get(fish(5)));
    }

    @Test
    void deepFishGoToNormalTanksWhenDeepHallClosed() {
        Map<FishId, String> layout =
                TankLayout.assign(fish(0, 2), List.of(tank("a", 5, false)), Set.of(fish(1)), 40, 0);
        assertEquals("a", layout.get(fish(1)));
    }

    @Test
    void overflowBeyondSlotsIsHidden() {
        Map<FishId, String> layout =
                TankLayout.assign(fish(0, 5), List.of(tank("a", 3, false)), Set.of(), 40, 0);
        assertEquals(3, layout.size());
        assertFalse(layout.containsKey(fish(4)));
    }

    @Test
    void windowRotatesWhenOverVisibleMax() {
        List<FishId> all = fish(0, 5);
        assertEquals(all, TankLayout.window(all, 10, 7));
        assertEquals(List.of(fish(0), fish(1)), TankLayout.window(all, 2, 0));
        assertEquals(List.of(fish(2), fish(3)), TankLayout.window(all, 2, 1));
        assertEquals(List.of(fish(4), fish(0)), TankLayout.window(all, 2, 2));
    }
}
