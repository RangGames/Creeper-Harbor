package wiki.creeper.harbor.core.api;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.event.Listener;
import wiki.creeper.harbor.common.content.ContentCheck;
import wiki.creeper.harbor.common.content.ContentType;
import wiki.creeper.harbor.core.task.Tasks;

/** Where a module registers its things with Core. Use only in {@link HarborModule#enable}. */
public interface ModuleContext {

    HarborModule plugin();

    HarborApi api();

    /** Task scheduler that automatically cancels remaining tasks when the module is disabled. */
    Tasks tasks();

    /**
     * Registers a content type. Content is reloaded after the module is enabled (failure aborts
     * server startup).
     */
    void contentType(ContentType<?> type);

    <T> void contentCheck(ContentType<T> type, ContentCheck<T> check);

    void playerComponent(PlayerComponent<?> component);

    /**
     * {@code /harbor <name> …} admin subcommand. Permission harbor.admin.<name> is applied
     * automatically.
     */
    void adminCommand(LiteralArgumentBuilder<CommandSourceStack> command);

    /** Applies this module's DB migrations (db/migration/<module ID>). */
    void migrate();

    void listen(Listener listener);

    /** Pre-transfer hook. Unregistered automatically when the module is disabled. */
    void handoffBlocker(HandoffBlocker blocker);

    /**
     * Includes items held outside the inventory in autosaves ({@link ItemCustody}). Unregistered
     * automatically when the module is disabled.
     */
    void itemCustody(ItemCustody custody);
}
