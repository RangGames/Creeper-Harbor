package wiki.creeper.harbor.collection;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import wiki.creeper.harbor.collection.content.DisplayCaseRules;
import wiki.creeper.harbor.common.donor.DonorTier;

/** 개인 어항 칸 수 (D11: 최고 평판 등급) · 테마 칸 (D14: 후원 외형 칸) · 부품 상한. */
class DisplaySlotMathTest {

    private static final DisplayCaseRules RULES =
            new DisplayCaseRules(
                    6,
                    Map.of(3, 2, 5, 4),
                    1,
                    120,
                    32,
                    5,
                    0.25,
                    null,
                    new DisplayCaseRules.Theme(null, "minecraft:sand", null),
                    List.of());

    @Test
    void slotsGrowWithBestReputationTierOnly() {
        assertEquals(6, RULES.slotCount(1));
        assertEquals(6, RULES.slotCount(2));
        assertEquals(8, RULES.slotCount(3));
        assertEquals(8, RULES.slotCount(4));
        // 5등급은 +4 (+2와 더하지 않는다: 도달한 가장 높은 단계 하나)
        assertEquals(10, RULES.slotCount(5));
        assertEquals(10, RULES.slotCount(7));
    }

    @Test
    void donorAddsThemeSlotsNotDisplaySlots() {
        assertEquals(1, RULES.themeSlotCount(DonorTier.NONE.extraThemeSlots()));
        assertEquals(1, RULES.themeSlotCount(DonorTier.HARBOR.extraThemeSlots()));
        assertEquals(3, RULES.themeSlotCount(DonorTier.FLEET.extraThemeSlots()));
        // 전시 칸 수는 후원과 무관
        assertEquals(RULES.slotCount(3), RULES.slotCount(3));
    }

    @Test
    void expiredDonorRevertsOnlyExtraThemes() {
        // 테마가 걸린 칸 2·5·0 (FLEET 때 3칸) → 후원 종료로 1칸: 번호가 작은 0만 남기고 2·5를 기본으로
        assertEquals(List.of(2, 5), DisplayCaseRules.themesToRevert(List.of(5, 2, 0), 1));
        assertEquals(List.of(), DisplayCaseRules.themesToRevert(List.of(5, 2, 0), 3));
        assertEquals(List.of(0, 2), DisplayCaseRules.themesToRevert(List.of(0, 2), 0));
    }

    @Test
    void slotOpenIsZeroBased() {
        assertTrue(DisplayCaseRules.slotOpen(0, 6));
        assertTrue(DisplayCaseRules.slotOpen(5, 6));
        assertFalse(DisplayCaseRules.slotOpen(6, 6));
        assertFalse(DisplayCaseRules.slotOpen(-1, 6));
    }

    @Test
    void partBudgetFallsBackToSimpleRig() {
        // 상한 12: 5 + 5 = 10 → 다음 5는 넘으므로 간이(1) → 11 → 다음 5도 간이 → 12 → 마지막은 표시 안 함
        assertArrayEquals(
                new int[] {0, 0, 1, 1, 2},
                DisplayCaseRules.budget(List.of(5, 5, 5, 5, 5), 12, true));
        // 간이 리그가 없으면 넘는 칸은 표시 안 함 (뒤의 작은 리그는 들어갈 수 있다)
        assertArrayEquals(
                new int[] {0, 0, 2, 0}, DisplayCaseRules.budget(List.of(5, 5, 5, 2), 12, false));
    }
}
