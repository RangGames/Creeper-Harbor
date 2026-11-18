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
 * 어시장 광장 랭킹 전광판 (billboard.yml, 추가 제안, docs 20 4.4 '랭킹 전광판'). Season RankingService 캐시(10분 주기)를 읽어
 * pageSeconds마다 다음 페이지를 그린다.
 *
 * @param server 이 서버 ID에서만 띄운다 (비우면 world가 있는 모든 서버)
 * @param lines 페이지당 줄 수
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

    /** 전광판 한 페이지. */
    public sealed interface Page permits BoardPage, RegionPage, WeeklyPage {}

    /** Season 랭킹 보드 한 리그 (rankings.yml id). */
    public record BoardPage(String board, String league) implements Page {}

    /** 지역 필터: 그 지역 어종마다 전체 리그 서버 1위 (Collection 기록 캐시). */
    public record RegionPage(RegionId region) implements Page {}

    /** 이번 주 신기록 (이 서버가 받은 서버 1위 갱신, 주간 초기화 때 비움). */
    public record WeeklyPage() implements Page {}

    public static final BillboardRules NONE =
            new BillboardRules("", "", null, 0, 200, 10, 10, List.of());

    public static final ContentType.Single<BillboardRules> TYPE =
            new ContentType.Single<>("billboard", "billboard.yml", false, BillboardRules::parse);

    public boolean enabled() {
        return at != null && !pages.isEmpty() && !world.isEmpty();
    }

    /** 페이지 번호 (tick은 0부터 늘어나는 페이지 넘김 횟수). */
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
