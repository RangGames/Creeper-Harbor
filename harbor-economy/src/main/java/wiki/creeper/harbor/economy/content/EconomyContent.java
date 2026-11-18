package wiki.creeper.harbor.economy.content;

import java.util.List;
import wiki.creeper.harbor.common.content.ContentCheck;
import wiki.creeper.harbor.common.content.ContentType;
import wiki.creeper.harbor.common.content.type.ItemDef;

/** Economy 콘텐츠 형식. 서버 API를 쓰지 않아 harbor-tools(CI)도 같은 코드로 검사한다. */
public final class EconomyContent {

    public static final List<ContentType<?>> TYPES =
            List.of(ShopDef.TYPE, RecipeDef.TYPE, StationDef.TYPE, AuctionRefs.TYPE);

    /** 레시피 결과 아이템이 콘텐츠에 있고, 주간 제한이 있는 레시피는 장소가 정해져 있는지 등. */
    public static final ContentCheck<RecipeDef> RECIPE_CHECK =
            (recipe, check) -> {
                var result = check.snapshot().get(ItemDef.TYPE, recipe.result().id());
                if (result.isPresent()
                        && recipe.category() == RecipeDef.Category.FOOD
                        && result.get().type() != ItemDef.Type.FOOD) {
                    check.error("요리(food) 레시피의 결과는 type: food 아이템이어야 합니다: " + recipe.result());
                }
                if (recipe.station() == RecipeDef.Station.COOKING
                        && recipe.category() != RecipeDef.Category.FOOD) {
                    check.error("요리 화로(cooking) 레시피는 category: food여야 합니다");
                }
                for (RecipeDef.Ingredient ingredient : recipe.ingredients()) {
                    if (ingredient instanceof RecipeDef.Item(var item, var _)
                            && item.equals(recipe.result())) {
                        check.error("결과 아이템을 재료로 쓸 수 없습니다: " + item);
                    }
                }
            };

    private EconomyContent() {}
}
