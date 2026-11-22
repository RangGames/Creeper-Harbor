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
 * Removes trap blocks and looks on season transition (docs 20 3.4 "auto-retrieval" last line).
 * Contents are subject to the season reset, so they are not mailed, and traps rows are cleaned up
 * by the Season reset_plan (archive_reset). Reads locations in onArchive (before the originals are
 * deleted) and removes blocks in worlds on this server on the main thread. Trap blocks on other
 * servers are cleaned up when someone opens them (no row). Idempotent. This class is loaded only
 * when Harbor-Season is enabled.
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
