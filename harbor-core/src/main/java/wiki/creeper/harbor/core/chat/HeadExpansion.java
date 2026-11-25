package wiki.creeper.harbor.core.chat;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

/**
 * PlaceholderAPI expansion {@code %harbor_head…%} (proposed; only loaded when PlaceholderAPI is
 * enabled). Values are MiniMessage text (the head needs a font and hex colors, which legacy codes
 * cannot carry), so they render in plugins that parse MiniMessage; Harbor's own chat format reads
 * them as MiniMessage (PlaceholderApiHook).
 *
 * <ul>
 *   <li>{@code %harbor_head%} the player's head · {@code %harbor_head_12%} at 12 pixels
 *   <li>{@code %harbor_head_Name%} another player's · {@code %harbor_head_Name_12%} both
 * </ul>
 */
public final class HeadExpansion extends PlaceholderExpansion {

    private final ChatHeads heads;

    private HeadExpansion(ChatHeads heads) {
        this.heads = heads;
    }

    /** Registers the expansion (call only when PlaceholderAPI is enabled). */
    public static void register(ChatHeads heads) {
        new HeadExpansion(heads).register();
    }

    @Override
    public String getIdentifier() {
        return "harbor";
    }

    @Override
    public String getAuthor() {
        return "Creeper Harbor";
    }

    @Override
    public String getVersion() {
        return "1";
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public @Nullable String onRequest(@Nullable OfflinePlayer player, String params) {
        if (!params.startsWith("head")) {
            return null;
        }
        Player self = player == null ? null : player.getPlayer();
        Component head = heads.fromPlaceholder(self, params.substring("head".length()));
        return MiniMessage.miniMessage().serialize(head);
    }
}
