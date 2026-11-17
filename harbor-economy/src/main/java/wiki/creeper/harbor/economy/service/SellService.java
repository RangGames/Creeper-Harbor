package wiki.creeper.harbor.economy.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.UUID;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.slf4j.Logger;
import wiki.creeper.harbor.common.db.SqlConsumer;
import wiki.creeper.harbor.common.ledger.LedgerEntry;
import wiki.creeper.harbor.common.ledger.LedgerError;
import wiki.creeper.harbor.common.ledger.LedgerException;
import wiki.creeper.harbor.common.ledger.LedgerReason;
import wiki.creeper.harbor.common.model.Currency;
import wiki.creeper.harbor.common.util.Result;
import wiki.creeper.harbor.core.api.HarborApi;
import wiki.creeper.harbor.core.api.HarborItem;
import wiki.creeper.harbor.core.item.FishLedger;
import wiki.creeper.harbor.economy.api.event.FishSoldEvent;

/**
 * 어종 판매 (docs 20 7.4 판매 흐름). 인벤토리의 어종(또는 손에 든 것)을 견적과 함께 보여주고, 확인하면 아이템을 꺼내 보관한 뒤 한 트랜잭션으로 골드
 * 지급(FISH_SELL, 참조 ID = 판매 묶음 UUID)과 고유 ID 장부 SOLD 전환을 한다. 실패하면 아이템을 돌려준다.
 */
public final class SellService {

    /** 판매 후보 한 줄 (인벤토리 칸 번호와 그때의 아이템). */
    private record Line(int slot, ItemStack stack, HarborItem.Fish fish, long price) {}

    private final HarborApi api;
    private final MarketEngine market;
    private final Logger log;

    public SellService(HarborApi api, MarketEngine market, Logger log) {
        this.api = api;
        this.market = market;
        this.log = log;
    }

    /**
     * @param heldOnly 손에 든 것만
     */
    public void open(Player player, boolean heldOnly) {
        List<Line> lines = collect(player, heldOnly);
        if (lines.isEmpty()) {
            api.messages().send(player, "economy.sell.nothing");
            return;
        }
        long total = lines.stream().mapToLong(Line::price).sum();
        api.dialogs()
                .confirm(
                        player,
                        api.messages().get("economy.sell.title"),
                        List.of(
                                api.messages()
                                        .get(
                                                "economy.sell.body",
                                                Placeholder.unparsed(
                                                        "count", String.valueOf(lines.size())),
                                                Placeholder.unparsed(
                                                        "total", String.valueOf(total)))),
                        api.messages().get("economy.sell.yes"),
                        api.messages().get("economy.sell.no"),
                        ok -> {
                            if (ok) {
                                sell(player, heldOnly, total);
                            }
                        });
    }

    private List<Line> collect(Player player, boolean heldOnly) {
        PlayerInventory inv = player.getInventory();
        List<Line> lines = new ArrayList<>();
        int from = heldOnly ? inv.getHeldItemSlot() : 0;
        int to = heldOnly ? inv.getHeldItemSlot() + 1 : 36;
        for (int slot = from; slot < to; slot++) {
            ItemStack stack = inv.getItem(slot);
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            int index = slot;
            api.items()
                    .read(stack)
                    .filter(i -> i instanceof HarborItem.Fish)
                    .map(i -> (HarborItem.Fish) i)
                    .ifPresent(
                            fish -> {
                                OptionalLong price = market.quote(player.getUniqueId(), fish);
                                if (price.isPresent() && price.getAsLong() > 0) {
                                    lines.add(
                                            new Line(
                                                    index,
                                                    stack.clone(),
                                                    fish,
                                                    price.getAsLong() * stack.getAmount()));
                                }
                            });
        }
        return lines;
    }

    private void sell(Player player, boolean heldOnly, long quoted) {
        List<Line> lines = collect(player, heldOnly);
        long total = lines.stream().mapToLong(Line::price).sum();
        if (lines.isEmpty()) {
            return;
        }
        if (total != quoted) {
            // 정각 시세 갱신·리로드로 견적이 바뀌었으면 다시 보여준다
            api.messages().send(player, "economy.sell.requote");
            open(player, heldOnly);
            return;
        }
        // 보관: 인벤토리에서 꺼낸다
        PlayerInventory inv = player.getInventory();
        List<ItemStack> custody = new ArrayList<>();
        for (Line line : lines) {
            inv.setItem(line.slot(), null);
            custody.add(line.stack());
        }
        UUID uuid = player.getUniqueId();
        String ref = "sell:" + UUID.randomUUID();
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
        var _ =
                api.currency()
                        .atomic(
                                List.of(
                                        new LedgerEntry(
                                                uuid,
                                                Currency.GOLD,
                                                total,
                                                LedgerReason.FISH_SELL,
                                                ref)),
                                ledger)
                        .thenAccept(
                                result -> {
                                    if (result
                                            instanceof
                                            Result.Err<List<Long>, LedgerError>(
                                                    LedgerError error)) {
                                        log.warn("판매 실패 {} ({}): {}", player.getName(), ref, error);
                                        custody.forEach(item -> giveBack(player, item));
                                        api.messages().send(player, "economy.sell.failed");
                                        return;
                                    }
                                    List<FishSoldEvent.Line> sold = new ArrayList<>();
                                    for (Line line : lines) {
                                        market.recordSale(
                                                line.fish().species(), line.stack().getAmount());
                                        sold.add(
                                                new FishSoldEvent.Line(
                                                        line.fish().species(),
                                                        line.fish().quality(),
                                                        line.fish().sizeCm(),
                                                        line.price()));
                                    }
                                    log.info(
                                            "판매 {} {}마리 {}G ({})",
                                            player.getName(),
                                            lines.size(),
                                            total,
                                            ref);
                                    Bukkit.getPluginManager()
                                            .callEvent(new FishSoldEvent(uuid, sold, total));
                                    api.messages()
                                            .send(
                                                    player,
                                                    "economy.sell.done",
                                                    Placeholder.unparsed(
                                                            "count", String.valueOf(lines.size())),
                                                    Placeholder.unparsed(
                                                            "total", String.valueOf(total)));
                                });
    }

    private static void giveBack(Player player, ItemStack item) {
        player.getInventory()
                .addItem(item)
                .values()
                .forEach(
                        left ->
                                player.getWorld()
                                        .dropItem(
                                                Objects.requireNonNull(player.getLocation()),
                                                left));
    }
}
