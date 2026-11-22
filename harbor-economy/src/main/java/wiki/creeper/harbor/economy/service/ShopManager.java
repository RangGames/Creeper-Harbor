package wiki.creeper.harbor.economy.service;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.ItemLore;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import wiki.creeper.harbor.common.content.type.ItemDef;
import wiki.creeper.harbor.common.db.SqlConsumer;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.common.id.ItemId;
import wiki.creeper.harbor.common.id.NpcId;
import wiki.creeper.harbor.common.ledger.LedgerEntry;
import wiki.creeper.harbor.common.ledger.LedgerError;
import wiki.creeper.harbor.common.ledger.LedgerException;
import wiki.creeper.harbor.common.ledger.LedgerReason;
import wiki.creeper.harbor.common.model.Currency;
import wiki.creeper.harbor.common.util.Result;
import wiki.creeper.harbor.core.api.HarborApi;
import wiki.creeper.harbor.core.api.HarborPlayer;
import wiki.creeper.harbor.core.api.HarborSounds;
import wiki.creeper.harbor.core.api.StatKey;
import wiki.creeper.harbor.core.gui.PagedMenu;
import wiki.creeper.harbor.core.task.Tasks;
import wiki.creeper.harbor.economy.api.ShopService;
import wiki.creeper.harbor.economy.api.event.ShopPurchaseEvent;
import wiki.creeper.harbor.economy.content.ShopDef;
import wiki.creeper.harbor.npc.api.AffinityService;
import wiki.creeper.harbor.npc.api.NpcInfo;
import wiki.creeper.harbor.npc.api.NpcService;

/**
 * NPC shop purchases (docs 20 7.4). Price = buy_price (or entry price) × shop_buy_price stat × (1 −
 * NPC affinity discount). Quantity selection → take(SHOP_BUY) and daily limit update in one
 * transaction → give after commit; if slots run short, the rest goes to the mailbox. Vanilla
 * materials (fish market iron ingot, gunpowder …) have fixed prices and daily limits. Selling back
 * (buyback) is not included.
 */
public final class ShopManager implements ShopService {

    private static final StatKey BUY_PRICE = StatKey.of("shop_buy_price");

    private final HarborApi api;
    private final Tasks tasks;
    private final MailManager mail;
    private final TradeGuard guard;
    private final Logger log;

    public ShopManager(HarborApi api, Tasks tasks, MailManager mail, TradeGuard guard, Logger log) {
        this.api = api;
        this.tasks = tasks;
        this.mail = mail;
        this.guard = guard;
        this.log = log;
    }

    @Override
    public boolean open(Player player, HarborId shopId) {
        return open(player, shopId, null);
    }

    /** When opened via NPC dialog (shop:open), that NPC's affinity discount applies. */
    public boolean open(Player player, HarborId shopId, @Nullable NpcId npc) {
        Optional<ShopDef> shop = api.content().snapshot().get(ShopDef.TYPE, shopId);
        if (shop.isEmpty()) {
            return false;
        }
        NpcId seller = npc != null ? npc : shop.get().npc();
        PagedMenu<ShopDef.Entry> menu =
                new PagedMenu<>(
                        Component.text(shop.get().title()),
                        6,
                        shop.get().entries(),
                        entry ->
                                new PagedMenu.Entry(
                                        icon(player, entry, seller),
                                        e ->
                                                chooseAmount(
                                                        player,
                                                        shop.get(),
                                                        entry,
                                                        seller,
                                                        e.getClick())),
                        named(Material.ARROW, "economy.shop.previous"),
                        named(Material.ARROW, "economy.shop.next"));
        menu.show(player, 0);
        return true;
    }

    /**
     * Dialog action shop:open: looks up by argument → the NPC's shop field → the shop whose
     * shops.yml npc is this NPC.
     */
    public Optional<HarborId> shopOf(NpcId npc, @Nullable String explicit) {
        if (explicit != null) {
            return HarborId.tryParse(explicit);
        }
        Optional<HarborId> fromNpc =
                api.service(NpcService.class).flatMap(s -> s.npc(npc)).map(NpcInfo::shop);
        if (fromNpc.isPresent()) {
            return fromNpc;
        }
        return api.content().snapshot().all(ShopDef.TYPE).values().stream()
                .filter(s -> npc.equals(s.npc()))
                .map(ShopDef::id)
                .findFirst();
    }

    private Optional<ItemDef> def(ShopDef.Entry entry) {
        ItemId item = entry.item();
        return item == null
                ? Optional.empty()
                : api.content().snapshot().get(ItemDef.TYPE, item.id());
    }

    /** Item for a vanilla entry (empty if the key is invalid). */
    private static Optional<Material> vanilla(ShopDef.Entry entry) {
        String key = entry.vanilla();
        return key == null ? Optional.empty() : Optional.ofNullable(Material.matchMaterial(key));
    }

    private Optional<ItemStack> create(ShopDef.Entry entry, int amount) {
        Optional<ItemDef> def = def(entry);
        if (def.isPresent()) {
            return Optional.of(api.items().create(def.get(), amount));
        }
        return vanilla(entry).map(m -> ItemStack.of(m, amount));
    }

    /**
     * Entry name. Vanilla materials are translation keys, so they show in the client language
     * (Korean).
     */
    private Component name(ShopDef.Entry entry) {
        return def(entry)
                .<Component>map(d -> Component.text(d.name()))
                .orElseGet(
                        () ->
                                vanilla(entry)
                                        .<Component>map(
                                                m -> Component.translatable(m.translationKey()))
                                        .orElse(Component.text("?")));
    }

    long unitPrice(UUID player, ShopDef.Entry entry, @Nullable NpcId npc) {
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
        int discount =
                npc == null
                        ? 0
                        : api.service(AffinityService.class)
                                .map(a -> a.discountPercent(player, npc))
                                .orElse(0);
        double mult =
                api.modifiers().value(player, BUY_PRICE)
                        * (1 - Math.clamp(discount, 0, 100) / 100.0);
        return Math.max(1, (long) Math.ceil(base * mult));
    }

    private boolean meets(HarborPlayer hp, ShopDef.Entry entry) {
        if (hp.jobTier().level() < entry.jobTier()
                || (entry.line() != null && hp.jobLine().orElse(null) != entry.line())) {
            return false;
        }
        for (String flag : entry.requiredFlags()) {
            if (!api.flags().hasPlayer(hp.uuid(), flag)) {
                return false;
            }
        }
        return true;
    }

    private ItemStack icon(Player player, ShopDef.Entry entry, @Nullable NpcId npc) {
        Optional<ItemStack> created = create(entry, 1);
        if (created.isEmpty()) {
            return ItemStack.of(Material.BARRIER);
        }
        ItemStack stack = created.get();
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
                                        String.valueOf(
                                                unitPrice(player.getUniqueId(), entry, npc)))));
        if (!meets(api.players().get(player), entry)) {
            lore.add(
                    api.messages()
                            .get(
                                    entry.jobTier() > 0
                                            ? "economy.shop.locked"
                                            : "economy.shop.locked_flag",
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
        lore.add(
                api.messages()
                        .get(
                                "economy.shop.click_hint",
                                Placeholder.unparsed(
                                        "stack", String.valueOf(stack.getMaxStackSize()))));
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

    /**
     * Click quantity (proposed): left-click 1 · Shift+left-click one stack · right-click manual
     * input (1 to one stack). If slots run short, the rest goes to the mailbox.
     */
    private void chooseAmount(
            Player player,
            ShopDef shop,
            ShopDef.Entry entry,
            @Nullable NpcId npc,
            @Nullable ClickType click) {
        Optional<ItemStack> sample = create(entry, 1);
        if (sample.isEmpty()) {
            return;
        }
        if (!meets(api.players().get(player), entry)) {
            api.messages()
                    .send(
                            player,
                            entry.jobTier() > 0
                                    ? "economy.shop.locked"
                                    : "economy.shop.locked_flag",
                            Placeholder.unparsed("tier", String.valueOf(entry.jobTier())));
            return;
        }
        int maxStack = sample.get().getMaxStackSize();
        long unit = unitPrice(player.getUniqueId(), entry, npc);
        ShopQuantity.Click kind =
                click == null
                        ? ShopQuantity.Click.OTHER
                        : click == ClickType.LEFT
                                ? ShopQuantity.Click.LEFT
                                : click == ClickType.SHIFT_LEFT
                                        ? ShopQuantity.Click.SHIFT_LEFT
                                        : click.isRightClick()
                                                ? ShopQuantity.Click.RIGHT
                                                : ShopQuantity.Click.OTHER;
        OptionalInt now = ShopQuantity.amount(kind, maxStack);
        if (now.isPresent()) {
            buy(player, shop, entry, now.getAsInt(), unit);
            return;
        }
        if (kind != ShopQuantity.Click.RIGHT) {
            return;
        }
        player.closeInventory();
        api.dialogs()
                .input(
                        player,
                        name(entry),
                        List.of(
                                api.messages()
                                        .get(
                                                "economy.shop.input_body",
                                                Placeholder.unparsed("price", String.valueOf(unit)),
                                                Placeholder.unparsed(
                                                        "max", String.valueOf(maxStack)))),
                        api.messages().get("economy.shop.input_label"),
                        "1",
                        4,
                        false,
                        api.messages().get("economy.shop.input_ok"),
                        api.messages().get("economy.shop.input_cancel"),
                        text -> {
                            OptionalInt amount = ShopQuantity.parse(text, maxStack);
                            if (amount.isEmpty()) {
                                api.messages()
                                        .send(
                                                player,
                                                "economy.shop.input_bad",
                                                Placeholder.unparsed(
                                                        "max", String.valueOf(maxStack)));
                                return;
                            }
                            buy(player, shop, entry, amount.getAsInt(), unit);
                        });
    }

    private void buy(Player player, ShopDef shop, ShopDef.Entry entry, int amount, long unit) {
        UUID uuid = player.getUniqueId();
        long price = unit * amount;
        if (price <= 0) {
            api.messages().send(player, "economy.shop.failed");
            return;
        }
        if (player.getInventory().firstEmpty() < 0) {
            api.messages().send(player, "economy.mail.full");
            return;
        }
        Optional<TradeGuard.Ticket> ticket = guard.begin(uuid);
        if (ticket.isEmpty()) {
            api.messages().send(player, "economy.busy");
            return;
        }
        String entryKey = shop.id().path() + "|" + entry.key();
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
        String ref = "buy:" + UUID.randomUUID();
        var _ =
                ticket.get()
                        .track(
                                api.currency()
                                        .atomic(
                                                List.of(
                                                        new LedgerEntry(
                                                                uuid,
                                                                Currency.GOLD,
                                                                -price,
                                                                LedgerReason.SHOP_BUY,
                                                                ref)),
                                                limit))
                        .thenAccept(
                                result -> {
                                    if (result
                                            instanceof
                                            Result.Err<List<Long>, LedgerError>(
                                                    LedgerError error)) {
                                        if (player.isOnline()) {
                                            api.messages()
                                                    .send(
                                                            player,
                                                            switch (error) {
                                                                case INSUFFICIENT_FUNDS ->
                                                                        "economy.shop.no_gold";
                                                                case REJECTED ->
                                                                        "economy.shop.limit";
                                                                default -> "economy.shop.failed";
                                                            });
                                            // Denied sound ("15. Sound" 4.5 ui.error). The future
                                            // finishes on the main thread
                                            HarborSounds.play(player, "harbor:ui.error");
                                        }
                                        return;
                                    }
                                    Optional<ItemStack> items = create(entry, amount);
                                    if (items.isEmpty()) {
                                        log.error("상점 아이템을 만들지 못했습니다: {} ({})", entry.key(), ref);
                                        return;
                                    }
                                    // Inventory if online; mailbox if slots filled up or they left
                                    // between commits
                                    mail.giveOrMail(
                                            uuid, List.of(items.get()), "shop_overflow", ref);
                                    log.info(
                                            "상점 구매 {} {} x{} {}G",
                                            player.getName(),
                                            entry.key(),
                                            amount,
                                            price);
                                    Bukkit.getPluginManager()
                                            .callEvent(
                                                    new ShopPurchaseEvent(
                                                            uuid,
                                                            shop.id(),
                                                            entry.item(),
                                                            entry.key(),
                                                            amount,
                                                            price));
                                    if (player.isOnline()) {
                                        api.messages()
                                                .send(
                                                        player,
                                                        "economy.shop.bought",
                                                        Placeholder.component("name", name(entry)),
                                                        Placeholder.unparsed(
                                                                "amount", String.valueOf(amount)),
                                                        Placeholder.unparsed(
                                                                "price", String.valueOf(price)));
                                    }
                                });
    }

    @Override
    public CompletableFuture<Result<Long, LedgerError>> charge(
            Player player,
            long price,
            LedgerReason reason,
            String refId,
            @Nullable SqlConsumer extra) {
        if (price <= 0) {
            throw new IllegalArgumentException("가격은 0보다 커야 합니다: " + price);
        }
        UUID uuid = player.getUniqueId();
        Optional<TradeGuard.Ticket> ticket = guard.begin(uuid);
        if (ticket.isEmpty()) {
            return CompletableFuture.completedFuture(Result.err(LedgerError.REJECTED));
        }
        return ticket.get()
                .track(
                        api.currency()
                                .atomic(
                                        List.of(
                                                new LedgerEntry(
                                                        uuid,
                                                        Currency.GOLD,
                                                        -price,
                                                        reason,
                                                        refId)),
                                        extra))
                .thenApplyAsync(r -> r.map(List::getFirst), tasks.main());
    }

    private ItemStack named(Material material, String key) {
        ItemStack stack = ItemStack.of(material);
        stack.setData(DataComponentTypes.ITEM_NAME, api.messages().get(key));
        return stack;
    }
}
