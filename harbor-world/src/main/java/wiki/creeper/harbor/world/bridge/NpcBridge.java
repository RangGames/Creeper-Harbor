package wiki.creeper.harbor.world.bridge;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import wiki.creeper.harbor.npc.api.ActionResult;
import wiki.creeper.harbor.npc.api.DialogService;
import wiki.creeper.harbor.npc.api.ReputationService;

/**
 * NPC integration (optional). Registers the best cross-season reputation tier
 * (ReputationService.bestTier, D11: housing size and decor conditions) and dialog action keys.
 * Putting these keys in a dialog file (NPC content/dialogs) shows the choices: {@code
 * world:shipyard} (shipyard), {@code world:housing} (my dock), {@code world:decor_shop} (decor
 * shop), {@code world:airship} (airship routes), {@code world:chart} (sea chart). This class is
 * loaded only when Harbor-NPC is present.
 */
public final class NpcBridge {

    private NpcBridge() {}

    /**
     * Best reputation tier (cached while online, otherwise from the DB). 0 if the service is
     * missing.
     */
    public static CompletableFuture<Integer> bestTier(UUID player) {
        ReputationService rep = Bukkit.getServicesManager().load(ReputationService.class);
        if (rep == null) {
            return CompletableFuture.completedFuture(0);
        }
        if (Bukkit.getPlayer(player) != null) {
            return CompletableFuture.completedFuture(rep.bestTier(player));
        }
        return rep.loadBestTier(player);
    }

    /** Registers dialog actions (actions that open a screen only end the dialog session). */
    public static void registerActions(
            Plugin plugin,
            Consumer<Player> shipyard,
            Consumer<Player> housing,
            Consumer<Player> decorShop,
            Consumer<Player> airship,
            Consumer<Player> chart) {
        Optional<DialogService> dialogs =
                Optional.ofNullable(Bukkit.getServicesManager().load(DialogService.class));
        if (dialogs.isEmpty()) {
            return;
        }
        register(plugin, dialogs.get(), "world:shipyard", shipyard);
        register(plugin, dialogs.get(), "world:housing", housing);
        register(plugin, dialogs.get(), "world:decor_shop", decorShop);
        register(plugin, dialogs.get(), "world:airship", airship);
        register(plugin, dialogs.get(), "world:chart", chart);
    }

    private static void register(
            Plugin plugin, DialogService dialogs, String key, Consumer<Player> open) {
        if (dialogs.hasAction(key)) {
            return;
        }
        dialogs.registerAction(
                plugin,
                key,
                (ctx, args) -> {
                    open.accept(ctx.player());
                    return ActionResult.HANDOFF;
                });
    }
}
