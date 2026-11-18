package wiki.creeper.harbor.shop;

import java.util.UUID;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import wiki.creeper.harbor.collection.api.event.CodexMilestoneEvent;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.shop.cosmetic.CosmeticManager;
import wiki.creeper.harbor.shop.data.ShopRepository;
import wiki.creeper.harbor.shop.donor.DonorManager;
import wiki.creeper.harbor.shop.festival.FestivalManager;
import wiki.creeper.harbor.shop.pass.PassManager;
import wiki.creeper.harbor.shop.pearl.PearlManager;

/**
 * 접속·서버 간 알림 처리 (docs 20 12.2: PlayerJoinEvent → 미표시 결제 알림, 구독 혜택 적용). harbor:shop 알림을 받으면 그 플레이어가
 * 이 서버에 있을 때 캐시를 DB에서 다시 읽고 미확인 알림을 보여 준다. Collection 도감 마일스톤 치장품도 여기서 준다 ('17-2' 4.4).
 */
public final class ShopListener implements Listener {

    private final ShopRuntime rt;
    private final PearlManager pearls;
    private final CosmeticManager cosmetics;
    private final DonorManager donors;
    private final PassManager passes;
    private final FestivalManager festivals;

    public ShopListener(
            ShopRuntime rt,
            PearlManager pearls,
            CosmeticManager cosmetics,
            DonorManager donors,
            PassManager passes,
            FestivalManager festivals) {
        this.rt = rt;
        this.pearls = pearls;
        this.cosmetics = cosmetics;
        this.donors = donors;
        this.passes = passes;
        this.festivals = festivals;
    }

    public void start() {
        rt.api()
                .bus()
                .subscribe(
                        ShopRuntime.NOTICES,
                        env -> rt.tasks().main().execute(() -> onNotice(env.payload())));
        rt.api()
                .bus()
                .subscribe(
                        ShopRuntime.ALERTS,
                        env -> rt.tasks().main().execute(() -> rt.showAlert(env.payload())));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        donors.apply(player);
        var _ =
                rt.tasks()
                        .async(
                                () ->
                                        donors.syncRedis(
                                                player.getUniqueId(),
                                                donors.tier(player.getUniqueId())));
        cosmetics.applySkins(player);
        passes.claim(player.getUniqueId());
        festivals.grantRewards(player);
        showNotices(player.getUniqueId());
    }

    private void onNotice(ShopRuntime.ShopNotice notice) {
        UUID uuid = notice.player();
        Player player = Bukkit.getPlayer(uuid);
        if (player == null) {
            return;
        }
        var _ = pearls.refresh(uuid);
        var _ = cosmetics.refresh(uuid);
        donors.refresh(uuid);
        passes.refresh(uuid);
        showNotices(uuid);
    }

    /** 미확인 결제·선물 알림을 보여 주고 확인 처리한다. */
    private void showNotices(UUID uuid) {
        var _ =
                rt.db(c -> ShopRepository.takeNotices(c, uuid))
                        .thenAccept(
                                notices -> {
                                    Player player = Bukkit.getPlayer(uuid);
                                    if (player == null) {
                                        return;
                                    }
                                    for (ShopRepository.Notice n : notices) {
                                        String detail = n.detail() == null ? "" : n.detail();
                                        String from =
                                                detail.contains("|")
                                                        ? detail.substring(0, detail.indexOf('|'))
                                                        : "";
                                        String item =
                                                detail.contains("|")
                                                        ? detail.substring(detail.indexOf('|') + 1)
                                                        : detail;
                                        String name =
                                                HarborId.tryParse(item)
                                                        .flatMap(rt::cosmetic)
                                                        .map(c -> c.name())
                                                        .orElse(item);
                                        rt.api()
                                                .messages()
                                                .send(
                                                        player,
                                                        "shop.notice."
                                                                + n.kind()
                                                                        .toLowerCase(
                                                                                java.util.Locale
                                                                                        .ROOT),
                                                        Placeholder.unparsed(
                                                                "amount",
                                                                String.valueOf(n.amount())),
                                                        Placeholder.unparsed("from", from),
                                                        Placeholder.unparsed("name", name));
                                    }
                                });
    }

    /** 도감 달성률·지역 완성 치장품 (shop.yml codex_rewards). 칭호는 Collection이 Season에 직접 요청한다. */
    @EventHandler
    public void onMilestone(CodexMilestoneEvent event) {
        HarborId reward = rt.settings().codexRewards().get(event.key());
        if (reward != null) {
            var _ = cosmetics.grant(event.player(), reward, "CODEX", "codex:" + event.key());
        }
    }
}
