package wiki.creeper.harbor.fishing.trap;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import wiki.creeper.harbor.season.api.SeasonHook;
import wiki.creeper.harbor.season.api.SeasonHookContext;
import wiki.creeper.harbor.season.api.SeasonServices;

/**
 * 시즌 전환 때 통발 블록·외형을 지운다 (docs 20 3.4 '자동 회수' 마지막 줄). 내용물은 시즌 초기화 대상이라 우편으로 보내지 않고, traps 행은 Season
 * reset_plan(archive_reset)이 정리한다. onArchive(원본 삭제 전)에서 위치를 읽어 이 서버에 있는 월드의 블록을 메인 스레드에서 지운다. 다른
 * 서버의 통발 블록은 누가 열 때(행 없음) 정리된다. 여러 번 불려도 결과가 같다. Harbor-Season이 켜져 있을 때만 이 클래스를 불러온다.
 */
final class TrapSeasonHook implements SeasonHook {

    private record Spot(long id, String world, int x, int y, int z) {}

    private final TrapManager traps;

    private TrapSeasonHook(TrapManager traps) {
        this.traps = traps;
    }

    static void register(Plugin plugin, TrapManager traps) {
        SeasonServices.hooks().register(plugin, new TrapSeasonHook(traps));
    }

    @Override
    public String id() {
        return "fishing.traps";
    }

    @Override
    public void onArchive(SeasonHookContext ctx) throws Exception {
        List<Spot> spots = new ArrayList<>();
        ctx.database()
                .txVoid(
                        c -> {
                            try (PreparedStatement ps =
                                    c.prepareStatement(
                                            "SELECT id, world, x, y, z FROM traps WHERE season = ?")) {
                                ps.setInt(1, ctx.fromSeason());
                                try (ResultSet rs = ps.executeQuery()) {
                                    while (rs.next()) {
                                        spots.add(
                                                new Spot(
                                                        rs.getLong(1),
                                                        rs.getString(2),
                                                        rs.getInt(3),
                                                        rs.getInt(4),
                                                        rs.getInt(5)));
                                    }
                                }
                            }
                        });
        ctx.report("통발 블록 정리 대상 " + spots.size() + "개");
        if (ctx.dryRun() || spots.isEmpty()) {
            return;
        }
        Runnable clear =
                () -> spots.forEach(s -> traps.demolish(s.world(), s.x(), s.y(), s.z(), s.id()));
        if (Bukkit.isPrimaryThread()) {
            clear.run();
        } else {
            var _ = traps.tasks().sync(clear);
        }
    }
}
