package wiki.creeper.harbor.core.admin;

import java.sql.PreparedStatement;
import java.util.UUID;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import wiki.creeper.harbor.common.db.Database;
import wiki.creeper.harbor.core.api.AdminLogService;

/**
 * Admin action log (admin_log). Every /harbor admin command is recorded here, and it is exposed as
 * {@link AdminLogService} for actions without a command sender (Skript, integrations).
 */
public final class AdminLog implements AdminLogService {

    private static final int ACTOR_MAX = 36;
    private static final int KEY_MAX = 64;

    private final Database db;
    private final Logger log;

    public AdminLog(Database db, Logger log) {
        this.db = db;
        this.log = log;
    }

    public void record(
            CommandSender admin, String action, @Nullable String target, @Nullable String detail) {
        String adminId =
                admin instanceof Player player ? player.getUniqueId().toString() : "CONSOLE";
        insert(adminId, action, target, detail);
    }

    @Override
    public void record(String actor, String action, @Nullable UUID target, String detail) {
        insert(
                clip(actor, ACTOR_MAX),
                clip(action, KEY_MAX),
                target == null ? null : target.toString(),
                detail);
    }

    private void insert(
            String adminId, String action, @Nullable String target, @Nullable String detail) {
        String clippedTarget = target == null ? null : clip(target, KEY_MAX);
        var _ =
                db.asyncVoid(
                                c -> {
                                    try (PreparedStatement ps =
                                            c.prepareStatement(
                                                    "INSERT INTO admin_log (admin, action, target, detail) VALUES (?, ?, ?, ?)")) {
                                        ps.setString(1, adminId);
                                        ps.setString(2, action);
                                        ps.setString(3, clippedTarget);
                                        ps.setString(4, detail);
                                        ps.executeUpdate();
                                    }
                                })
                        .exceptionally(
                                e -> {
                                    log.error(
                                            "admin_log 기록 실패: {} {} {}",
                                            adminId,
                                            action,
                                            target,
                                            e);
                                    return null;
                                });
    }

    static String clip(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }
}
