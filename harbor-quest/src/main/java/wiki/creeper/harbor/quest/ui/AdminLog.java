package wiki.creeper.harbor.quest.ui;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import wiki.creeper.harbor.core.api.HarborApi;

/**
 * Quest admin log (admin_log, action is {@code quest.<action>}). Shared by commands, the admin GUI
 * and the editor. DB writes run on a virtual thread.
 */
final class AdminLog {

    private final HarborApi api;

    AdminLog(HarborApi api) {
        this.api = api;
    }

    void log(CommandSender sender, String action, String target, String detail) {
        String admin = sender instanceof Player p ? p.getUniqueId().toString() : "CONSOLE";
        var _ =
                api.database()
                        .asyncVoid(
                                c -> {
                                    try (var ps =
                                            c.prepareStatement(
                                                    "INSERT INTO admin_log (admin, action, target, detail) VALUES (?, ?, ?, ?)")) {
                                        ps.setString(1, admin);
                                        ps.setString(2, "quest." + action);
                                        ps.setString(3, target);
                                        ps.setString(4, detail);
                                        ps.executeUpdate();
                                    }
                                });
    }
}
