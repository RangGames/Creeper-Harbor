package wiki.creeper.harbor.shop.ui;

import static wiki.creeper.harbor.shop.ui.MenuSupport.named;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import wiki.creeper.harbor.common.util.Result;
import wiki.creeper.harbor.core.gui.Menu;
import wiki.creeper.harbor.shop.ShopRuntime;
import wiki.creeper.harbor.shop.chest.ChestManager;
import wiki.creeper.harbor.shop.content.ChestDef;
import wiki.creeper.harbor.shop.content.CosmeticDef;
import wiki.creeper.harbor.shop.pearl.PearlManager;
import wiki.creeper.harbor.shop.util.UuidV7;

/** The pearl chest screen (/보물함): published rates, pity, shard exchange and opening. */
final class ChestMenu {

    private final ShopRuntime rt;
    private final ChestManager chests;
    private final MenuSupport ui;

    ChestMenu(ShopRuntime rt, ChestManager chests, MenuSupport ui) {
        this.rt = rt;
        this.chests = chests;
        this.ui = ui;
    }

    void open(Player player) {
        if (!chests.enabled()) {
            ui.msg().send(player, "shop.chest.closed");
            return;
        }
        List<ChestDef> list = chests.available();
        if (list.isEmpty()) {
            ui.msg().send(player, "shop.chest.closed");
            return;
        }
        ChestDef chest = list.getFirst();
        var _ =
                chests.pity(player.getUniqueId(), chest)
                        .thenAccept(pity -> showChest(player, chest, pity));
    }

    private void showChest(Player player, ChestDef chest, int pity) {
        Menu menu =
                new Menu(
                        ui.msg()
                                .get(
                                        "shop.chest.title",
                                        Placeholder.unparsed("name", chest.name())),
                        6);
        int slot = 0;
        for (ChestDef.Entry e : chest.entries()) {
            if (slot >= 36) {
                break;
            }
            Optional<CosmeticDef> def = rt.cosmetic(e.cosmetic());
            Integer exchange = chest.exchange().get(e.grade());
            UUID order = UuidV7.next();
            ItemStack icon =
                    def.map(
                                    d ->
                                            ui.cosmeticIcon(
                                                    d,
                                                    ui.msg()
                                                            .get(
                                                                    "shop.chest.rate",
                                                                    Placeholder.unparsed(
                                                                            "rate", e.display())),
                                                    exchange == null
                                                            ? null
                                                            : ui.msg()
                                                                    .get(
                                                                            "shop.chest.exchange",
                                                                            Placeholder.unparsed(
                                                                                    "shards",
                                                                                    String.valueOf(
                                                                                            exchange))),
                                                    null))
                            .orElse(named(Material.PAPER, Component.text(e.cosmetic().path())));
            menu.button(
                    slot++,
                    icon,
                    exchange == null
                            ? null
                            : ev ->
                                    ui.report(
                                            player,
                                            chests.exchange(player, chest, e.cosmetic(), order)
                                                    .thenApply(MenuSupport::errorKey)));
        }
        menu.button(
                45,
                named(
                        Material.CLOCK,
                        ui.msg()
                                .get(
                                        "shop.chest.pity",
                                        Placeholder.unparsed("count", String.valueOf(pity)),
                                        Placeholder.unparsed(
                                                "max", String.valueOf(chest.pity().count())),
                                        Placeholder.unparsed(
                                                "shards",
                                                String.valueOf(
                                                        rt.profile(player.getUniqueId())
                                                                .shards())))),
                null);
        for (int count : List.of(1, 10)) {
            long price = count == 1 ? chest.priceSingle() : chest.priceTen();
            UUID order = UuidV7.next();
            menu.button(
                    count == 1 ? 48 : 50,
                    named(
                            count == 1 ? Material.CHEST : Material.ENDER_CHEST,
                            ui.msg()
                                    .get(
                                            "shop.chest.open",
                                            Placeholder.unparsed("count", String.valueOf(count)),
                                            Placeholder.unparsed("price", String.valueOf(price)))),
                    e ->
                            ui.confirm(
                                    player,
                                    chest.name() + " ×" + count,
                                    price,
                                    () -> {
                                        var _ =
                                                chests.open(player, chest, count, order)
                                                        .thenAccept(r -> showPulls(player, r));
                                    }));
        }
        menu.open(player);
    }

    private void showPulls(
            Player player, Result<ChestManager.Opened, PearlManager.Failure> result) {
        switch (result) {
            case Result.Err<ChestManager.Opened, PearlManager.Failure>(var f) ->
                    ui.msg().send(player, f.messageKey());
            case Result.Ok<ChestManager.Opened, PearlManager.Failure>(var opened) -> {
                for (ChestManager.Pull p : opened.pulls()) {
                    ui.msg()
                            .send(
                                    player,
                                    p.duplicate() ? "shop.chest.pull_duplicate" : "shop.chest.pull",
                                    Placeholder.unparsed("name", chests.name(p.cosmetic())),
                                    Placeholder.unparsed("grade", p.grade().key()),
                                    Placeholder.unparsed("shards", String.valueOf(p.shards())));
                }
                player.closeInventory();
            }
        }
    }
}
