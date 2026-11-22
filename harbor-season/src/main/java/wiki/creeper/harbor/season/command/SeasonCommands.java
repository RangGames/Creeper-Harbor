package wiki.creeper.harbor.season.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import wiki.creeper.harbor.core.api.HarborApi;
import wiki.creeper.harbor.core.task.Tasks;
import wiki.creeper.harbor.season.SeasonContentView;
import wiki.creeper.harbor.season.clock.SeasonClockManager;
import wiki.creeper.harbor.season.limit.WeeklyLimitManager;
import wiki.creeper.harbor.season.ranking.RankingManager;
import wiki.creeper.harbor.season.title.TitleManager;
import wiki.creeper.harbor.season.transition.TransitionEngine;
import wiki.creeper.harbor.season.ui.HallOfFameMenu;
import wiki.creeper.harbor.season.ui.RankingMenu;
import wiki.creeper.harbor.season.ui.TitleMenu;

/**
 * /시즌, /랭킹, /명예의전당, /칭호, /harbor season … (docs 20 11.5). Every admin command is recorded in
 * admin_log. Fine-grained permissions are harbor.admin.season.&lt;command&gt; (transition · week ·
 * ranking · title · limit).
 */
public final class SeasonCommands {

    private final SeasonPlayerCommands player;
    private final SeasonAdminCommands admin;

    public SeasonCommands(
            HarborApi api,
            SeasonContentView content,
            SeasonClockManager clock,
            RankingManager rankings,
            TitleManager titles,
            WeeklyLimitManager limits,
            TransitionEngine engine,
            RankingMenu rankingMenu,
            HallOfFameMenu hofMenu,
            TitleMenu titleMenu,
            Tasks tasks) {
        this.player =
                new SeasonPlayerCommands(
                        api, content, clock, rankings, titles, rankingMenu, hofMenu, titleMenu);
        this.admin =
                new SeasonAdminCommands(
                        api, content, clock, rankings, titles, limits, engine, tasks);
    }

    // ------------------------------------------------------------------ player commands

    public void registerPlayerCommands(Commands commands) {
        player.register(commands);
    }

    // ------------------------------------------------------------------ admin commands

    /** /harbor season … (the harbor.admin.season permission is applied by Core). */
    public LiteralArgumentBuilder<CommandSourceStack> adminCommand() {
        return admin.build();
    }
}
