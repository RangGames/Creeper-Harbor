package wiki.creeper.harbor.collection.content;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.content.ContentType;
import wiki.creeper.harbor.common.content.ParseContext;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.common.id.RigId;
import wiki.creeper.harbor.common.rig.RigDef;
import wiki.creeper.harbor.common.yaml.YamlNode;

/**
 * Personal aquarium display rules (display_case.yml, proposed, docs 20 4.4 "personal aquarium
 * display" · 0.6 D11·D14). All numbers are proposed values.
 *
 * @param baseSlots base display slots ("12. World · Housing · Traps" ch. 4: 6 base slots)
 * @param repBonus highest cross-season reputation tier → extra slots (only the single highest stage
 *     reached, not cumulative. 5.4 reputation table: tier 3 +2, tier 5 +4)
 * @param baseThemeSlots base theme slots (number of slots that can hold a non-default theme)
 * @param partBudget rig part budget per plot (beyond it, simple rigs are used)
 * @param viewRadius also visible to players outside the plot within this distance
 * @param infoSeconds how long visitor click info is shown
 * @param swimRadius swim radius inside the tank (blocks)
 * @param simpleRig single-part simple rig (if absent, slots beyond the budget are not shown)
 * @param defaultTheme default theme (all slots)
 * @param themes cosmetic themes (Shop aquarium theme cosmetic → appearance)
 */
public record DisplayCaseRules(
        int baseSlots,
        Map<Integer, Integer> repBonus,
        int baseThemeSlots,
        int partBudget,
        double viewRadius,
        int infoSeconds,
        double swimRadius,
        @Nullable RigId simpleRig,
        Theme defaultTheme,
        List<Theme> themes) {

    /**
     * Aquarium theme (appearance only, no stats).
     *
     * @param cosmetic Shop cosmetic ID (null for the default theme)
     * @param floor Block Display block laid on the tank floor (minecraft:sand …)
     * @param particle ambient particle (bubble …; none if absent)
     */
    public record Theme(@Nullable HarborId cosmetic, String floor, @Nullable String particle) {}

    public static final ContentType.Single<DisplayCaseRules> TYPE =
            new ContentType.Single<>(
                    "display_case", "display_case.yml", false, DisplayCaseRules::parse);

    /**
     * Display slot count = base + bonus of the highest stage the highest reputation tier reached
     * (D11: highest tier ever reached, so it does not shrink when the season changes).
     */
    public int slotCount(int bestTier) {
        int bonus = 0;
        for (Map.Entry<Integer, Integer> e : repBonus.entrySet()) {
            if (bestTier >= e.getKey()) {
                bonus = Math.max(bonus, e.getValue());
            }
        }
        return baseSlots + bonus;
    }

    /** Theme slot count = base + donor tier extra slots (D14, DonorTier.extraThemeSlots). */
    public int themeSlotCount(int donorExtraSlots) {
        return baseThemeSlots + Math.max(0, donorExtraSlots);
    }

    /**
     * Slots to revert to the default theme when theme slots shrink (D14). Counts themed slots in
     * slot order and returns only those beyond the theme slot count. Does not touch the displays
     * themselves.
     *
     * @param themedSlots display slot numbers holding a non-default theme
     */
    public static List<Integer> themesToRevert(Collection<Integer> themedSlots, int themeSlots) {
        List<Integer> sorted = new ArrayList<>(themedSlots);
        sorted.sort(null);
        int keep = Math.max(0, themeSlots);
        return sorted.size() <= keep ? List.of() : List.copyOf(sorted.subList(keep, sorted.size()));
    }

    /** Whether a display slot number is within the slot count (slot starts at 0). */
    public static boolean slotOpen(int slot, int slotCount) {
        return slot >= 0 && slot < slotCount;
    }

    /**
     * Part budget check: adds part counts in slot order and, from the slot that exceeds the budget
     * on, switches to the simple rig (1 part). If even the simple rig exceeds it, the slot is not
     * shown.
     *
     * @param parts part count of each rig, in slot order
     * @return per slot: 0 = original rig, 1 = simple rig, 2 = not shown
     */
    public static int[] budget(List<Integer> parts, int budget, boolean hasSimple) {
        int[] result = new int[parts.size()];
        int used = 0;
        for (int i = 0; i < parts.size(); i++) {
            int p = parts.get(i);
            if (used + p <= budget) {
                used += p;
            } else if (hasSimple && used + 1 <= budget) {
                used += 1;
                result[i] = 1;
            } else {
                result[i] = 2;
            }
        }
        return result;
    }

    public Optional<Theme> theme(@Nullable HarborId cosmetic) {
        if (cosmetic == null) {
            return Optional.of(defaultTheme);
        }
        return themes.stream().filter(t -> cosmetic.equals(t.cosmetic())).findFirst();
    }

    static DisplayCaseRules parse(YamlNode node, ParseContext ctx) {
        node.allowOnly(
                Set.of(
                        "base_slots",
                        "rep_bonus",
                        "base_theme_slots",
                        "part_budget",
                        "view_radius",
                        "info_seconds",
                        "swim_radius",
                        "simple_rig",
                        "default_theme",
                        "themes"));
        Map<Integer, Integer> bonus = new TreeMap<>();
        node.get("rep_bonus")
                .ifPresent(
                        b ->
                                b.entries()
                                        .forEach(
                                                (k, v) -> {
                                                    try {
                                                        bonus.put(Integer.parseInt(k), v.asInt());
                                                    } catch (NumberFormatException e) {
                                                        v.error("평판 등급은 숫자여야 합니다: " + k);
                                                    }
                                                }));
        RigId simple = null;
        Optional<YamlNode> sr = node.get("simple_rig");
        if (sr.isPresent()) {
            Optional<HarborId> id = sr.get().asId(RigId.PREFIX);
            if (id.isPresent()) {
                simple = new RigId(id.get());
                ctx.ref(RigDef.TYPE.name(), id.get(), sr.get().pos());
            }
        }
        Theme def =
                node.get("default_theme")
                        .map(t -> theme(t, ctx, false))
                        .orElse(new Theme(null, "minecraft:sand", null));
        List<Theme> themes = new ArrayList<>();
        Set<HarborId> seen = new HashSet<>();
        for (YamlNode t : node.list("themes")) {
            Theme theme = theme(t, ctx, true);
            HarborId cosmetic = theme.cosmetic();
            if (cosmetic != null && !seen.add(cosmetic)) {
                t.error("테마 코스메틱이 겹칩니다: " + cosmetic);
            }
            themes.add(theme);
        }
        return new DisplayCaseRules(
                node.integer("base_slots", 6, 0, 64),
                Map.copyOf(bonus),
                node.integer("base_theme_slots", 1, 0, 64),
                node.integer("part_budget", 120, 1, 2000),
                node.number("view_radius", 32),
                node.integer("info_seconds", 5, 1, 60),
                node.number("swim_radius", 0.25),
                simple,
                def,
                List.copyOf(themes));
    }

    private static Theme theme(YamlNode t, ParseContext ctx, boolean needsCosmetic) {
        t.allowOnly(Set.of("cosmetic", "floor", "particle"));
        HarborId cosmetic = null;
        Optional<YamlNode> c = t.get("cosmetic");
        if (c.isPresent()) {
            cosmetic = c.get().asId("cosmetic/").orElse(null);
            if (cosmetic != null) {
                ctx.ref("cosmetic", cosmetic, c.get().pos());
            }
        } else if (needsCosmetic) {
            t.error("themes 항목에는 cosmetic이 있어야 합니다");
        }
        return new Theme(
                cosmetic,
                t.string("floor", "minecraft:sand"),
                t.optString("particle").orElse(null));
    }
}
