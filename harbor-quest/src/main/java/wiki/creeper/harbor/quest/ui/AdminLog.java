package wiki.creeper.harbor.quest.ui;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import wiki.creeper.harbor.core.api.HarborApi;

/** 퀘스트 관리 기록 (admin_log, action은 {@code quest.<동작>}). 명령·관리 GUI·편집기가 같이 쓴다. DB 쓰기는 가상 스레드. */
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
