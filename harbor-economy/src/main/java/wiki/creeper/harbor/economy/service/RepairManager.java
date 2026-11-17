package wiki.creeper.harbor.economy.service;

import io.papermc.paper.datacomponent.DataComponentTypes;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import wiki.creeper.harbor.common.content.type.Balance;
import wiki.creeper.harbor.common.content.type.ItemDef;
import wiki.creeper.harbor.common.ledger.LedgerReason;
import wiki.creeper.harbor.common.model.Currency;
import wiki.creeper.harbor.core.api.HarborApi;
import wiki.creeper.harbor.core.api.HarborItem;
import wiki.creeper.harbor.core.api.StatKey;
import wiki.creeper.harbor.economy.content.EconomyBalance;

/**
 * 수리 (docs 20 7.4): 수리비 = 장비 가격 × 3% (내구도 0이면 10%) × repair_cost 보정 (수리공 −30%). 장비 가격은 buy_price
 * (레시피 비용은 제작이 들어오면 추가). 손에 든 장비를 꺼내 보관한 뒤 골드 차감(REPAIR)이 커밋되면 수리해서 돌려준다.
 */
public final class RepairManager {

    private static final StatKey REPAIR_COST = StatKey.of("repair_cost");

    private final HarborApi api;

    public RepairManager(HarborApi api) {
        this.api = api;
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
        if (def.isEmpty() || def.get().buyPrice().isEmpty()) {
            return OptionalLong.empty();
        }
        EconomyBalance b = new EconomyBalance(api.content().snapshot().single(Balance.TYPE));
        double rate = damage >= max ? b.repairBrokenRate() : b.repairRate();
        long cost =
                (long)
                        Math.ceil(
                                def.get().buyPrice().getAsInt()
                                        * rate
                                        * api.modifiers().value(player.getUniqueId(), REPAIR_COST));
        return OptionalLong.of(Math.max(1, cost));
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
        ItemStack custody = held.clone();
        player.getInventory().setItem(slot, null);
        UUID uuid = player.getUniqueId();
        var _ =
                api.currency()
                        .take(
                                uuid,
                                Currency.GOLD,
                                quoted,
                                LedgerReason.REPAIR,
                                "repair:" + UUID.randomUUID())
                        .thenAccept(
                                result -> {
                                    if (result.isOk()) {
                                        custody.setData(DataComponentTypes.DAMAGE, 0);
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
