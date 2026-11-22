package wiki.creeper.harbor.economy;

import org.bukkit.Material;
import wiki.creeper.harbor.common.content.ContentCheck;
import wiki.creeper.harbor.common.content.type.Balance;
import wiki.creeper.harbor.economy.content.EconomyBalance;
import wiki.creeper.harbor.economy.content.RecipeDef;
import wiki.creeper.harbor.economy.content.ShopDef;

/** Content checks that need the server API (vanilla item keys). harbor-tools only checks format. */
final class ServerChecks {

    private ServerChecks() {}

    private static boolean isItem(String key) {
        Material material = Material.matchMaterial(key);
        return material != null && material.isItem() && !material.isAir();
    }

    static final ContentCheck<RecipeDef> RECIPE_VANILLA =
            (recipe, check) -> {
                for (RecipeDef.Ingredient ingredient : recipe.ingredients()) {
                    if (ingredient instanceof RecipeDef.Vanilla(var key, var _) && !isItem(key)) {
                        check.error("없는 바닐라 아이템입니다: " + key);
                    }
                }
            };

    static final ContentCheck<ShopDef> SHOP_VANILLA =
            (shop, check) -> {
                for (ShopDef.Entry entry : shop.entries()) {
                    String key = entry.vanilla();
                    if (key != null && !isItem(key)) {
                        check.error("없는 바닐라 아이템입니다: " + key);
                    }
                }
            };

    /** Whether auction ratios and fees make sense (economy.auction). */
    static final ContentCheck<Balance> BALANCE =
            (balance, check) -> {
                EconomyBalance b = new EconomyBalance(balance);
                if (b.auctionMinRatio() <= 0 || b.auctionMaxRatio() < b.auctionMinRatio()) {
                    check.error("economy.auction.min_ratio · max_ratio가 잘못됐습니다");
                }
                if (b.auctionListFee() < 0
                        || b.auctionListFee() >= 1
                        || b.auctionSaleFee() < 0
                        || b.auctionSaleFee() >= 1) {
                    check.error("economy.auction 수수료는 0 이상 1 미만이어야 합니다");
                }
                if (b.auctionDurationHours() <= 0) {
                    check.error("economy.auction.duration_h는 0보다 커야 합니다");
                }
            };
}
