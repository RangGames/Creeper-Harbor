package wiki.creeper.harbor.economy;

import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import org.bukkit.plugin.ServicePriority;
import wiki.creeper.harbor.core.api.HarborApi;
import wiki.creeper.harbor.core.api.HarborModule;
import wiki.creeper.harbor.core.api.ModuleContext;
import wiki.creeper.harbor.economy.api.MailService;
import wiki.creeper.harbor.economy.api.PriceService;
import wiki.creeper.harbor.economy.api.ShopService;
import wiki.creeper.harbor.economy.content.EconomyContent;
import wiki.creeper.harbor.economy.content.ShopDef;
import wiki.creeper.harbor.economy.service.EconomyCommands;
import wiki.creeper.harbor.economy.service.MailManager;
import wiki.creeper.harbor.economy.service.MarketEngine;
import wiki.creeper.harbor.economy.service.RepairManager;
import wiki.creeper.harbor.economy.service.SellService;
import wiki.creeper.harbor.economy.service.ShopManager;

/**
 * Harbor-Economy (docs 20 7장): Core 재화 서비스 위에서 골드가 들어오고 나가는 규칙. 판매가·시세, NPC 상점, 우편함, 수리. 제작·경매장·부정
 * 거래 감시는 다음 단계에서 추가한다.
 */
public final class HarborEconomyPlugin extends HarborModule {

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

        MarketEngine market = new MarketEngine(api, ctx.tasks(), getSLF4JLogger());
        MailManager mail = new MailManager(api, ctx.tasks(), getSLF4JLogger());
        ShopManager shops = new ShopManager(api, mail, getSLF4JLogger());
        SellService sell = new SellService(api, market, getSLF4JLogger());
        RepairManager repair = new RepairManager(api);
        market.start();
        mail.start();

        EconomyCommands commands = new EconomyCommands(api, market, sell, mail, shops, repair);
        commands.adminCommands().forEach(ctx::adminCommand);
        getLifecycleManager()
                .registerEventHandler(
                        LifecycleEvents.COMMANDS,
                        event -> commands.registerPlayerCommands(event.registrar()));

        var services = getServer().getServicesManager();
        services.register(PriceService.class, market, this, ServicePriority.Normal);
        services.register(MailService.class, mail, this, ServicePriority.Normal);
        services.register(ShopService.class, shops, this, ServicePriority.Normal);
    }

    @Override
    protected void disable() {
        getServer().getServicesManager().unregisterAll(this);
    }
}
