package wiki.creeper.harbor.core.api;

import java.time.Duration;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.entity.Player;

/** Helpers for action bar, boss bar and title output, plus the custom font glyph registry. */
public interface Ui {

    void actionBar(Player player, Component message);

    /**
     * Action bar for persistent guidance (quest direction, etc.) (proposed). Not sent if another
     * action bar went out within {@code quiet}, so that text isn't overwritten. This call doesn't
     * count as another action bar's timestamp.
     *
     * @return true if sent
     */
    boolean ambientActionBar(Player player, Component message, Duration quiet);

    void title(
            Player player,
            Component title,
            Component subtitle,
            Duration fadeIn,
            Duration stay,
            Duration fadeOut);

    /** Creates and shows a boss bar. Hide it with {@link #hideBossBar}. */
    BossBar showBossBar(Player player, Component name, float progress, BossBar.Color color);

    void hideBossBar(Player player, BossBar bar);

    /** Registers a glyph (gauge or icon character). Overwrites one with the same name. */
    void registerGlyph(String name, String glyph);

    /** Tag used in MiniMessage as {@code <glyph:name>}. */
    TagResolver glyphs();
}
