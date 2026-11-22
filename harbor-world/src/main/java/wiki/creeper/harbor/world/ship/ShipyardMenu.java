package wiki.creeper.harbor.world.ship;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import wiki.creeper.harbor.common.content.type.ItemDef;
import wiki.creeper.harbor.core.api.MessageService;
import wiki.creeper.harbor.core.gui.Menu;
import wiki.creeper.harbor.world.Icons;
import wiki.creeper.harbor.world.WorldKit;
import wiki.creeper.harbor.world.api.ShipState;
import wiki.creeper.harbor.world.api.UpgradePath;
import wiki.creeper.harbor.world.content.ShipDef;

/**
 * Shipyard screen (docs 20 8.4, '12. world · housing · traps' chapter 3 shipyard: ship purchase,
 * upgrade, repair). A purchase runs the payment (SHIP_PURCHASE) and the ships row insert in one
 * transaction after a confirmation Dialog. Main thread only.
 */
final class ShipyardMenu {

    private final WorldKit kit;
    private final ShipManager ships;

    ShipyardMenu(WorldKit kit, ShipManager ships) {
        this.kit = kit;
        this.ships = ships;
    }

    void open(Player player) {
        MessageService msg = kit.api().messages();
        List<ShipDef> defs =
                new ArrayList<>(kit.api().content().snapshot().all(ShipDef.TYPE).values());
        defs.sort(Comparator.comparingLong(ShipDef::price));
        Menu menu = new Menu(msg.get("world.yard.title"), 3);
        int slot = 10;
        for (ShipDef def : defs) {
            if (slot > 16) {
                break;
            }
            Optional<ShipRepository.Row> row =
                    ships.rows(player.getUniqueId()).stream()
                            .filter(r -> r.type().equals(def.id()))
                            .findFirst();
            if (row.isEmpty() && def.price() <= 0) {
                continue;
            }
            List<Component> lore = new ArrayList<>();
            lore.add(
                    msg.get(
                            "world.yard.lore.speed",
                            Placeholder.unparsed(
                                    "value", String.valueOf(Math.round(def.speed() * 100)))));
            lore.add(
                    msg.get(
                            "world.yard.lore.cargo",
                            Placeholder.unparsed("value", String.valueOf(def.cargo()))));
            lore.add(
                    msg.get(
                            "world.yard.lore.durability",
                            Placeholder.unparsed("value", String.valueOf(def.durability()))));
            if (row.isPresent()) {
                ShipRepository.Row r = row.get();
                lore.add(
                        msg.get(
                                r.state() == ShipState.WRECKED
                                        ? "world.yard.lore.wrecked"
                                        : "world.yard.lore.owned",
                                Placeholder.unparsed("durability", String.valueOf(r.durability())),
                                Placeholder.unparsed(
                                        "max",
                                        String.valueOf(
                                                ShipMath.maxDurability(def, r.upgrades())))));
                lore.add(msg.get("world.yard.lore.manage"));
            } else {
                lore.add(
                        msg.get(
                                "world.yard.lore.price",
                                Placeholder.unparsed("price", String.valueOf(def.price()))));
                String req = ships.requirement(player, def);
                if (req != null) {
                    lore.add(
                            msg.get(
                                    req,
                                    Placeholder.unparsed(
                                            "level", String.valueOf(def.requires().level())),
                                    Placeholder.unparsed(
                                            "tier", String.valueOf(def.requires().jobTier()))));
                }
            }
            ItemStack icon = Icons.of(base(def), Component.text(def.name()), lore, null);
            menu.button(
                    slot++,
                    icon,
                    e -> {
                        if (row.isPresent()) {
                            manage(player, def, row.get());
                        } else {
                            confirmBuy(player, def);
                        }
                    });
        }
        menu.open(player);
    }

    private ItemStack base(ShipDef def) {
        return kit.api()
                .content()
                .snapshot()
                .get(ItemDef.TYPE, def.item().id())
                .map(d -> kit.api().items().create(d, 1))
                .orElse(ItemStack.of(Material.OAK_BOAT));
    }

    private void confirmBuy(Player player, ShipDef def) {
        player.closeInventory();
        MessageService msg = kit.api().messages();
        kit.api()
                .dialogs()
                .confirm(
                        player,
                        msg.get("world.yard.buy_title"),
                        List.of(
                                msg.get(
                                        "world.yard.buy_body",
                                        Placeholder.unparsed("name", def.name()),
                                        Placeholder.unparsed(
                                                "price", String.valueOf(def.price())))),
                        msg.get("world.yard.yes"),
                        msg.get("world.yard.no"),
                        ok -> {
                            if (ok) {
                                ships.buy(player, def);
                            }
                        });
    }

    private void manage(Player player, ShipDef def, ShipRepository.Row row) {
        MessageService msg = kit.api().messages();
        Menu menu =
                new Menu(
                        msg.get(
                                "world.yard.manage_title",
                                Placeholder.unparsed("name", def.name())),
                        3);
        int slot = 10;
        for (UpgradePath path : UpgradePath.values()) {
            int level = row.upgrades().getOrDefault(path, 0);
            int max = def.maxUpgrade(path);
            List<Component> lore = new ArrayList<>();
            lore.add(
                    msg.get(
                            "world.yard.lore.level",
                            Placeholder.unparsed("level", String.valueOf(level)),
                            Placeholder.unparsed("max", String.valueOf(max))));
            if (level < max && def.price() > 0) {
                long cost = ShipMath.upgradeCost(def, kit.balance().upgradeCost(level + 1));
                lore.add(
                        msg.get(
                                "world.yard.lore.price",
                                Placeholder.unparsed("price", String.valueOf(cost))));
            }
            Material icon =
                    switch (path) {
                        case SPEED -> Material.FEATHER;
                        case CARGO -> Material.CHEST;
                        case HULL -> Material.IRON_CHESTPLATE;
                    };
            menu.button(
                    slot,
                    Icons.of(icon, msg.get("world.yard.path." + path.key()), lore),
                    e -> {
                        player.closeInventory();
                        ships.upgrade(player, row, path);
                    });
            slot += 2;
        }
        long quote = ships.repairQuote(player, row);
        menu.button(
                16,
                Icons.of(
                        Material.ANVIL,
                        msg.get("world.yard.repair"),
                        List.of(
                                msg.get(
                                        "world.yard.lore.price",
                                        Placeholder.unparsed("price", String.valueOf(quote))))),
                e -> {
                    player.closeInventory();
                    ships.repair(player, row);
                });
        menu.button(
                22,
                Icons.of(Material.PAPER, msg.get("world.yard.deed"), List.of()),
                e -> {
                    player.closeInventory();
                    ships.giveDeed(player, def, row.shipId());
                });
        menu.open(player);
    }
}
