package wiki.creeper.harbor.world.bridge;

import java.util.UUID;
import org.bukkit.Bukkit;
import wiki.creeper.harbor.shop.api.CosmeticService;
import wiki.creeper.harbor.shop.content.CosmeticDef;

/**
 * Shop 연결 (선택). 선박 스킨(BOAT_SKIN)을 장착했으면 Shop이 탑승 때 스킨 리그를 보트에 태우므로, World는 기본 장식 리그를 띄우지 않는다 (장식이
 * 겹치지 않게). Harbor-Shop이 있을 때만 이 클래스를 불러온다.
 */
public final class ShopBridge {

    private ShopBridge() {}

    public static boolean boatSkinEquipped(UUID player) {
        CosmeticService cosmetics = Bukkit.getServicesManager().load(CosmeticService.class);
        return cosmetics != null
                && cosmetics.equipped(player, CosmeticDef.Category.BOAT_SKIN).isPresent();
    }
}
