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
import wiki.creeper.harbor.shop.data.NoticeRepository;
import wiki.creeper.harbor.shop.donor.DonorManager;
import wiki.creeper.harbor.shop.festival.FestivalManager;
import wiki.creeper.harbor.shop.pass.PassManager;
import wiki.creeper.harbor.shop.pearl.PearlManager;

/**
 * Join and cross-server notice handling (docs 20 12.2: PlayerJoinEvent → unshown payment notices,
 * apply subscription perks). On a harbor:shop notice, if that player is on this server, re-read the
 * cache from the DB and show unacknowledged notices. Collection codex milestone cosmetics are also
 * granted here ('17-2' 4.4).
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

    /** Shows unacknowledged payment and gift notices and marks them acknowledged. */
    private void showNotices(UUID uuid) {
        var _ =
                rt.db(c -> NoticeRepository.takeNotices(c, uuid))
                        .thenAccept(
                                notices -> {
                                    Player player = Bukkit.getPlayer(uuid);
                                    if (player == null) {
                                        return;
                                    }
                                    for (NoticeRepository.Notice n : notices) {
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

    /**
     * Codex completion and region completion cosmetics (shop.yml codex_rewards). Titles are
     * requested by Collection directly from Season.
     */
    @EventHandler
    public void onMilestone(CodexMilestoneEvent event) {
        HarborId reward = rt.settings().codexRewards().get(event.key());
        if (reward != null) {
            var _ = cosmetics.grant(event.player(), reward, "CODEX", "codex:" + event.key());
        }
    }
}
