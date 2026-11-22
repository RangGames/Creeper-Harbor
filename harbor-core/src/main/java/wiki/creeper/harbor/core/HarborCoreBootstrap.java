package wiki.creeper.harbor.core;

import io.papermc.paper.plugin.bootstrap.BootstrapContext;
import io.papermc.paper.plugin.bootstrap.PluginBootstrap;

/**
 * Datapack registry entry registration stage (custom enchantments, Dialog). Fishing-rod
 * enchantments will be registered here once Phase 1 technical validation ('8. System detailed
 * design' ch. 13) confirms the Registry API works. Nothing to register yet.
 */
public final class HarborCoreBootstrap implements PluginBootstrap {

    @Override
    public void bootstrap(BootstrapContext context) {
        context.getLogger().info("Harbor-Core 부트스트랩");
    }
}
