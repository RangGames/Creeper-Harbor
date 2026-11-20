package wiki.creeper.harbor.world.housing;

import java.util.UUID;
import org.bukkit.entity.Player;
import wiki.creeper.harbor.world.api.PlotInfo;
import wiki.creeper.harbor.world.api.PlotPermission;

/** 플롯 권한 판정. 주인, 공동 건축자, 방문 정책(공개 · 건축자만 · 비공개). harbor.world.bypass는 모두 허용. */
final class PlotAccess {

    private PlotAccess() {}

    static boolean can(Player player, PlotInfo plot, PlotPermission permission) {
        if (player.hasPermission("harbor.world.bypass")) {
            return true;
        }
        UUID uuid = player.getUniqueId();
        return switch (permission) {
            case VISIT ->
                    switch (plot.visitPolicy()) {
                        case PUBLIC -> true;
                        case MEMBERS -> plot.isMember(uuid);
                        case PRIVATE -> plot.owner().equals(uuid);
                    };
            case BUILD, EDIT_DISPLAY -> plot.isMember(uuid);
            case MANAGE -> plot.owner().equals(uuid);
        };
    }
}
