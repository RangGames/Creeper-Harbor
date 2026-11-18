package wiki.creeper.harbor.shop;

import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import java.io.IOException;
import java.util.Objects;
import java.util.UUID;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.ServicePriority;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.core.api.HarborApi;
import wiki.creeper.harbor.core.api.HarborModule;
import wiki.creeper.harbor.core.api.ModuleContext;
import wiki.creeper.harbor.fishing.api.FishingService;
import wiki.creeper.harbor.season.api.SeasonServices;
import wiki.creeper.harbor.shop.api.CosmeticService;
import wiki.creeper.harbor.shop.api.DonorService;
import wiki.creeper.harbor.shop.api.PearlService;
import wiki.creeper.harbor.shop.api.SeasonPassService;
import wiki.creeper.harbor.shop.chest.ChestManager;
import wiki.creeper.harbor.shop.command.ShopCommands;
import wiki.creeper.harbor.shop.content.ShopContent;
import wiki.creeper.harbor.shop.cosmetic.CatchFxPresenter;
import wiki.creeper.harbor.shop.cosmetic.CosmeticManager;
import wiki.creeper.harbor.shop.cosmetic.PetManager;
import wiki.creeper.harbor.shop.donor.DonorManager;
import wiki.creeper.harbor.shop.festival.FestivalManager;
import wiki.creeper.harbor.shop.order.ShopOrders;
import wiki.creeper.harbor.shop.pass.OptionalPassSources;
import wiki.creeper.harbor.shop.pass.PassManager;
import wiki.creeper.harbor.shop.pass.PassSeasonHook;
import wiki.creeper.harbor.shop.pearl.PearlAudit;
import wiki.creeper.harbor.shop.pearl.PearlManager;
import wiki.creeper.harbor.shop.ui.ShopMenus;
import wiki.creeper.harbor.shop.webhook.InboxProcessor;
import wiki.creeper.harbor.shop.webhook.PaymentProcessor;
import wiki.creeper.harbor.shop.webhook.Reconciler;
import wiki.creeper.harbor.shop.webhook.SignatureVerifier;
import wiki.creeper.harbor.shop.webhook.WebhookIntake;
import wiki.creeper.harbor.shop.webhook.WebhookServer;

/**
 * Harbor-Shop (docs 20 12장): 유료 재화 진주와 사용처(코스메틱 상점, 시즌 패스, 진주 보물함), 후원 등급 구독, Tebex 결제 수신. 원칙은 '6.
 * BM 기획' 1장: 진주는 골드·물비늘·어종과 교환 불가, 유료 상품은 거래·경매 불가, 코스메틱은 영구 소유, 확률 공개, 결제로 성능이 오르지 않음.
 */
public final class HarborShopPlugin extends HarborModule {

    private @Nullable WebhookServer webhook;
    private @Nullable PetManager pets;

    @Override
    public String moduleId() {
        return "shop";
    }

    @Override
    protected void enable(ModuleContext ctx) {
        HarborApi api = ctx.api();
        ctx.migrate();
        ShopContent.TYPES.forEach(ctx::contentType);
        ShopContent.registerChecks(ctx::contentCheck);
        ctx.playerComponent(ShopRuntime.COMPONENT);
        saveDefaultConfig();
        FileConfiguration config = getConfig();

        ShopRuntime rt = new ShopRuntime(api, ctx.tasks(), getSLF4JLogger());
        PearlManager pearls = new PearlManager(rt);
        CosmeticManager cosmetics = new CosmeticManager(rt);
        DonorManager donors = new DonorManager(rt);
        PassManager passes = new PassManager(rt, pearls, cosmetics);
        ChestManager chests = new ChestManager(rt, pearls, cosmetics);
        FestivalManager festivals = new FestivalManager(rt, cosmetics);
        ShopOrders orders = new ShopOrders(rt, pearls, cosmetics, donors);
        PetManager petManager = new PetManager(rt, cosmetics);
        pets = petManager;
        CatchFxPresenter catchFx = new CatchFxPresenter(rt);
        ShopListener listener = new ShopListener(rt, pearls, cosmetics, donors, passes, festivals);

        PaymentProcessor payments =
                new PaymentProcessor(
                        rt,
                        pearls,
                        new PaymentProcessor.Hooks() {
                            @Override
                            public void donorChanged(UUID player) {
                                donors.refresh(player);
                            }

                            @Override
                            public void festival(FestivalManager.Contribution contribution) {
                                festivals.afterContribution(contribution);
                            }

                            @Override
                            public void cosmeticsChanged(UUID player) {
                                var _ = cosmetics.refresh(player);
                            }
                        });
        InboxProcessor inbox = new InboxProcessor(rt, payments);
        Reconciler reconciler =
                new Reconciler(
                        rt,
                        inbox,
                        config.getString("tebex.secret", ""),
                        config.getString("tebex.payments_url", "https://plugin.tebex.io/payments"));
        PearlAudit audit = new PearlAudit(rt);

        ctx.listen(cosmetics);
        ctx.listen(passes);
        ctx.listen(petManager);
        ctx.listen(catchFx);
        ctx.listen(listener);
        Objects.requireNonNull(
                        getServer().getServicesManager().load(FishingService.class),
                        "Harbor-Fishing이 켜져 있어야 합니다")
                .registerPresentation(catchFx);
        SeasonServices.hooks().register(this, new PassSeasonHook(rt, pearls));
        new OptionalPassSources(passes, this, getSLF4JLogger()).register();

        listener.start();
        donors.start();
        festivals.start();
        petManager.start();
        audit.schedule();

        boolean webhookEnabled = config.getBoolean("webhook.enabled", false);
        if (webhookEnabled) {
            startWebhook(config, rt, inbox);
            reconciler.schedule();
        }
        if (webhookEnabled && config.getBoolean("webhook.process_inbox", true)) {
            inbox.start();
        }

        ShopMenus menus = new ShopMenus(rt, orders, cosmetics, passes, chests);
        ShopCommands commands =
                new ShopCommands(
                        rt,
                        pearls,
                        cosmetics,
                        passes,
                        chests,
                        donors,
                        festivals,
                        orders,
                        menus,
                        inbox,
                        reconciler,
                        audit);
        ctx.adminCommand(commands.adminCommand());
        getLifecycleManager()
                .registerEventHandler(
                        LifecycleEvents.COMMANDS,
                        event -> commands.registerPlayerCommands(event.registrar()));

        var services = getServer().getServicesManager();
        services.register(PearlService.class, pearls, this, ServicePriority.Normal);
        services.register(CosmeticService.class, cosmetics, this, ServicePriority.Normal);
        services.register(DonorService.class, donors, this, ServicePriority.Normal);
        services.register(SeasonPassService.class, passes, this, ServicePriority.Normal);
    }

    private void startWebhook(FileConfiguration config, ShopRuntime rt, InboxProcessor inbox) {
        String secret = config.getString("webhook.secret", "");
        if (secret.isBlank()) {
            getSLF4JLogger().warn("webhook.secret이 비어 있어 모든 웹훅을 거부합니다");
        }
        WebhookIntake intake =
                new WebhookIntake(
                        SignatureVerifier.create(
                                config.getString("webhook.signature", "tebex_v1"), secret),
                        inbox);
        WebhookServer server =
                new WebhookServer(
                        intake,
                        inbox::signal,
                        config.getStringList("webhook.allowed_ips"),
                        rt.log());
        try {
            server.start(
                    config.getString("webhook.bind", "127.0.0.1"),
                    config.getInt("webhook.port", 8765),
                    config.getString("webhook.path", "/tebex/webhook"));
            webhook = server;
        } catch (IOException e) {
            getSLF4JLogger().error("웹훅 수신기를 열지 못했습니다", e);
        }
    }

    @Override
    protected void disable() {
        WebhookServer server = webhook;
        if (server != null) {
            server.stop();
        }
        PetManager p = pets;
        if (p != null) {
            p.stop();
        }
        getServer().getServicesManager().unregisterAll(this);
    }
}
