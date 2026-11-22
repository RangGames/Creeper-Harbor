package wiki.creeper.harbor.economy.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.ToLongFunction;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import wiki.creeper.harbor.common.db.SqlConsumer;
import wiki.creeper.harbor.common.id.FishId;
import wiki.creeper.harbor.common.id.SkillId;
import wiki.creeper.harbor.common.ledger.LedgerEntry;
import wiki.creeper.harbor.common.ledger.LedgerError;
import wiki.creeper.harbor.common.ledger.LedgerException;
import wiki.creeper.harbor.common.ledger.LedgerReason;
import wiki.creeper.harbor.common.model.Currency;
import wiki.creeper.harbor.core.api.HarborApi;
import wiki.creeper.harbor.core.api.HarborItem;
import wiki.creeper.harbor.core.api.StatKey;
import wiki.creeper.harbor.core.item.FishLedger;
import wiki.creeper.harbor.economy.api.TradeResult;
import wiki.creeper.harbor.economy.api.event.FishSoldEvent;
import wiki.creeper.harbor.economy.content.EconomyBalance;
import wiki.creeper.harbor.jobs.api.CapstoneState;
import wiki.creeper.harbor.jobs.api.JobsService;

/**
 * Fish selling (docs 20 7.4 sell flow). Shows fish in the inventory (or the held one) with a quote;
 * on confirmation, takes the items and holds them, then in one transaction pays gold (FISH_SELL,
 * reference ID = sale batch UUID) and switches the unique ID ledger to SOLD. On failure the items
 * are returned.
 *
 * <ul>
 *   <li>Bulk-sale slippage (proposed): selling more than 32 of one species at once prices that
 *       batch at the average of the current M and "the M after this sale is reflected" (economy
 *       line "Bulk Trade" bulk_sell_slip reduces the drop).
 *   <li>Bulk-buyer trade (economy line capstone): once a week, sells up to 64 fish of the same
 *       species as the held one at M = 1.0. Reason BIG_DEAL_SELL, reference ID = uuid + season +
 *       week, so a second payout in the same week is blocked by the ledger. Sales volume is added
 *       to market_sales as-is.
 * </ul>
 */
public final class SellService {

    /**
     * One sale candidate line (slot index and the item at that time). The slot is an inventory slot
     * or a sell chest slot.
     *
     * @param protection valuable fish protection reason ({@link SellProtection})
     */
    record Line(
            int slot,
            ItemStack stack,
            HarborItem.Fish fish,
            long price,
            SellProtection.Reason protection) {}

    private static final StatKey BULK_SLIP = StatKey.of("bulk_sell_slip");
    private static final SkillId BIG_DEAL = SkillId.parse("skill/capstone/big_deal");

    private final HarborApi api;
    private final MarketEngine market;
    private final MailManager mail;
    private final TradeGuard guard;
    private final Logger log;

    public SellService(
            HarborApi api, MarketEngine market, MailManager mail, TradeGuard guard, Logger log) {
        this.api = api;
        this.market = market;
        this.mail = mail;
        this.guard = guard;
        this.log = log;
    }

    /**
     * /판매 손 · /판매 전부: quote confirmation Dialog. All skips valuable fish (locked, unique number,
     * ★5); the held item is sold as-is but noted in the confirmation message.
     *
     * @param heldOnly only the held item
     */
    public void open(Player player, boolean heldOnly) {
        List<Line> lines = collect(player, heldOnly);
        if (lines.isEmpty()) {
            api.messages().send(player, "economy.sell.nothing");
            return;
        }
        long total = lines.stream().mapToLong(Line::price).sum();
        List<net.kyori.adventure.text.Component> body = new ArrayList<>();
        body.add(
                api.messages()
                        .get(
                                "economy.sell.body",
                                Placeholder.unparsed("count", String.valueOf(count(lines))),
                                Placeholder.unparsed("total", String.valueOf(total))));
        if (lines.stream().anyMatch(l -> l.protection().protects())) {
            body.add(api.messages().get("economy.sell.protected_warning"));
        }
        api.dialogs()
                .confirm(
                        player,
                        api.messages().get("economy.sell.title"),
                        body,
                        api.messages().get("economy.sell.yes"),
                        api.messages().get("economy.sell.no"),
                        ok -> {
                            if (ok) {
                                sell(player, heldOnly, total);
                            }
                        });
    }

    static int count(List<Line> lines) {
        return lines.stream().mapToInt(l -> l.stack().getAmount()).sum();
    }

    /**
     * Fish in inventory slots. price is per line (unit price × count); unit price comes from
     * pricer.
     */
    private List<Line> scan(
            Player player, int from, int to, ToLongFunction<HarborItem.Fish> pricer) {
        PlayerInventory inv = player.getInventory();
        List<Line> lines = new ArrayList<>();
        for (int slot = from; slot < to; slot++) {
            ItemStack stack = inv.getItem(slot);
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            line(slot, stack, pricer).ifPresent(lines::add);
        }
        return lines;
    }

    private Optional<Line> line(int slot, ItemStack stack, ToLongFunction<HarborItem.Fish> pricer) {
        Optional<HarborItem> item = api.items().read(stack);
        if (item.isPresent() && item.get() instanceof HarborItem.Fish fish) {
            long unit = pricer.applyAsLong(fish);
            if (unit > 0) {
                return Optional.of(
                        new Line(
                                slot,
                                stack.clone(),
                                fish,
                                unit * stack.getAmount(),
                                protection(fish, stack)));
            }
        }
        return Optional.empty();
    }

    /** Valuable fish protection reason (locked → unique number → ★5). */
    static SellProtection.Reason protection(HarborItem.Fish fish, ItemStack stack) {
        return SellProtection.of(
                fish.quality().stars(),
                wiki.creeper.harbor.common.model.Quality.MAX,
                fish.uid() != null,
                ItemLock.locked(stack));
    }

    /**
     * Quote for sell chest slots (bulk-sale slippage included). Slot indexes are chest slots.
     * Non-fish or zero-value slots are skipped.
     *
     * @param contents chest slots (null and empty slots allowed)
     * @param slots number of slots to look at (from the front)
     */
    List<Line> boxLines(Player player, @Nullable ItemStack[] contents, int slots) {
        UUID uuid = player.getUniqueId();
        List<Line> lines = new ArrayList<>();
        for (int slot = 0; slot < Math.min(slots, contents.length); slot++) {
            ItemStack stack = contents[slot];
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            line(slot, stack, f -> market.quote(uuid, f).orElse(0)).ifPresent(lines::add);
        }
        return slip(uuid, lines);
    }

    /** Whether this item is a species that can go into the sell chest (regardless of value). */
    boolean isFish(ItemStack stack) {
        return !stack.isEmpty()
                && api.items().read(stack).filter(i -> i instanceof HarborItem.Fish).isPresent();
    }

    /** Protection reason of this item (NONE if not a fish). */
    SellProtection.Reason protectionOf(ItemStack stack) {
        Optional<HarborItem> item = stack.isEmpty() ? Optional.empty() : api.items().read(stack);
        return item.isPresent() && item.get() instanceof HarborItem.Fish fish
                ? protection(fish, stack)
                : SellProtection.Reason.NONE;
    }

    /**
     * Sells lines taken from the sell chest (the caller has already cleared the chest slots).
     * Transaction and failure return are the same as /판매.
     *
     * @return true if the trade started (false if one is in progress, in which case the caller
     *     returns the items)
     */
    boolean sellTaken(Player player, List<Line> lines) {
        return sellTakenResult(player, lines) != null;
    }

    /**
     * {@link #sellTaken} with the result. Null if the trade did not start (empty or one in
     * progress), in which case the caller returns the items.
     */
    @Nullable CompletableFuture<TradeResult> sellTakenResult(Player player, List<Line> lines) {
        long total = lines.stream().mapToLong(Line::price).sum();
        if (lines.isEmpty() || total <= 0) {
            return null;
        }
        return commit(
                player,
                lines,
                total,
                LedgerReason.FISH_SELL,
                "sell:" + UUID.randomUUID(),
                false,
                false);
    }

    /**
     * Api sale (SaleService): the /판매 손 · 전부 flow without the confirmation dialog.
     *
     * @param species only this species (null = all)
     */
    CompletableFuture<TradeResult> sellNow(
            Player player, boolean heldOnly, @Nullable FishId species) {
        List<Line> lines = collect(player, heldOnly, species);
        long total = lines.stream().mapToLong(Line::price).sum();
        if (lines.isEmpty() || total <= 0) {
            api.messages().send(player, "economy.sell.nothing");
            return CompletableFuture.completedFuture(TradeResult.of(TradeResult.Status.NOTHING));
        }
        CompletableFuture<TradeResult> started =
                commit(
                        player,
                        lines,
                        total,
                        LedgerReason.FISH_SELL,
                        "sell:" + UUID.randomUUID(),
                        false,
                        true);
        return started != null
                ? started
                : CompletableFuture.completedFuture(TradeResult.of(TradeResult.Status.BUSY));
    }

    private List<Line> collect(Player player, boolean heldOnly) {
        return collect(player, heldOnly, null);
    }

    private List<Line> collect(Player player, boolean heldOnly, @Nullable FishId species) {
        PlayerInventory inv = player.getInventory();
        int from = heldOnly ? inv.getHeldItemSlot() : 0;
        int to = heldOnly ? inv.getHeldItemSlot() + 1 : 36;
        UUID uuid = player.getUniqueId();
        List<Line> lines = scan(player, from, to, f -> market.quote(uuid, f).orElse(0));
        if (!heldOnly) {
            // Sell-all skips valuable fish (they are sold only when placed in the sell screen and
            // confirmed)
            lines = lines.stream().filter(l -> !l.protection().protects()).toList();
        }
        if (species != null) {
            lines = lines.stream().filter(l -> l.fish().species().equals(species)).toList();
        }
        return slip(uuid, lines);
    }

    /**
     * Bulk-sale slippage: if a species' count exceeds the threshold, recompute its lines with the
     * adjusted factor.
     */
    private List<Line> slip(UUID uuid, List<Line> lines) {
        // Bulk-sale slippage: if a species' count exceeds the threshold, recompute its lines with
        // the adjusted factor
        Map<FishId, Integer> counts = new HashMap<>();
        for (Line line : lines) {
            counts.merge(line.fish().species(), line.stack().getAmount(), Integer::sum);
        }
        EconomyBalance b = market.balance();
        double slip = api.modifiers().apply(uuid, BULK_SLIP, 0);
        Map<FishId, Double> adjusted = new HashMap<>();
        counts.forEach(
                (fish, n) -> {
                    double now = market.coefficient(fish);
                    double coef =
                            MarketMath.bulkCoefficient(
                                    now, market.sales(fish), n, market.s0(fish), slip, b);
                    if (coef != now) {
                        adjusted.put(fish, coef);
                    }
                });
        if (adjusted.isEmpty()) {
            return lines;
        }
        List<Line> result = new ArrayList<>();
        for (Line line : lines) {
            Double coef = adjusted.get(line.fish().species());
            if (coef == null) {
                result.add(line);
                continue;
            }
            OptionalLong unit = market.quoteWith(uuid, line.fish(), coef);
            if (unit.isPresent() && unit.getAsLong() > 0) {
                result.add(
                        new Line(
                                line.slot(),
                                line.stack(),
                                line.fish(),
                                unit.getAsLong() * line.stack().getAmount(),
                                line.protection()));
            }
        }
        return result;
    }

    private void sell(Player player, boolean heldOnly, long quoted) {
        List<Line> lines = collect(player, heldOnly);
        long total = lines.stream().mapToLong(Line::price).sum();
        if (lines.isEmpty()) {
            return;
        }
        if (total != quoted) {
            // If the quote changed due to the hourly market update or a reload, show it again
            api.messages().send(player, "economy.sell.requote");
            open(player, heldOnly);
            return;
        }
        var _ =
                commit(
                        player,
                        lines,
                        total,
                        LedgerReason.FISH_SELL,
                        "sell:" + UUID.randomUUID(),
                        false,
                        true);
    }

    /**
     * Hold → one transaction (gold + ledger SOLD) → after commit, sales volume and event; on
     * failure, return.
     *
     * @param fromInventory if true, take from inventory slots. If false, the caller (sell chest)
     *     already took them
     * @return the result once committed, or null if the trade did not start (one in progress)
     */
    private @Nullable CompletableFuture<TradeResult> commit(
            Player player,
            List<Line> lines,
            long total,
            LedgerReason reason,
            String ref,
            boolean bigDeal,
            boolean fromInventory) {
        UUID uuid = player.getUniqueId();
        Optional<TradeGuard.Ticket> ticket = guard.begin(uuid);
        if (ticket.isEmpty()) {
            api.messages().send(player, "economy.busy");
            return null;
        }
        // Hold: take it out of the inventory
        PlayerInventory inv = player.getInventory();
        List<ItemStack> custody = new ArrayList<>();
        for (Line line : lines) {
            if (fromInventory) {
                inv.setItem(line.slot(), null);
            }
            custody.add(line.stack());
        }
        List<UUID> uids = lines.stream().map(l -> l.fish().uid()).filter(Objects::nonNull).toList();
        SqlConsumer ledger =
                c -> {
                    for (UUID uid : uids) {
                        if (!FishLedger.transition(
                                c, uid, FishLedger.State.ACTIVE, FishLedger.State.SOLD)) {
                            throw new LedgerException(
                                    LedgerError.REJECTED, "장부 상태가 ACTIVE가 아닌 고유 어종: " + uid);
                        }
                    }
                };
        return ticket.get()
                .track(
                        api.currency()
                                .atomic(
                                        List.of(
                                                new LedgerEntry(
                                                        uuid, Currency.GOLD, total, reason, ref)),
                                        ledger))
                .thenApply(
                        result -> {
                            LedgerError error = Tx.error(result);
                            if (error != null) {
                                log.warn("판매 실패 {} ({}): {}", player.getName(), ref, error);
                                mail.giveOrMail(uuid, custody, "sell_return", ref);
                                if (player.isOnline()) {
                                    api.messages()
                                            .send(
                                                    player,
                                                    bigDeal && error == LedgerError.DUPLICATE
                                                            ? "economy.big_deal.used"
                                                            : "economy.sell.failed");
                                }
                                return TradeResult.of(TradeResult.Status.FAILED);
                            }
                            List<FishSoldEvent.Line> sold = new ArrayList<>();
                            for (Line line : lines) {
                                market.recordSale(line.fish().species(), line.stack().getAmount());
                                sold.add(
                                        new FishSoldEvent.Line(
                                                line.fish().species(),
                                                line.fish().quality(),
                                                line.fish().sizeCm(),
                                                line.price()));
                            }
                            log.info(
                                    "{} {} {}마리 {}G ({})",
                                    bigDeal ? "큰손 거래" : "판매",
                                    player.getName(),
                                    count(lines),
                                    total,
                                    ref);
                            if (bigDeal) {
                                api.service(JobsService.class)
                                        .ifPresent(
                                                jobs -> {
                                                    var _ = jobs.consumeWeeklyUse(uuid);
                                                });
                            }
                            Bukkit.getPluginManager()
                                    .callEvent(new FishSoldEvent(uuid, sold, total));
                            if (player.isOnline()) {
                                api.messages()
                                        .send(
                                                player,
                                                bigDeal
                                                        ? "economy.big_deal.done"
                                                        : "economy.sell.done",
                                                Placeholder.unparsed(
                                                        "count", String.valueOf(count(lines))),
                                                Placeholder.unparsed(
                                                        "total", String.valueOf(total)));
                            }
                            return TradeResult.ok(total, count(lines));
                        });
    }

    // ------------------------------------------------------------------ Bulk-buyer trade

    /**
     * /큰손: sells up to 64 fish of the same species as the held one at M = 1.0 (confirmation
     * screen).
     */
    public void bigDeal(Player player) {
        UUID uuid = player.getUniqueId();
        Optional<JobsService> jobs = api.service(JobsService.class);
        CapstoneState cap = jobs.map(j -> j.capstone(uuid)).orElse(null);
        if (cap == null || cap.node().filter(BIG_DEAL::equals).isEmpty()) {
            api.messages().send(player, "economy.big_deal.locked");
            return;
        }
        if (cap.weeklyUsesLeft() == 0) {
            api.messages().send(player, "economy.big_deal.used");
            return;
        }
        Optional<HarborItem> held = api.items().read(player.getInventory().getItemInMainHand());
        if (held.isEmpty() || !(held.get() instanceof HarborItem.Fish heldFish)) {
            api.messages().send(player, "economy.big_deal.hold_fish");
            return;
        }
        List<Line> lines = bigDealLines(player, heldFish.species());
        if (lines.isEmpty()) {
            api.messages().send(player, "economy.sell.nothing");
            return;
        }
        long total = lines.stream().mapToLong(Line::price).sum();
        api.dialogs()
                .confirm(
                        player,
                        api.messages().get("economy.big_deal.title"),
                        List.of(
                                api.messages()
                                        .get(
                                                "economy.big_deal.body",
                                                Placeholder.unparsed(
                                                        "count", String.valueOf(count(lines))),
                                                Placeholder.unparsed(
                                                        "total", String.valueOf(total)))),
                        api.messages().get("economy.sell.yes"),
                        api.messages().get("economy.sell.no"),
                        ok -> {
                            if (!ok) {
                                return;
                            }
                            List<Line> again = bigDealLines(player, heldFish.species());
                            long recheck = again.stream().mapToLong(Line::price).sum();
                            if (again.isEmpty() || recheck != total) {
                                api.messages().send(player, "economy.sell.requote");
                                return;
                            }
                            String ref =
                                    "bigdeal:"
                                            + uuid
                                            + ":"
                                            + api.infra().season().value()
                                            + ":"
                                            + api.time().seasonWeek();
                            var _ =
                                    commit(
                                            player,
                                            again,
                                            recheck,
                                            LedgerReason.BIG_DEAL_SELL,
                                            ref,
                                            true,
                                            true);
                        });
    }

    /**
     * Same-species lines from the front up to the max count (an overflowing slot is dropped whole
     * rather than split).
     */
    private List<Line> bigDealLines(Player player, FishId species) {
        UUID uuid = player.getUniqueId();
        EconomyBalance b = market.balance();
        List<Line> all =
                scan(
                        player,
                        0,
                        36,
                        f ->
                                f.species().equals(species)
                                        ? market.quoteWith(uuid, f, b.bigDealCoef()).orElse(0)
                                        : 0);
        List<Line> picked = new ArrayList<>();
        int total = 0;
        for (Line line : all) {
            int n = line.stack().getAmount();
            if (total + n > b.bigDealMax()) {
                continue;
            }
            picked.add(line);
            total += n;
        }
        return picked;
    }
}
