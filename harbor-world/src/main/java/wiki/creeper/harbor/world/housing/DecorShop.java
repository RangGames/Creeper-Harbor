package wiki.creeper.harbor.world.housing;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.content.type.CustomBlockDef;
import wiki.creeper.harbor.common.content.type.FurnitureDef;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.common.ledger.LedgerReason;
import wiki.creeper.harbor.core.api.MessageService;
import wiki.creeper.harbor.core.gui.PagedMenu;
import wiki.creeper.harbor.world.Icons;
import wiki.creeper.harbor.world.WorldKit;
import wiki.creeper.harbor.world.api.PlotPermission;
import wiki.creeper.harbor.world.bridge.Payments;
import wiki.creeper.harbor.world.content.DecorDef;

/**
 * Decor shop and palette menus, plus the decor items they hand out (split out of {@link
 * DecorManager}).
 */
final class DecorShop {

    private final WorldKit kit;
    private final HousingManager housing;
    private final Payments payments;
    private final Function<UUID, CompletableFuture<Integer>> bestTier;
    private final DecorManager decor;

    DecorShop(
            WorldKit kit,
            HousingManager housing,
            Payments payments,
            Function<UUID, CompletableFuture<Integer>> bestTier,
            DecorManager decor) {
        this.kit = kit;
        this.housing = housing;
        this.payments = payments;
        this.bestTier = bestTier;
        this.decor = decor;
    }

    private NamespacedKey tag() {
        return decor.tag();
    }

    private Optional<DecorDef> def(HarborId id) {
        return decor.def(id);
    }

    private void addCount(UUID uuid, HarborId id, int amount) {
        decor.addCount(uuid, id, amount);
    }

    private int count(UUID player, HarborId id) {
        return decor.count(player, id);
    }

    // ------------------------------------------------------------------ shop

    /** Decor shop (/부두 상점). */
    void openShop(Player player) {
        MessageService msg = kit.api().messages();
        List<DecorDef> defs =
                new ArrayList<>(kit.api().content().snapshot().all(DecorDef.TYPE).values());
        defs.sort(Comparator.comparing(DecorDef::category).thenComparingLong(DecorDef::price));
        var _ =
                bestTier.apply(player.getUniqueId())
                        .thenAccept(
                                tier -> {
                                    PagedMenu<DecorDef> menu =
                                            new PagedMenu<>(
                                                    msg.get("world.decor.shop_title"),
                                                    6,
                                                    defs,
                                                    d ->
                                                            new PagedMenu.Entry(
                                                                    icon(player, d, tier, true),
                                                                    e ->
                                                                            confirmBuy(
                                                                                    player, d,
                                                                                    tier)),
                                                    Icons.of(
                                                            Material.ARROW,
                                                            msg.get("world.decor.previous"),
                                                            List.of()),
                                                    Icons.of(
                                                            Material.ARROW,
                                                            msg.get("world.decor.next"),
                                                            List.of()));
                                    menu.show(player, 0);
                                });
    }

    private ItemStack icon(Player player, DecorDef d, int tier, boolean shop) {
        MessageService msg = kit.api().messages();
        List<Component> lore = new ArrayList<>();
        if (shop) {
            lore.add(
                    msg.get(
                            "world.decor.lore.price",
                            Placeholder.unparsed("price", String.valueOf(d.price())),
                            Placeholder.unparsed("amount", String.valueOf(d.amount()))));
            if (d.reputationMin() > tier) {
                lore.add(
                        msg.get(
                                "world.decor.lore.locked",
                                Placeholder.unparsed("tier", String.valueOf(d.reputationMin()))));
            }
        }
        lore.add(
                msg.get(
                        "world.decor.lore.owned",
                        Placeholder.unparsed(
                                "count", String.valueOf(count(player.getUniqueId(), d.id())))));
        if (!shop) {
            lore.add(msg.get("world.decor.lore.take"));
        }
        return Icons.of(base(d), Component.text(d.name()), lore, iconModel(d));
    }

    /**
     * Icon model: the explicit model, otherwise the model of the Core furniture or custom block.
     */
    private @Nullable String iconModel(DecorDef d) {
        if (d.model() != null) {
            return d.model();
        }
        var snapshot = kit.api().content().snapshot();
        HarborId furniture = d.furniture();
        if (furniture != null) {
            return snapshot.get(FurnitureDef.TYPE, furniture).map(FurnitureDef::model).orElse(null);
        }
        HarborId block = d.block();
        if (block != null) {
            return snapshot.get(CustomBlockDef.TYPE, block)
                    .map(CustomBlockDef::itemModel)
                    .orElse(null);
        }
        return null;
    }

    private ItemStack base(DecorDef d) {
        if (d.block() != null) {
            return ItemStack.of(Material.NOTE_BLOCK);
        }
        String raw = d.material();
        Material m = raw == null ? null : Material.matchMaterial(raw);
        return ItemStack.of(m != null && m.isItem() ? m : Material.PAPER);
    }

    private void confirmBuy(Player player, DecorDef d, int tier) {
        if (d.reputationMin() > tier) {
            kit.send(
                    player,
                    "world.decor.locked",
                    Placeholder.unparsed("tier", String.valueOf(d.reputationMin())));
            return;
        }
        player.closeInventory();
        MessageService msg = kit.api().messages();
        kit.api()
                .dialogs()
                .confirm(
                        player,
                        msg.get("world.decor.buy_title"),
                        List.of(
                                msg.get(
                                        "world.decor.buy_body",
                                        Placeholder.unparsed("name", d.name()),
                                        Placeholder.unparsed("amount", String.valueOf(d.amount())),
                                        Placeholder.unparsed("price", String.valueOf(d.price())))),
                        msg.get("world.decor.yes"),
                        msg.get("world.decor.no"),
                        ok -> {
                            if (ok) {
                                buy(player, d);
                            }
                        });
    }

    private void buy(Player player, DecorDef d) {
        UUID uuid = player.getUniqueId();
        HarborId id = d.id();
        int amount = d.amount();
        var _ =
                payments.charge(
                                player,
                                d.price(),
                                LedgerReason.HOUSING_DECOR,
                                "decor:" + UUID.randomUUID(),
                                c -> HousingRepository.addDecor(c, uuid, id, amount))
                        .thenAccept(
                                result -> {
                                    if (!result.isOk()) {
                                        kit.send(player, Payments.failKey(result));
                                        return;
                                    }
                                    decor.adjustOwned(uuid, id, amount);
                                    kit.send(
                                            player,
                                            "world.decor.bought",
                                            Placeholder.unparsed("name", d.name()),
                                            Placeholder.unparsed("amount", String.valueOf(amount)),
                                            Placeholder.unparsed(
                                                    "price", String.valueOf(d.price())));
                                });
    }

    // ------------------------------------------------------------------ palette

    /**
     * Decor palette (/부두 장식): only inside a plot the player may build in. Click for 1, shift-click
     * for 16.
     */
    void openPalette(Player player) {
        Optional<HousingManager.PlotState> plot = housing.stateAt(WorldKit.loc(player));
        if (plot.isEmpty() || !housing.can(player, plot.get().info, PlotPermission.BUILD)) {
            kit.send(player, "world.decor.not_in_plot");
            return;
        }
        MessageService msg = kit.api().messages();
        UUID uuid = player.getUniqueId();
        List<DecorDef> mine = new ArrayList<>();
        for (Map.Entry<HarborId, Integer> e : decor.ownedOf(uuid).entrySet()) {
            if (e.getValue() > 0) {
                def(e.getKey()).ifPresent(mine::add);
            }
        }
        mine.sort(Comparator.comparing(DecorDef::category).thenComparing(DecorDef::name));
        if (mine.isEmpty()) {
            kit.send(player, "world.decor.empty");
            return;
        }
        PagedMenu<DecorDef> menu =
                new PagedMenu<>(
                        msg.get("world.decor.palette_title"),
                        6,
                        mine,
                        d ->
                                new PagedMenu.Entry(
                                        icon(player, d, 0, false),
                                        e ->
                                                take(
                                                        player,
                                                        d,
                                                        e.getClick() == ClickType.SHIFT_LEFT
                                                                ? 16
                                                                : 1)),
                        Icons.of(Material.ARROW, msg.get("world.decor.previous"), List.of()),
                        Icons.of(Material.ARROW, msg.get("world.decor.next"), List.of()));
        menu.show(player, 0);
    }

    private void take(Player player, DecorDef d, int wanted) {
        UUID uuid = player.getUniqueId();
        int amount = Math.min(wanted, count(uuid, d.id()));
        if (d.kind() != DecorDef.Kind.BLOCK) {
            amount = Math.min(amount, 1);
        }
        if (amount <= 0) {
            return;
        }
        int n = amount;
        HarborId id = d.id();
        decor.adjustOwned(uuid, id, -n);
        var _ =
                kit.api()
                        .database()
                        .asyncVoid(c -> HousingRepository.takeDecor(c, uuid, id, n))
                        .whenCompleteAsync(
                                (v, error) -> {
                                    if (error != null) {
                                        decor.adjustOwned(uuid, id, n);
                                        kit.send(player, "world.decor.take_failed");
                                        return;
                                    }
                                    // Once the move snapshot has been taken, adding to the
                                    // inventory would not be saved, so return
                                    // it to storage
                                    if (!player.isOnline() || kit.api().players().frozen(uuid)) {
                                        addCount(uuid, id, n);
                                        return;
                                    }
                                    ItemStack item = item(d, n);
                                    var left = player.getInventory().addItem(item);
                                    left.values()
                                            .forEach(rest -> addCount(uuid, id, rest.getAmount()));
                                    // The storage deduction is already committed: save the
                                    // inventory soon too (items are lost if
                                    // the server dies)
                                    kit.api().players().saveSoon(uuid);
                                },
                                kit.tasks().main());
    }

    ItemStack item(DecorDef d, int amount) {
        ItemStack stack = coreItem(d, amount).orElse(null);
        if (stack == null) {
            stack =
                    Icons.of(
                            base(d),
                            Component.text(d.name()),
                            List.of(kit.api().messages().get("world.decor.lore.item")),
                            d.model());
        }
        stack.setAmount(amount);
        stack.editPersistentDataContainer(
                pdc -> pdc.set(tag(), PersistentDataType.STRING, d.id().toString()));
        return stack;
    }

    /**
     * Core furniture or custom block item (item() adds the decor tag). The name is set to the decor
     * name.
     */
    private Optional<ItemStack> coreItem(DecorDef d, int amount) {
        HarborId furniture = d.furniture();
        HarborId block = d.block();
        Optional<ItemStack> stack =
                furniture != null
                        ? kit.api().furniture().furnitureItem(furniture)
                        : block != null
                                ? kit.api().furniture().blockItem(block, amount)
                                : Optional.empty();
        stack.ifPresent(
                s ->
                        s.setData(
                                io.papermc.paper.datacomponent.DataComponentTypes.ITEM_NAME,
                                Component.text(d.name())));
        return stack;
    }
}
