package wiki.creeper.harbor.season.api;

import java.util.List;
import org.bukkit.plugin.Plugin;

/**
 * Season transition hook registration point. Hooks are removed automatically when the registering
 * plugin is disabled.
 */
public interface SeasonHookRegistry {

    void register(Plugin owner, SeasonHook hook);

    void unregister(SeasonHook hook);

    /** Currently registered hooks (registration order). */
    List<SeasonHook> hooks();
}
