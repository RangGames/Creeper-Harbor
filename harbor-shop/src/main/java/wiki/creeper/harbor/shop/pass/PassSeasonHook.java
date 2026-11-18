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
 * 시즌 패스 마감 (Season SeasonHook, docs 20 12.4 '시즌 마감'). 전환 6단계(ARCHIVE)의 onArchive에서 끝나는 시즌의 미수령 보상을
 * 자동 지급한다. season_pass_claim 유니크와 참조 ID(pass:&lt;시즌&gt;:&lt;단계&gt;:&lt;트랙&gt;)로 여러 번 불려도 결과가 같다. 이후
 * 그 시즌 패스는 팔지 않는다 (구매는 지금 시즌 패스만 받음). season_pass·season_pass_claim 행은 reset_plan(archive_keep)이
 * 아카이브만 하고 남긴다.
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
                // TitleService는 메인 스레드에서 부르고, 끝날 때까지 기다린다 (재실행해도 같은 결과: grant는 멱등)
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
