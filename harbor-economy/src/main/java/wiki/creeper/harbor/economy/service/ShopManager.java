package wiki.creeper.harbor.economy.service;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.ItemLore;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.slf4j.Logger;
import wiki.creeper.harbor.common.content.type.ItemDef;
import wiki.creeper.harbor.common.db.SqlConsumer;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.common.ledger.LedgerEntry;
import wiki.creeper.harbor.common.ledger.LedgerError;
import wiki.creeper.harbor.common.ledger.LedgerException;
import wiki.creeper.harbor.common.ledger.LedgerReason;
import wiki.creeper.harbor.common.model.Currency;
import wiki.creeper.harbor.common.util.Result;
import wiki.creeper.harbor.core.api.Dialogs;
import wiki.creeper.harbor.core.api.HarborApi;
import wiki.creeper.harbor.core.api.HarborPlayer;
import wiki.creeper.harbor.core.api.StatKey;
import wiki.creeper.harbor.core.gui.PagedMenu;
import wiki.creeper.harbor.economy.api.ShopService;
import wiki.creeper.harbor.economy.api.event.ShopPurchaseEvent;
import wiki.creeper.harbor.economy.content.ShopDef;

/**
 * NPC 상점 구매 (docs 20 7.4). 가격 = buy_price × (1 − 상점 할인 보정 shop_buy_price). 수량 선택 → take(SHOP_BUY)와
 * 일일 한도 갱신을 한 트랜잭션으로 → 커밋 후 지급, 칸이 모자라면 남는 것은 우편함. 되팔기(바이백)는 넣지 않는다. NPC 호감도 할인은 NPC 모듈이 생기면 곱한다.
 */
public final class ShopManager implements ShopService {

    private static final StatKey BUY_PRICE = StatKey.of("shop_buy_price");
    private static final List<Integer> AMOUNTS = List.of(1, 8, 16, 64);

    private final HarborApi api;
    private final MailManager mail;
    private final Logger log;

    public ShopManager(HarborApi api, MailManager mail, Logger log) {
        this.api = api;
        this.mail = mail;
        this.log = log;
    }

    @Override
    public boolean open(Player player, HarborId shopId) {
        Optional<ShopDef> shop = api.content().snapshot().get(ShopDef.TYPE, shopId);
        if (shop.isEmpty()) {
            return false;
        }
        PagedMenu<ShopDef.Entry> menu =
                new PagedMenu<>(
                        Component.text(shop.get().title()),
                        6,
                        shop.get().entries(),
                        entry ->
                                new PagedMenu.Entry(
                                        icon(player, entry),
                                        e -> chooseAmount(player, shop.get(), entry)),
                        named(Material.ARROW, "economy.shop.previous"),
                        named(Material.ARROW, "economy.shop.next"));
        menu.show(player, 0);
        return true;
    }

    private Optional<ItemDef> def(ShopDef.Entry entry) {
        return api.content().snapshot().get(ItemDef.TYPE, entry.item().id());
    }

    long unitPrice(UUID player, ShopDef.Entry entry) {
        long base =
                entry.price().isPresent()
                        ? entry.price().getAsLong()
                        : def(entry)
                                .flatMap(
                                        d -> {
                                            var p = d.buyPrice();
                                            return p.isPresent()
                                                    ? Optional.of((long) p.getAsInt())
                                                    : Optional.empty();
                                        })
                                .orElse(0L);
        return (long) Math.ceil(base * api.modifiers().value(player, BUY_PRICE));
    }

    private boolean meets(HarborPlayer hp, ShopDef.Entry entry) {
        return hp.jobTier().level() >= entry.jobTier()
                && (entry.line() == null || hp.jobLine().orElse(null) == entry.line());
    }

    private ItemStack icon(Player player, ShopDef.Entry entry) {
        Optional<ItemDef> def = def(entry);
        if (def.isEmpty()) {
            return ItemStack.of(Material.BARRIER);
        }
        ItemStack stack = api.items().create(def.get(), 1);
        List<Component> lore =
                new ArrayList<>(
                        Optional.ofNullable(stack.getData(DataComponentTypes.LORE))
                                .map(ItemLore::lines)
                                .orElse(List.of()));
        lore.add(
                api.messages()
                        .get(
                                "economy.shop.price",
                                Placeholder.unparsed(
                                        "price",
                                        String.valueOf(unitPrice(player.getUniqueId(), entry)))));
        if (!meets(api.players().get(player), entry)) {
            lore.add(
                    api.messages()
                            .get(
                                    "economy.shop.locked",
                                    Placeholder.unparsed("tier", String.valueOf(entry.jobTier()))));
        }
        if (entry.dailyLimit() > 0) {
            lore.add(
                    api.messages()
                            .get(
                                    "economy.shop.daily_limit",
                                    Placeholder.unparsed(
                                            "limit", String.valueOf(entry.dailyLimit()))));
        }
        stack.setData(
                DataComponentTypes.LORE,
                ItemLore.lore(
                        lore.stream()
                                .map(
                                        c ->
                                                c.decorationIfAbsent(
                                                        TextDecoration.ITALIC,
                                                        TextDecoration.State.FALSE))
                                .toList()));
        return stack;
    }

    private void chooseAmount(Player player, ShopDef shop, ShopDef.Entry entry) {
        Optional<ItemDef> def = def(entry);
        if (def.isEmpty()) {
            return;
        }
        if (!meets(api.players().get(player), entry)) {
            api.messages()
                    .send(
                            player,
                            "economy.shop.locked",
                            Placeholder.unparsed("tier", String.valueOf(entry.jobTier())));
            return;
        }
        int maxStack = api.items().create(def.get(), 1).getMaxStackSize();
        List<Integer> amounts = AMOUNTS.stream().filter(a -> a <= maxStack).toList();
        long unit = unitPrice(player.getUniqueId(), entry);
        List<Dialogs.Choice> choices =
                amounts.stream()
                        .map(
                                a ->
                                        new Dialogs.Choice(
                                                api.messages()
                                                        .get(
                                                                "economy.shop.amount",
                                                                Placeholder.unparsed(
                                                                        "amount",
                                                                        String.valueOf(a)),
                                                                Placeholder.unparsed(
                                                                        "price",
                                                                        String.valueOf(unit * a))),
                                                Component.empty()))
                        .toList();
        player.closeInventory();
        api.dialogs()
                .choose(
                        player,
                        Component.text(def.get().name()),
                        List.of(api.messages().get("economy.shop.choose")),
                        choices,
                        2,
                        index -> buy(player, shop, entry, def.get(), amounts.get(index), unit));
    }

    private void buy(
            Player player, ShopDef shop, ShopDef.Entry entry, ItemDef def, int amount, long unit) {
        UUID uuid = player.getUniqueId();
        long price = unit * amount;
        String entryKey = shop.id().path() + "|" + entry.item().id().path();
        LocalDate day = api.time().gameDay();
        SqlConsumer limit =
                entry.dailyLimit() <= 0
                        ? c -> {}
                        : c -> {
                            try (PreparedStatement ps =
                                    c.prepareStatement(
                                            "INSERT INTO shop_limit (uuid, shop_entry, day, count) VALUES (?, ?, ?, ?) ON DUPLICATE KEY UPDATE count = count + VALUES(count)")) {
                                ps.setString(1, uuid.toString());
                                ps.setString(2, entryKey);
                                ps.setObject(3, day);
                                ps.setInt(4, amount);
                                ps.executeUpdate();
                            }
                            try (PreparedStatement ps =
                                    c.prepareStatement(
                                            "SELECT count FROM shop_limit WHERE uuid = ? AND shop_entry = ? AND day = ?")) {
                                ps.setString(1, uuid.toString());
                                ps.setString(2, entryKey);
                                ps.setObject(3, day);
                                try (ResultSet rs = ps.executeQuery()) {
                                    if (rs.next() && rs.getInt(1) > entry.dailyLimit()) {
                                        throw new LedgerException(LedgerError.REJECTED, "일일 한도 초과");
                                    }
                                }
                            }
                        };
        if (price <= 0) {
            api.messages().send(player, "economy.shop.failed");
            return;
        }
        var _ =
                api.currency()
                        .atomic(
                                List.of(
                                        new LedgerEntry(
                                                uuid,
                                                Currency.GOLD,
                                                -price,
                                                LedgerReason.SHOP_BUY,
                                                "buy:" + UUID.randomUUID())),
                                limit)
                        .thenAccept(
                                result -> {
                                    if (result
                                            instanceof
                                            Result.Err<List<Long>, LedgerError>(
                                                    LedgerError error)) {
                                        api.messages()
                                                .send(
                                                        player,
                                                        switch (error) {
                                                            case INSUFFICIENT_FUNDS ->
                                                                    "economy.shop.no_gold";
                                                            case REJECTED -> "economy.shop.limit";
                                                            default -> "economy.shop.failed";
                                                        });
                                        return;
                                    }
                                    ItemStack items = api.items().create(def, amount);
                                    for (ItemStack left :
                                            player.getInventory().addItem(items).values()) {
                                        // 커밋 사이에 칸이 찼으면 우편함으로
                                        var _ = mail.sendItem(uuid, left, "shop_overflow", null);
                                        api.messages().send(player, "economy.shop.to_mail");
                                    }
                                    log.info(
                                            "상점 구매 {} {} x{} {}G",
                                            player.getName(),
                                            def.id(),
                                            amount,
                                            price);
                                    Bukkit.getPluginManager()
                                            .callEvent(
                                                    new ShopPurchaseEvent(
                                                            uuid, shop.id(), def.id(), amount,
                                                            price));
                                    api.messages()
                                            .send(
                                                    player,
                                                    "economy.shop.bought",
                                                    Placeholder.unparsed("name", def.name()),
                                                    Placeholder.unparsed(
                                                            "amount", String.valueOf(amount)),
                                                    Placeholder.unparsed(
                                                            "price", String.valueOf(price)));
                                });
    }

    private ItemStack named(Material material, String key) {
        ItemStack stack = ItemStack.of(material);
        stack.setData(DataComponentTypes.ITEM_NAME, api.messages().get(key));
        return stack;
    }
}
