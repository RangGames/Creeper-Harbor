package wiki.creeper.harbor.world.housing;

import java.util.UUID;
import org.bukkit.entity.Player;
import wiki.creeper.harbor.world.api.PlotInfo;
import wiki.creeper.harbor.world.api.PlotPermission;

/**
 * Plot permission checks. Owner, co-builders, visit policy (public · builders only · private).
 * harbor.world.bypass allows everything.
 */
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
