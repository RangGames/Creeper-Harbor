package wiki.creeper.harbor.core.admin;

import java.sql.PreparedStatement;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import wiki.creeper.harbor.common.db.Database;

/** 관리자 작업 기록 (admin_log). 모든 /harbor 관리 명령은 여기에 남긴다. */
public final class AdminLog {

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
        var _ =
                db.asyncVoid(
                                c -> {
                                    try (PreparedStatement ps =
                                            c.prepareStatement(
                                                    "INSERT INTO admin_log (admin, action, target, detail) VALUES (?, ?, ?, ?)")) {
                                        ps.setString(1, adminId);
                                        ps.setString(2, action);
                                        ps.setString(3, target);
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
}
