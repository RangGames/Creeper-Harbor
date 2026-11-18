package wiki.creeper.harbor.economy.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.function.ToLongFunction;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
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
import wiki.creeper.harbor.economy.api.event.FishSoldEvent;
import wiki.creeper.harbor.economy.content.EconomyBalance;
import wiki.creeper.harbor.jobs.api.CapstoneState;
import wiki.creeper.harbor.jobs.api.JobsService;

/**
 * 어종 판매 (docs 20 7.4 판매 흐름). 인벤토리의 어종(또는 손에 든 것)을 견적과 함께 보여주고, 확인하면 아이템을 꺼내 보관한 뒤 한 트랜잭션으로 골드
 * 지급(FISH_SELL, 참조 ID = 판매 묶음 UUID)과 고유 ID 장부 SOLD 전환을 한다. 실패하면 아이템을 돌려준다.
 *
 * <ul>
 *   <li>일괄 판매 미끄러짐 (제안): 같은 어종을 한 번에 32마리 넘게 팔면 그 묶음은 "이 판매가 반영됐을 때의 M"과 현재 M의 평균으로 계산한다 (경제 계열 '대량
 *       거래' bulk_sell_slip이 하락폭을 줄인다).
 *   <li>큰손 거래 (경제 계열 캡스톤): 주 1회, 손에 든 어종과 같은 종 최대 64마리를 M = 1.0으로 판다. 사유 BIG_DEAL_SELL, 참조 ID =
 *       uuid + 시즌 + 주차라서 같은 주 두 번 지급이 원장에서 막힌다. 판매량은 market_sales에 그대로 더한다.
 * </ul>
 */
public final class SellService {

    /** 판매 후보 한 줄 (인벤토리 칸 번호와 그때의 아이템). */
    private record Line(int slot, ItemStack stack, HarborItem.Fish fish, long price) {}

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
                                                        "count", String.valueOf(count(lines))),
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

    private static int count(List<Line> lines) {
        return lines.stream().mapToInt(l -> l.stack().getAmount()).sum();
    }

    /** 인벤토리 칸의 어종들. price는 줄마다 (단가 × 개수), 단가 계산은 pricer. */
    private List<Line> scan(
            Player player, int from, int to, ToLongFunction<HarborItem.Fish> pricer) {
        PlayerInventory inv = player.getInventory();
        List<Line> lines = new ArrayList<>();
        for (int slot = from; slot < to; slot++) {
            ItemStack stack = inv.getItem(slot);
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            Optional<HarborItem> item = api.items().read(stack);
            if (item.isPresent() && item.get() instanceof HarborItem.Fish fish) {
                long unit = pricer.applyAsLong(fish);
                if (unit > 0) {
                    lines.add(new Line(slot, stack.clone(), fish, unit * stack.getAmount()));
                }
            }
        }
        return lines;
    }

    private List<Line> collect(Player player, boolean heldOnly) {
        PlayerInventory inv = player.getInventory();
        int from = heldOnly ? inv.getHeldItemSlot() : 0;
        int to = heldOnly ? inv.getHeldItemSlot() + 1 : 36;
        UUID uuid = player.getUniqueId();
        List<Line> lines = scan(player, from, to, f -> market.quote(uuid, f).orElse(0));
        // 일괄 판매 미끄러짐: 어종별 마릿수가 기준을 넘으면 그 어종 줄을 조정한 계수로 다시 계산
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
                                unit.getAsLong() * line.stack().getAmount()));
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
            // 정각 시세 갱신·리로드로 견적이 바뀌었으면 다시 보여준다
            api.messages().send(player, "economy.sell.requote");
            open(player, heldOnly);
            return;
        }
        commit(player, lines, total, LedgerReason.FISH_SELL, "sell:" + UUID.randomUUID(), false);
    }

    /** 보관 → 한 트랜잭션 (골드 + 장부 SOLD) → 커밋 후 판매량·이벤트, 실패하면 돌려준다. */
    private void commit(
            Player player,
            List<Line> lines,
            long total,
            LedgerReason reason,
            String ref,
            boolean bigDeal) {
        UUID uuid = player.getUniqueId();
        Optional<TradeGuard.Ticket> ticket = guard.begin(uuid);
        if (ticket.isEmpty()) {
            api.messages().send(player, "economy.busy");
            return;
        }
        // 보관: 인벤토리에서 꺼낸다
        PlayerInventory inv = player.getInventory();
        List<ItemStack> custody = new ArrayList<>();
        for (Line line : lines) {
            inv.setItem(line.slot(), null);
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
        var _ =
                ticket.get()
                        .track(
                                api.currency()
                                        .atomic(
                                                List.of(
                                                        new LedgerEntry(
                                                                uuid,
                                                                Currency.GOLD,
                                                                total,
                                                                reason,
                                                                ref)),
                                                ledger))
                        .thenAccept(
                                result -> {
                                    LedgerError error = Tx.error(result);
                                    if (error != null) {
                                        log.warn("판매 실패 {} ({}): {}", player.getName(), ref, error);
                                        mail.giveOrMail(uuid, custody, "sell_return", ref);
                                        if (player.isOnline()) {
                                            api.messages()
                                                    .send(
                                                            player,
                                                            bigDeal
                                                                            && error
                                                                                    == LedgerError
                                                                                            .DUPLICATE
                                                                    ? "economy.big_deal.used"
                                                                    : "economy.sell.failed");
                                        }
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
                                                                "count",
                                                                String.valueOf(count(lines))),
                                                        Placeholder.unparsed(
                                                                "total", String.valueOf(total)));
                                    }
                                });
    }

    // ------------------------------------------------------------------ 큰손 거래

    /** /큰손: 손에 든 어종과 같은 종을 최대 64마리 M = 1.0으로 판다 (확인 화면). */
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
                            commit(player, again, recheck, LedgerReason.BIG_DEAL_SELL, ref, true);
                        });
    }

    /** 같은 종 줄을 앞에서부터 최대 마릿수까지 (넘치는 칸은 그 칸을 통째로 빼서 쪼개지 않는다). */
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
