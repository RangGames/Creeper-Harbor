package wiki.creeper.harbor.collection.content;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.content.ContentType;
import wiki.creeper.harbor.common.content.ParseContext;
import wiki.creeper.harbor.common.id.RegionId;
import wiki.creeper.harbor.common.yaml.YamlNode;

/**
 * Fish market plaza ranking billboard (billboard.yml, proposed, docs 20 4.4 "ranking billboard").
 * Reads the Season RankingService cache (10-minute cycle) and draws the next page every
 * pageSeconds.
 *
 * @param server shown only on this server ID (blank means every server that has the world)
 * @param lines lines per page
 */
public record BillboardRules(
        String server,
        String world,
        @Nullable Point at,
        float yaw,
        int lineWidth,
        int pageSeconds,
        int lines,
        List<Page> pages) {

    /** One billboard page. */
    public sealed interface Page permits BoardPage, RegionPage, WeeklyPage {}

    /** One league of a Season ranking board (rankings.yml id). */
    public record BoardPage(String board, String league) implements Page {}

    /**
     * Region filter: server #1 in the all league for each species in that region (Collection record
     * cache).
     */
    public record RegionPage(RegionId region) implements Page {}

    /**
     * This week's new records (server #1 updates received by this server, cleared on weekly reset).
     */
    public record WeeklyPage() implements Page {}

    public static final BillboardRules NONE =
            new BillboardRules("", "", null, 0, 200, 10, 10, List.of());

    public static final ContentType.Single<BillboardRules> TYPE =
            new ContentType.Single<>("billboard", "billboard.yml", false, BillboardRules::parse);

    public boolean enabled() {
        return at != null && !pages.isEmpty() && !world.isEmpty();
    }

    /** Page index (tick is the page-turn count, increasing from 0). */
    public static int pageIndex(long tick, int pageCount) {
        return pageCount <= 0 ? -1 : (int) Math.floorMod(tick, (long) pageCount);
    }

    static BillboardRules parse(YamlNode node, ParseContext ctx) {
        node.allowOnly(
                Set.of(
                        "server",
                        "world",
                        "at",
                        "yaw",
                        "line_width",
                        "page_seconds",
                        "lines",
                        "pages"));
        List<Page> pages = new ArrayList<>();
        for (YamlNode p : node.list("pages")) {
            p.allowOnly(Set.of("board", "league", "region", "weekly"));
            if (p.has("board")) {
                String league = p.string("league", "all");
                if (!Set.of("all", "tier1", "tier2").contains(league)) {
                    p.error("league는 all · tier1 · tier2 중 하나입니다: " + league);
                }
                pages.add(new BoardPage(p.string("board"), league));
            } else if (p.has("region")) {
                Optional<RegionId> region =
                        p.get("region").flatMap(r -> r.asId(RegionId.PREFIX)).map(RegionId::new);
                region.ifPresent(r -> ctx.ref("region", r.id(), p.pos()));
                region.ifPresent(r -> pages.add(new RegionPage(r)));
            } else if (p.bool("weekly", false)) {
                pages.add(new WeeklyPage());
            } else {
                p.error("페이지는 board · region · weekly 중 하나가 있어야 합니다");
            }
        }
        return new BillboardRules(
                node.string("server", ""),
                node.string("world", ""),
                node.get("at").flatMap(Point::parse).orElse(null),
                (float) node.number("yaw", 0),
                node.integer("line_width", 200, 50, 1000),
                node.integer("page_seconds", 10, 2, 600),
                node.integer("lines", 10, 1, 20),
                List.copyOf(pages));
    }
}
