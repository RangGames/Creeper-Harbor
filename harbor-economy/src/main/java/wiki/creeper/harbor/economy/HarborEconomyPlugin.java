package wiki.creeper.harbor.economy;

import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import org.bukkit.plugin.ServicePriority;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.content.type.Balance;
import wiki.creeper.harbor.core.api.HarborApi;
import wiki.creeper.harbor.core.api.HarborModule;
import wiki.creeper.harbor.core.api.MailService;
import wiki.creeper.harbor.core.api.ModuleContext;
import wiki.creeper.harbor.economy.api.AuctionService;
import wiki.creeper.harbor.economy.api.CraftService;
import wiki.creeper.harbor.economy.api.PriceService;
import wiki.creeper.harbor.economy.api.RepairService;
import wiki.creeper.harbor.economy.api.SaleService;
import wiki.creeper.harbor.economy.api.ShopService;
import wiki.creeper.harbor.economy.content.EconomyContent;
import wiki.creeper.harbor.economy.content.RecipeDef;
import wiki.creeper.harbor.economy.content.ShopDef;
import wiki.creeper.harbor.economy.content.StationDef;
import wiki.creeper.harbor.economy.service.AdminAlerts;
import wiki.creeper.harbor.economy.service.AuctionHouse;
import wiki.creeper.harbor.economy.service.CraftManager;
import wiki.creeper.harbor.economy.service.DialogActions;
import wiki.creeper.harbor.economy.service.EconomyCommands;
import wiki.creeper.harbor.economy.service.FraudMonitor;
import wiki.creeper.harbor.economy.service.MailManager;
import wiki.creeper.harbor.economy.service.MarketEngine;
import wiki.creeper.harbor.economy.service.RepairManager;
import wiki.creeper.harbor.economy.service.SaleApi;
import wiki.creeper.harbor.economy.service.SellMenu;
import wiki.creeper.harbor.economy.service.SellService;
import wiki.creeper.harbor.economy.service.ShopManager;
import wiki.creeper.harbor.economy.service.StationManager;
import wiki.creeper.harbor.economy.service.TradeGuard;
import wiki.creeper.harbor.npc.api.DialogService;

/**
 * Harbor-Economy (docs 20 ch. 7): rules for gold flowing in and out on top of the Core currency
 * service. Sell prices and market rates (forecast, bulk-sale slippage), NPC shops, mailbox, repair,
 * crafting stations and recipes, auction house, bulk-buyer trades, fraudulent-trade monitoring.
 */
public final class HarborEconomyPlugin extends HarborModule {

    private @Nullable StationManager stations;

    @Override
    public String moduleId() {
        return "economy";
    }

    @Override
    protected void enable(ModuleContext ctx) {
        HarborApi api = ctx.api();
        ctx.migrate();
        EconomyContent.TYPES.forEach(ctx::contentType);
        ctx.contentCheck(ShopDef.TYPE, ShopDef.PRICE_CHECK);
        ctx.contentCheck(RecipeDef.TYPE, EconomyContent.RECIPE_CHECK);
        ctx.contentCheck(RecipeDef.TYPE, ServerChecks.RECIPE_VANILLA);
        ctx.contentCheck(ShopDef.TYPE, ServerChecks.SHOP_VANILLA);
        ctx.contentCheck(Balance.TYPE, ServerChecks.BALANCE);

        TradeGuard guard = new TradeGuard(api.players()::saveSoon, api.players()::frozen);
        AdminAlerts alerts = new AdminAlerts(api, ctx.tasks());
        MarketEngine market = new MarketEngine(api, ctx.tasks(), getSLF4JLogger(), alerts);
        MailManager mail = new MailManager(api, ctx.tasks(), getSLF4JLogger(), guard);
        StationManager stations = new StationManager(this, api);
        this.stations = stations;
        FraudMonitor fraud = new FraudMonitor(api, ctx.tasks(), getSLF4JLogger(), alerts);
        ShopManager shops = new ShopManager(api, ctx.tasks(), mail, guard, getSLF4JLogger());
        SellService sell = new SellService(api, market, mail, guard, getSLF4JLogger());
        CraftManager craft =
                new CraftManager(api, ctx.tasks(), getSLF4JLogger(), mail, guard, stations);
        RepairManager repair = new RepairManager(api, guard, craft);
        AuctionHouse auction =
                new AuctionHouse(
                        api, ctx.tasks(), getSLF4JLogger(), market, mail, guard, fraud, stations);
        stations.setOpener(
                (player, station) -> {
                    if (station.kind() == StationDef.Kind.AUCTION) {
                        auction.open(player, "all", null, false, 0);
                    } else {
                        craft.openStation(player, station.id());
                    }
                });

        alerts.start();
        market.start();
        mail.start();
        auction.start();
        ctx.listen(stations);
        ctx.handoffBlocker(guard);
        // Rebuilt again after content reloads (ContentReloadedEvent), but built once on the first
        // tick
        var _ = ctx.tasks().later(1, stations::respawn);

        api.service(DialogService.class)
                .ifPresent(
                        dialogs ->
                                DialogActions.register(this, dialogs, shops, craft, auction, mail));

        SellMenu sellMenu = new SellMenu(api, ctx.tasks(), sell, mail);
        ctx.itemCustody(sellMenu::held);
        ctx.listen(sellMenu);
        EconomyCommands commands =
                new EconomyCommands(
                        api, market, sell, mail, shops, repair, auction, craft, fraud, stations,
                        sellMenu);
        commands.adminCommands().forEach(ctx::adminCommand);
        getLifecycleManager()
                .registerEventHandler(
                        LifecycleEvents.COMMANDS,
                        event -> commands.registerPlayerCommands(event.registrar()));

        var services = getServer().getServicesManager();
        services.register(PriceService.class, market, this, ServicePriority.Normal);
        services.register(MailService.class, mail, this, ServicePriority.Normal);
        services.register(ShopService.class, shops, this, ServicePriority.Normal);
        services.register(CraftService.class, craft, this, ServicePriority.Normal);
        services.register(AuctionService.class, auction, this, ServicePriority.Normal);
        services.register(RepairService.class, repair, this, ServicePriority.Normal);
        services.register(
                SaleService.class, new SaleApi(sell, sellMenu), this, ServicePriority.Normal);
    }

    @Override
    protected void disable() {
        StationManager s = stations;
        if (s != null) {
            s.despawn();
        }
        getServer().getServicesManager().unregisterAll(this);
    }
}
