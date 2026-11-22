package wiki.creeper.harbor.quest.api;

import java.util.Set;
import java.util.UUID;
import org.bukkit.entity.Player;

/**
 * "Harbor Chronicle" (docs 20 6.4): pieces of the truth. A piece is kept as the permanent player
 * flag chronicle/&lt;id&gt;. Main thread.
 */
public interface ChronicleService {

    /** IDs of the collected pieces (online player). */
    Set<String> pieces(UUID player);

    /** Chronicle GUI (replay chapters). */
    void open(Player player);
}
