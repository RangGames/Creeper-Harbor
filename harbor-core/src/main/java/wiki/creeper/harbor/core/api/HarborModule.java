package wiki.creeper.harbor.core.api;

import org.bukkit.plugin.java.JavaPlugin;
import org.jspecify.annotations.Nullable;

/**
 * Extended by the main class of each game module (Jobs, Fishing …). Fixes the order in which
 * content types, player data, commands and modifiers are registered with Core.
 *
 * <pre>{@code
 * public final class HarborFishingPlugin extends HarborModule {
 *     public String moduleId() { return "fishing"; }
 *     protected void enable(ModuleContext ctx) {
 *         ctx.migrate();
 *         ctx.contentType(FishTable.TYPE);
 *         ctx.listen(new FishingListener(ctx.api()));
 *     }
 * }
 * }</pre>
 */
public abstract class HarborModule extends JavaPlugin {

    private ModuleHost.@Nullable Handle handle;

    /** Module ID (lowercase). Used for the migration folder and history table names. */
    public abstract String moduleId();

    protected abstract void enable(ModuleContext ctx);

    /** Work to do on disable. Scheduled tasks are cancelled automatically. */
    protected void disable() {}

    @Override
    public final void onEnable() {
        ModuleHost.Handle h = ModuleHost.get().attach(this);
        handle = h;
        try {
            enable(h.context());
            h.finishEnable();
        } catch (RuntimeException e) {
            getSLF4JLogger().error("모듈 {} 시작 실패", moduleId(), e);
            h.failEnable(e);
        }
    }

    @Override
    public final void onDisable() {
        try {
            disable();
        } finally {
            ModuleHost.Handle h = handle;
            if (h != null) {
                h.detach();
            }
            handle = null;
        }
    }
}
