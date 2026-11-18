package wiki.creeper.harbor.collection.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.scheduler.BukkitTask;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.collection.api.RecordEntry;
import wiki.creeper.harbor.collection.content.BillboardRules;
import wiki.creeper.harbor.collection.content.Point;
import wiki.creeper.harbor.common.content.type.FishDef;
import wiki.creeper.harbor.common.content.type.RegionDef;
import wiki.creeper.harbor.core.api.HarborApi;
import wiki.creeper.harbor.core.api.MessageService;
import wiki.creeper.harbor.core.api.event.ContentReloadedEvent;
import wiki.creeper.harbor.core.task.Tasks;
import wiki.creeper.harbor.season.api.League;
import wiki.creeper.harbor.season.api.RankEntry;
import wiki.creeper.harbor.season.api.RankingBoard;
import wiki.creeper.harbor.season.api.RankingService;
import wiki.creeper.harbor.season.api.SeasonServices;
import wiki.creeper.harbor.season.content.RankingBoardDef;

/**
 * 어시장 광장 랭킹 전광판 (docs 20 4.4 '랭킹 전광판', billboard.yml). Text Display 하나에 page_seconds마다 다음 페이지를 그린다.
 *
 * <ul>
 *   <li>보드 페이지: Season RankingService 캐시(10분 주기 집계, DB 접근 없음)
 *   <li>지역 페이지: 그 지역 어종마다 전체 리그 서버 1위 (Collection 기록 캐시)
 *   <li>이번 주 신기록: 이 서버가 받은 서버 1위 갱신 (주간 초기화 때 비움)
 * </ul>
 */
public final class Billboard implements Listener {

    private final HarborApi api;
    private final Tasks tasks;
    private final CollectionManager codex;
    private @Nullable TextDisplay display;
    private @Nullable BukkitTask task;
    private long page;

    public Billboard(HarborApi api, Tasks tasks, CollectionManager codex) {
        this.api = api;
        this.tasks = tasks;
        this.codex = codex;
    }

    private BillboardRules rules() {
        return api.content().snapshot().single(BillboardRules.TYPE);
    }

    public void start() {
        schedule();
    }

    /** 콘텐츠 리로드 (주기가 바뀌었을 수 있다). */
    @EventHandler
    public void onReload(ContentReloadedEvent event) {
        reload();
    }

    public void reload() {
        stop();
        schedule();
    }

    private void schedule() {
        BillboardRules rules = rules();
        task = tasks.timer(40, rules.pageSeconds() * 20L, this::flip);
    }

    public void stop() {
        BukkitTask t = task;
        if (t != null) {
            t.cancel();
        }
        TextDisplay d = display;
        if (d != null) {
            d.remove();
        }
        display = null;
    }

    private void flip() {
        BillboardRules rules = rules();
        if (!rules.enabled()
                || (!rules.server().isEmpty() && !rules.server().equals(api.infra().serverId()))) {
            return;
        }
        TextDisplay d = ensure(rules);
        if (d == null) {
            return;
        }
        int index = BillboardRules.pageIndex(page++, rules.pages().size());
        d.text(render(rules, rules.pages().get(index)));
    }

    private @Nullable TextDisplay ensure(BillboardRules rules) {
        TextDisplay d = display;
        if (d != null && d.isValid()) {
            return d;
        }
        World world = Bukkit.getWorld(rules.world());
        Point at = rules.at();
        if (world == null || at == null) {
            return null;
        }
        Location loc = new Location(world, at.x(), at.y(), at.z(), rules.yaw(), 0);
        if (!world.isChunkLoaded(loc.getBlockX() >> 4, loc.getBlockZ() >> 4)) {
            return null;
        }
        TextDisplay spawned =
                world.spawn(
                        loc,
                        TextDisplay.class,
                        e -> {
                            e.setPersistent(false);
                            e.setBillboard(Display.Billboard.FIXED);
                            e.setLineWidth(rules.lineWidth());
                        });
        display = spawned;
        return spawned;
    }

    Component render(BillboardRules rules, BillboardRules.Page page) {
        MessageService msg = api.messages();
        List<Component> lines = new ArrayList<>();
        switch (page) {
            case BillboardRules.BoardPage(String board, String leagueKey) -> {
                RankingService rankings = SeasonServices.rankings();
                League league = League.parse(leagueKey).orElse(League.ALL);
                String name = rankings.boardDef(board).map(RankingBoardDef::name).orElse(board);
                lines.add(
                        msg.get(
                                "collection.billboard.board_title",
                                Placeholder.parsed("name", name),
                                Placeholder.unparsed("league", leagueKey)));
                RankingBoard top = rankings.board(board, league);
                boolean size =
                        rankings.boardDef(board)
                                .map(d -> d.source().equals("records"))
                                .orElse(false);
                for (RankEntry e : top.entries()) {
                    if (lines.size() > rules.lines()) {
                        break;
                    }
                    lines.add(
                            msg.get(
                                    "collection.billboard.board_line",
                                    Placeholder.unparsed("rank", String.valueOf(e.rank())),
                                    Placeholder.unparsed("player", e.name()),
                                    Placeholder.unparsed(
                                            "value",
                                            size
                                                    ? CollectionManager.fmt(e.value()) + "cm"
                                                    : String.format(
                                                            Locale.ROOT, "%,.0f", e.value()))));
                }
                if (top.entries().isEmpty()) {
                    lines.add(msg.get("collection.billboard.empty"));
                }
            }
            case BillboardRules.RegionPage(var region) -> {
                String regionName =
                        api.content()
                                .snapshot()
                                .get(RegionDef.TYPE, region.id())
                                .map(RegionDef::name)
                                .orElse(region.id().name());
                lines.add(
                        msg.get(
                                "collection.billboard.region_title",
                                Placeholder.unparsed("region", regionName)));
                List<FishDef> fish = new ArrayList<>();
                codex.regionFish(region)
                        .forEach(
                                id ->
                                        api.content()
                                                .snapshot()
                                                .get(FishDef.TYPE, id.id())
                                                .ifPresent(fish::add));
                fish.sort(Comparator.comparingInt(FishDef::no));
                for (FishDef f : fish) {
                    if (lines.size() > rules.lines()) {
                        break;
                    }
                    Optional<RecordEntry> top =
                            codex.serverTop(f.id(), "all", 1).stream().findFirst();
                    lines.add(
                            top.isPresent()
                                    ? msg.get(
                                            "collection.billboard.region_line",
                                            Placeholder.unparsed("name", f.name()),
                                            Placeholder.unparsed("player", top.get().name()),
                                            Placeholder.unparsed(
                                                    "size",
                                                    CollectionManager.fmt(top.get().size())))
                                    : msg.get(
                                            "collection.billboard.region_none",
                                            Placeholder.unparsed("name", f.name())));
                }
            }
            case BillboardRules.WeeklyPage() -> {
                lines.add(msg.get("collection.billboard.weekly_title"));
                for (CollectionManager.WeeklyRecord w : codex.weeklyRecords()) {
                    if (lines.size() > rules.lines()) {
                        break;
                    }
                    String name =
                            api.content()
                                    .snapshot()
                                    .get(FishDef.TYPE, w.fish().id())
                                    .map(FishDef::name)
                                    .orElse(w.fish().id().name());
                    lines.add(
                            msg.get(
                                    "collection.billboard.weekly_line",
                                    Placeholder.unparsed("name", name),
                                    Placeholder.unparsed("player", w.entry().name()),
                                    Placeholder.unparsed(
                                            "size", CollectionManager.fmt(w.entry().size()))));
                }
                if (lines.size() == 1) {
                    lines.add(msg.get("collection.billboard.empty"));
                }
            }
        }
        return Component.join(JoinConfiguration.newlines(), lines);
    }
}
