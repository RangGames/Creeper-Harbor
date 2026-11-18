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
 * 개인 어항 전시 규칙 (display_case.yml, 추가 제안, docs 20 4.4 '개인 어항 전시'·0.6 D11·D14). 수치는 모두 제안값.
 *
 * @param baseSlots 기본 전시 칸 ('12. 월드 · 하우징 · 통발' 4장: 기본 6칸)
 * @param repBonus 시즌 간 최고 평판 등급 → 추가 칸 (도달한 가장 높은 단계 하나만, 누적 아님. 5.4 평판 표: 3등급 +2, 5등급 +4)
 * @param baseThemeSlots 기본 테마 칸 (기본 테마가 아닌 테마를 걸 수 있는 칸 수)
 * @param partBudget 플롯당 리그 부품 상한 (넘으면 간이 리그로 바꾼다)
 * @param viewRadius 플롯 밖 이 거리 안 플레이어에게도 보인다
 * @param infoSeconds 방문객 클릭 정보 표시 시간
 * @param swimRadius 수조 안 헤엄 반경 (블록)
 * @param simpleRig 부품 1개짜리 간이 리그 (없으면 상한을 넘는 칸은 표시하지 않음)
 * @param defaultTheme 기본 테마 (모든 칸)
 * @param themes 코스메틱 테마 (Shop 어항 테마 코스메틱 → 외형)
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
     * 어항 테마 (외형만, 성능 없음).
     *
     * @param cosmetic Shop 코스메틱 ID (기본 테마는 null)
     * @param floor 수조 바닥에 까는 Block Display 블록 (minecraft:sand …)
     * @param particle 주변 파티클 (bubble …, 없으면 없음)
     */
    public record Theme(@Nullable HarborId cosmetic, String floor, @Nullable String particle) {}

    public static final ContentType.Single<DisplayCaseRules> TYPE =
            new ContentType.Single<>(
                    "display_case", "display_case.yml", false, DisplayCaseRules::parse);

    /** 전시 칸 수 = 기본 + 최고 평판 등급이 닿은 가장 높은 단계의 보너스 (D11: 최고 도달 등급이라 시즌이 바뀌어도 줄지 않는다). */
    public int slotCount(int bestTier) {
        int bonus = 0;
        for (Map.Entry<Integer, Integer> e : repBonus.entrySet()) {
            if (bestTier >= e.getKey()) {
                bonus = Math.max(bonus, e.getValue());
            }
        }
        return baseSlots + bonus;
    }

    /** 테마 칸 수 = 기본 + 후원 등급 추가 칸 (D14, DonorTier.extraThemeSlots). */
    public int themeSlotCount(int donorExtraSlots) {
        return baseThemeSlots + Math.max(0, donorExtraSlots);
    }

    /**
     * 테마 칸이 줄었을 때 기본 테마로 되돌릴 칸 (D14). 테마가 걸린 칸을 번호 순으로 세어 테마 칸 수를 넘는 칸만 돌려준다. 전시 자체는 건드리지 않는다.
     *
     * @param themedSlots 기본이 아닌 테마가 걸린 전시 칸 번호
     */
    public static List<Integer> themesToRevert(Collection<Integer> themedSlots, int themeSlots) {
        List<Integer> sorted = new ArrayList<>(themedSlots);
        sorted.sort(null);
        int keep = Math.max(0, themeSlots);
        return sorted.size() <= keep ? List.of() : List.copyOf(sorted.subList(keep, sorted.size()));
    }

    /** 전시 칸 번호가 칸 수 안인지 (slot은 0부터). */
    public static boolean slotOpen(int slot, int slotCount) {
        return slot >= 0 && slot < slotCount;
    }

    /**
     * 부품 상한 판정: 칸 순서대로 부품 수를 더하다가 상한을 넘는 칸부터 간이 리그(부품 1개)로 바꾼다. 간이 리그로도 넘으면 표시하지 않는다.
     *
     * @param parts 칸 순서대로 각 리그의 부품 수
     * @return 칸마다 0 = 원래 리그, 1 = 간이 리그, 2 = 표시 안 함
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
