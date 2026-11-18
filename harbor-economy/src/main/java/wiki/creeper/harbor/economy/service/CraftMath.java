package wiki.creeper.harbor.economy.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.content.type.FishDef;
import wiki.creeper.harbor.common.id.FishId;
import wiki.creeper.harbor.common.model.Grade;
import wiki.creeper.harbor.core.api.HarborItem;
import wiki.creeper.harbor.economy.content.RecipeDef;

/** 제작 계산 (docs 20 7.4 제작). 재료 절약·미끼 장인 보너스·재료 배정. 서버 API 없음 (HarborItem은 레코드), 단위 테스트 대상. */
public final class CraftMath {

    private CraftMath() {}

    /**
     * 인벤토리 칸 하나.
     *
     * @param item Harbor 아이템이면 그 정보, 아니면 null
     * @param vanillaKey Harbor 아이템이 아니면 바닐라 키 (minecraft:iron_ingot), 아니면 null
     */
    public record Slot(
            int index, @Nullable HarborItem item, @Nullable String vanillaKey, int amount) {}

    /** 실제로 필요한 재료 한 줄 (절약 반영 개수). */
    public record Need(RecipeDef.Ingredient ingredient, int count) {}

    /** '장인의 손': 장비(gear) 재료 = 올림(개수 × (1 − 절약)). 절약은 [0, maxSave]로 자르고 최소 1개. */
    public static int materialCount(
            int count, RecipeDef.Category category, double save, double maxSave) {
        if (category != RecipeDef.Category.GEAR || save <= 0) {
            return count;
        }
        double s = Math.clamp(save, 0.0, Math.max(0.0, maxSave));
        return Math.max(1, (int) Math.ceil(count * (1 - s) - 1e-9));
    }

    /** '미끼 장인': 미끼(bait) 레시피 결과 +1/랭크. */
    public static int resultAmount(int amount, RecipeDef.Category category, double baitBonus) {
        if (category != RecipeDef.Category.BAIT || baitBonus <= 0) {
            return amount;
        }
        return amount + (int) Math.floor(baitBonus + 1e-9);
    }

    public static List<Need> needs(RecipeDef recipe, double save, double maxSave) {
        List<Need> needs = new ArrayList<>();
        for (RecipeDef.Ingredient ingredient : recipe.ingredients()) {
            needs.add(
                    new Need(
                            ingredient,
                            materialCount(ingredient.count(), recipe.category(), save, maxSave)));
        }
        return needs;
    }

    /** 칸의 아이템이 재료 조건에 맞는지. */
    public static boolean matches(
            RecipeDef.Ingredient ingredient,
            Slot slot,
            Function<FishId, Optional<FishDef>> fishLookup) {
        HarborItem item = slot.item();
        return switch (ingredient) {
            case RecipeDef.Item(var id, var _) ->
                    item instanceof HarborItem.Plain plain && plain.id().equals(id);
            case RecipeDef.Vanilla(var key, var _) -> item == null && key.equals(slot.vanillaKey());
            case RecipeDef.FishMatch match -> {
                if (!(item instanceof HarborItem.Fish fish)) {
                    yield false;
                }
                if (!match.fishAny().isEmpty() && !match.fishAny().contains(fish.species())) {
                    yield false;
                }
                Grade min = match.gradeMin();
                if (min == null && match.region() == null) {
                    yield true;
                }
                Optional<FishDef> def = fishLookup.apply(fish.species());
                if (def.isEmpty()) {
                    yield false;
                }
                yield (min == null || def.get().grade().isAtLeast(min))
                        && (match.region() == null || def.get().region().equals(match.region()));
            }
        };
    }

    /**
     * 재료를 칸에 배정한다. 구체적인 재료(아이템·바닐라 → 어종 지정 → 높은 등급 조건)부터 채워서, 넓은 조건이 좁은 조건의 재료를 먼저 가져가지 않게 한다. 칸은
     * 앞에서부터 쓴다.
     *
     * @return 칸 번호 → 꺼낼 개수. 모자라면 빈 값
     */
    public static Optional<Map<Integer, Integer>> allocate(
            List<Need> needs, List<Slot> slots, Function<FishId, Optional<FishDef>> fishLookup) {
        Map<Integer, Integer> left = new LinkedHashMap<>();
        for (Slot slot : slots) {
            left.put(slot.index(), slot.amount());
        }
        Map<Integer, Integer> taken = new LinkedHashMap<>();
        List<Need> ordered =
                needs.stream().sorted(Comparator.comparingInt(CraftMath::specificity)).toList();
        for (Need need : ordered) {
            int remaining = need.count();
            for (Slot slot : slots) {
                if (remaining <= 0) {
                    break;
                }
                int available = left.getOrDefault(slot.index(), 0);
                if (available <= 0 || !matches(need.ingredient(), slot, fishLookup)) {
                    continue;
                }
                int use = Math.min(available, remaining);
                left.put(slot.index(), available - use);
                taken.merge(slot.index(), use, Integer::sum);
                remaining -= use;
            }
            if (remaining > 0) {
                return Optional.empty();
            }
        }
        return Optional.of(taken);
    }

    /** 작을수록 먼저 배정. */
    static int specificity(Need need) {
        return switch (need.ingredient()) {
            case RecipeDef.Item _ -> 0;
            case RecipeDef.Vanilla _ -> 0;
            case RecipeDef.FishMatch match -> {
                if (!match.fishAny().isEmpty()) {
                    yield 1;
                }
                Grade min = match.gradeMin();
                // 등급 조건이 높을수록 좁다: mythic 2 … common 7
                int grade =
                        min == null
                                ? 7
                                : switch (min) {
                                    case MYTHIC -> 1;
                                    case LEGENDARY -> 2;
                                    case EPIC -> 3;
                                    case RARE -> 4;
                                    case UNCOMMON -> 5;
                                    case COMMON -> 6;
                                };
                yield 1 + grade + (match.region() == null ? 1 : 0);
            }
        };
    }
}
