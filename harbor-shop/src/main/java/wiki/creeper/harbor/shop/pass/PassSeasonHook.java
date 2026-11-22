package wiki.creeper.harbor.shop.pass;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import wiki.creeper.harbor.season.api.SeasonHook;
import wiki.creeper.harbor.season.api.SeasonHookContext;
import wiki.creeper.harbor.season.api.SeasonServices;
import wiki.creeper.harbor.season.api.TitleId;
import wiki.creeper.harbor.season.api.TitleService;
import wiki.creeper.harbor.shop.ShopRuntime;
import wiki.creeper.harbor.shop.content.PassDef;
import wiki.creeper.harbor.shop.data.ShopRepository;
import wiki.creeper.harbor.shop.pearl.PearlManager;

/**
 * Season pass close-out (Season SeasonHook, docs 20 12.4 'Season close'). In onArchive at
 * transition step 6 (ARCHIVE), auto-grants the ending season's unclaimed rewards. The
 * season_pass_claim unique key and reference ID (pass:&lt;season&gt;:&lt;level&gt;:&lt;track&gt;)
 * make repeated calls idempotent. Afterwards that season's pass is no longer sold (purchases only
 * accept the current season's pass). season_pass and season_pass_claim rows are only archived and
 * kept by reset_plan (archive_keep).
 */
public final class PassSeasonHook implements SeasonHook {

    private final ShopRuntime rt;
    private final PearlManager pearls;

    public PassSeasonHook(ShopRuntime rt, PearlManager pearls) {
        this.rt = rt;
        this.pearls = pearls;
    }

    @Override
    public String id() {
        return "shop.pass";
    }

    @Override
    public void onArchive(SeasonHookContext ctx) throws Exception {
        int season = ctx.fromSeason();
        Optional<PassDef> found =
                rt.snapshot().all(PassDef.TYPE).values().stream()
                        .filter(p -> p.season() == season)
                        .findFirst();
        if (found.isEmpty()) {
            ctx.report("시즌 " + season + " 패스 정의 없음 — 마감할 보상 없음");
            return;
        }
        PassDef def = found.get();
        List<ShopRepository.PassRow> rows =
                ctx.database().tx(c -> ShopRepository.passRows(c, season));
        int players = 0;
        int rewards = 0;
        long pearlSum = 0;
        for (ShopRepository.PassRow row : rows) {
            int level = PassMath.level(def, row.exp(), row.bought());
            var claimed =
                    ctx.database()
                            .tx(
                                    c ->
                                            new HashSet<>(
                                                    ShopRepository.claimed(
                                                            c, row.player(), season)));
            List<PassDef.Reward> pending = PassMath.claimable(def, level, row.premium(), claimed);
            if (pending.isEmpty()) {
                continue;
            }
            players++;
            rewards += pending.size();
            if (ctx.dryRun()) {
                continue;
            }
            PassManager.Claimed result =
                    pearls.runBlocking(
                                    tx -> PassManager.grantRewards(tx, row.player(), def, pending))
                            .value();
            pearlSum += result.pearls();
            for (String title : result.titles()) {
                // Call TitleService on the main thread and wait until it finishes (re-running gives
                // the same result: grant is idempotent)
                CompletableFuture<TitleService.Grant> done = new CompletableFuture<>();
                rt.tasks()
                        .main()
                        .execute(
                                () -> {
                                    var _ =
                                            SeasonServices.titles()
                                                    .grant(
                                                            row.player(),
                                                            TitleId.of(title),
                                                            "pass:" + season + ":" + title)
                                                    .whenComplete(
                                                            (g, e) -> {
                                                                if (e != null) {
                                                                    done.completeExceptionally(e);
                                                                } else {
                                                                    done.complete(g);
                                                                }
                                                            });
                                });
                var _ = done.get(30, TimeUnit.SECONDS);
            }
        }
        ctx.report(
                (ctx.dryRun() ? "[dry-run] " : "")
                        + "시즌 "
                        + season
                        + " 패스 마감: "
                        + players
                        + "명, 미수령 보상 "
                        + rewards
                        + "개, 진주 "
                        + pearlSum);
    }
}
