package wiki.creeper.harbor.economy.service;

import io.papermc.paper.datacomponent.DataComponentTypes;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.content.type.Balance;
import wiki.creeper.harbor.common.content.type.ItemDef;
import wiki.creeper.harbor.common.db.SqlConsumer;
import wiki.creeper.harbor.common.ledger.LedgerEntry;
import wiki.creeper.harbor.common.ledger.LedgerError;
import wiki.creeper.harbor.common.ledger.LedgerReason;
import wiki.creeper.harbor.common.model.Currency;
import wiki.creeper.harbor.common.util.Result;
import wiki.creeper.harbor.core.api.HarborApi;
import wiki.creeper.harbor.core.api.HarborItem;
import wiki.creeper.harbor.core.api.StatKey;
import wiki.creeper.harbor.economy.api.RepairService;
import wiki.creeper.harbor.economy.content.EconomyBalance;

/**
 * Repair (docs 20 7.4): repair cost = gear price × 3% (10% at 0 durability) × repair_cost modifier
 * (repairer −30%). Gear price is buy_price, or the gold cost of the recipe that makes the gear if
 * absent. Takes the held gear and holds it; once the gold deduction (REPAIR) commits, repairs it
 * and gives it back. World ship repair passes the ship price through {@link RepairService} and is
 * computed the same way.
 */
public final class RepairManager implements RepairService {

    private static final StatKey REPAIR_COST = StatKey.of("repair_cost");

    private final HarborApi api;
    private final TradeGuard guard;
    private final CraftManager crafts;

    public RepairManager(HarborApi api, TradeGuard guard, CraftManager crafts) {
        this.api = api;
        this.guard = guard;
        this.crafts = crafts;
    }

    @Override
    public long quote(UUID player, long equipmentPrice, boolean broken) {
        EconomyBalance b = new EconomyBalance(api.content().snapshot().single(Balance.TYPE));
        double rate = broken ? b.repairBrokenRate() : b.repairRate();
        long cost =
                (long)
                        Math.ceil(
                                equipmentPrice * rate * api.modifiers().value(player, REPAIR_COST));
        return Math.max(1, cost);
    }

    @Override
    public CompletableFuture<Result<Long, LedgerError>> pay(
            UUID player, long cost, String refId, @Nullable SqlConsumer extra) {
        if (cost <= 0) {
            throw new IllegalArgumentException("수리비는 0보다 커야 합니다: " + cost);
        }
        return api.currency()
                .atomic(
                        List.of(
                                new LedgerEntry(
                                        player, Currency.GOLD, -cost, LedgerReason.REPAIR, refId)),
                        extra)
                .thenApply(r -> r.map(List::getFirst));
    }

    OptionalLong cost(Player player, ItemStack stack) {
        Integer damage = stack.getData(DataComponentTypes.DAMAGE);
        Integer max = stack.getData(DataComponentTypes.MAX_DAMAGE);
        if (damage == null || max == null || damage <= 0) {
            return OptionalLong.empty();
        }
        Optional<ItemDef> def =
                api.items()
                        .read(stack)
                        .filter(i -> i instanceof HarborItem.Plain)
                        .flatMap(
                                i ->
                                        api.content()
                                                .snapshot()
                                                .get(
                                                        ItemDef.TYPE,
                                                        ((HarborItem.Plain) i).id().id()));
        if (def.isEmpty()) {
            return OptionalLong.empty();
        }
        OptionalLong price =
                def.get().buyPrice().isPresent()
                        ? OptionalLong.of(def.get().buyPrice().getAsInt())
                        : crafts.recipeGold(def.get().id());
        if (price.isEmpty()) {
            return OptionalLong.empty();
        }
        return OptionalLong.of(quote(player.getUniqueId(), price.getAsLong(), damage >= max));
    }

    public void open(Player player) {
        ItemStack held = player.getInventory().getItemInMainHand();
        OptionalLong cost = cost(player, held);
        if (cost.isEmpty()) {
            api.messages().send(player, "economy.repair.nothing");
            return;
        }
        api.dialogs()
                .confirm(
                        player,
                        api.messages().get("economy.repair.title"),
                        List.of(
                                api.messages()
                                        .get(
                                                "economy.repair.body",
                                                Placeholder.unparsed(
                                                        "cost", String.valueOf(cost.getAsLong())))),
                        api.messages().get("economy.repair.yes"),
                        api.messages().get("economy.repair.no"),
                        ok -> {
                            if (ok) {
                                repair(player, cost.getAsLong());
                            }
                        });
    }

    private void repair(Player player, long quoted) {
        int slot = player.getInventory().getHeldItemSlot();
        ItemStack held = player.getInventory().getItemInMainHand();
        OptionalLong cost = cost(player, held);
        if (cost.isEmpty() || cost.getAsLong() != quoted) {
            api.messages().send(player, "economy.repair.changed");
            return;
        }
        UUID uuid = player.getUniqueId();
        var ticket = guard.begin(uuid);
        if (ticket.isEmpty()) {
            api.messages().send(player, "economy.busy");
            return;
        }
        ItemStack custody = held.clone();
        player.getInventory().setItem(slot, null);
        var _ =
                ticket.get()
                        .track(
                                api.currency()
                                        .take(
                                                uuid,
                                                Currency.GOLD,
                                                quoted,
                                                LedgerReason.REPAIR,
                                                "repair:" + UUID.randomUUID()))
                        .thenAccept(
                                result -> {
                                    if (result.isOk()) {
                                        custody.setData(DataComponentTypes.DAMAGE, 0);
                                    }
                                    if (!player.isOnline()) {
                                        // If they left between commits, send to the mailbox
                                        api.mail()
                                                .ifPresent(
                                                        m -> {
                                                            var _ =
                                                                    m.sendItem(
                                                                            uuid,
                                                                            custody,
                                                                            "repair_return",
                                                                            "repair:"
                                                                                    + uuid
                                                                                    + ":"
                                                                                    + System
                                                                                            .nanoTime());
                                                        });
                                        return;
                                    }
                                    player.getInventory()
                                            .addItem(custody)
                                            .values()
                                            .forEach(
                                                    left ->
                                                            player.getWorld()
                                                                    .dropItem(
                                                                            Objects.requireNonNull(
                                                                                    player
                                                                                            .getLocation()),
                                                                            left));
                                    api.messages()
                                            .send(
                                                    player,
                                                    result.isOk()
                                                            ? "economy.repair.done"
                                                            : "economy.repair.failed");
                                });
    }
}
