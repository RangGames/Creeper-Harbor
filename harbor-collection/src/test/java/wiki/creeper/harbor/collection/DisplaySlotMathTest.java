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

/**
 * Personal aquarium slot count (D11: highest reputation tier) · theme slots (D14: donor cosmetic
 * slots) · part budget.
 */
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
        // Tier 5 gives +4 (not added to +2: only the single highest stage reached)
        assertEquals(10, RULES.slotCount(5));
        assertEquals(10, RULES.slotCount(7));
    }

    @Test
    void donorAddsThemeSlotsNotDisplaySlots() {
        assertEquals(1, RULES.themeSlotCount(DonorTier.NONE.extraThemeSlots()));
        assertEquals(1, RULES.themeSlotCount(DonorTier.HARBOR.extraThemeSlots()));
        assertEquals(3, RULES.themeSlotCount(DonorTier.FLEET.extraThemeSlots()));
        // Display slot count does not depend on donor tier
        assertEquals(RULES.slotCount(3), RULES.slotCount(3));
    }

    @Test
    void expiredDonorRevertsOnlyExtraThemes() {
        // Themed slots 2·5·0 (3 slots under FLEET) → 1 slot after donation ends: keep only the
        // lowest-numbered 0, revert 2·5 to default
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
        // Budget 12: 5 + 5 = 10 → next 5 would exceed, so simple (1) → 11 → next 5 also simple → 12
        // → last is not shown
        assertArrayEquals(
                new int[] {0, 0, 1, 1, 2},
                DisplayCaseRules.budget(List.of(5, 5, 5, 5, 5), 12, true));
        // Without a simple rig, slots that exceed are not shown (later smaller rigs can still fit)
        assertArrayEquals(
                new int[] {0, 0, 2, 0}, DisplayCaseRules.budget(List.of(5, 5, 5, 2), 12, false));
    }
}
