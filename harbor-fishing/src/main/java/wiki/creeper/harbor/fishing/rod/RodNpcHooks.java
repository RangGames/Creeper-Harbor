package wiki.creeper.harbor.fishing.rod;

import org.bukkit.plugin.Plugin;
import wiki.creeper.harbor.core.api.HarborApi;
import wiki.creeper.harbor.npc.api.ActionResult;
import wiki.creeper.harbor.npc.api.DialogService;

/**
 * NPC integration (Fishing → NPC optional dependency, proposed): the tackle shop dialog action
 * {@code fishing:enchant} opens the rod-only enchant screen ({@link RodEnchanter}). The same way
 * Economy registers {@code shop:open}. This class is loaded only when Harbor-Npc is enabled.
 */
public final class RodNpcHooks {

    private RodNpcHooks() {}

    public static void register(Plugin plugin, HarborApi api, RodEnchanter enchanter) {
        api.service(DialogService.class)
                .ifPresent(
                        dialogs ->
                                dialogs.registerAction(
                                        plugin,
                                        "fishing:enchant",
                                        (ctx, args) -> {
                                            String reason = enchanter.open(ctx.player());
                                            return reason == null
                                                    ? ActionResult.HANDOFF
                                                    : ActionResult.failMessage(reason);
                                        }));
    }
}
