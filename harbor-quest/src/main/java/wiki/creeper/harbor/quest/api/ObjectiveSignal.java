package wiki.creeper.harbor.quest.api;

import org.bukkit.entity.Player;

/**
 * Entry point for modules that cannot easily fire an event to advance objectives (docs 20 6.2).
 * Call it on the main thread.
 *
 * <pre>{@code
 * signals.signal(player, "chart_percent", "region:harbor:region/coral_isles", 1);  // counts
 * signals.set(player, "chart_percent", "region/coral_isles", 42);                // reached value
 * }</pre>
 *
 * target is compared with the objective's target notation (ID, region:…, any). An objective with
 * any receives every signal.
 */
public interface ObjectiveSignal {

    /** Adds to the count (counting objectives such as catch and use_item). */
    void signal(Player player, String type, String target, int amount);

    /**
     * Sets the reached value (gauge objectives such as codex and chart_percent). It never goes
     * down.
     */
    void set(Player player, String type, String target, int value);
}
