package wiki.creeper.harbor.economy.service;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.slf4j.Logger;
import wiki.creeper.harbor.common.db.SqlConsumer;
import wiki.creeper.harbor.common.ledger.LedgerEntry;
import wiki.creeper.harbor.common.ledger.LedgerError;
import wiki.creeper.harbor.common.ledger.LedgerException;
import wiki.creeper.harbor.common.ledger.LedgerReason;
import wiki.creeper.harbor.common.model.Currency;
import wiki.creeper.harbor.core.api.HarborApi;
import wiki.creeper.harbor.core.item.FishLedger;
import wiki.creeper.harbor.core.task.Tasks;
import wiki.creeper.harbor.economy.api.TradeResult;
import wiki.creeper.harbor.economy.api.event.AuctionSoldEvent;
import wiki.creeper.harbor.economy.content.EconomyBalance;
import wiki.creeper.harbor.economy.service.AuctionHouse.Row;
import wiki.creeper.harbor.economy.service.AuctionHouse.SoldNotice;

/**
 * Auction buying and settlement (docs 20 7.4 buying). Reads the row and suspicion signals on a
 * virtual thread, then on the main thread handles, in one CurrencyService.atomic transaction, buyer
 * −price (AUCTION_BUY) + seller +price (AUCTION_SALE) −fee (AUCTION_FEE) + auction row SOLD +
 * auction_sale + ledger IN_AUCTION → ACTIVE + mail with the won item. If the suspicion score is
 * high, the seller's share is sent as HELD mail gold.
 */
final class AuctionSettlement {

    /** Information just before buying (read on a virtual thread). */
    private record Prepared(Row row, FraudMath.Verdict verdict) {}

    private final HarborApi api;
    private final Tasks tasks;
    private final Logger log;
    private final MailManager mail;
    private final TradeGuard guard;
    private final FraudMonitor fraud;
    private final AuctionHouse house;
    private final AuctionListings listings;

    AuctionSettlement(
            HarborApi api,
            Tasks tasks,
            Logger log,
            MailManager mail,
            TradeGuard guard,
            FraudMonitor fraud,
            AuctionHouse house,
            AuctionListings listings) {
        this.api = api;
        this.tasks = tasks;
        this.log = log;
        this.mail = mail;
        this.guard = guard;
        this.fraud = fraud;
        this.house = house;
        this.listings = listings;
    }

    private static CompletableFuture<TradeResult> done(TradeResult.Status status) {
        return CompletableFuture.completedFuture(TradeResult.of(status));
    }

    /**
     * Buys the listing as shown (the price must still match). The result completes on the main
     * thread.
     */
    CompletableFuture<TradeResult> buy(Player player, Row shown) {
        UUID buyer = player.getUniqueId();
        if (shown.seller().equals(buyer)) {
            api.messages().send(player, "economy.auction.own");
            return done(TradeResult.Status.OWN);
        }
        if (api.players().get(player).balance(Currency.GOLD) < shown.price()) {
            api.messages().send(player, "economy.auction.no_gold");
            return done(TradeResult.Status.NO_GOLD);
        }
        Optional<TradeGuard.Ticket> ticket = guard.begin(buyer);
        if (ticket.isEmpty()) {
            api.messages().send(player, "economy.busy");
            return done(TradeResult.Status.BUSY);
        }
        EconomyBalance.FraudRules rules = house.balance().fraud();
        int season = api.infra().season().value();
        CompletableFuture<Optional<Prepared>> prepared =
                api.database()
                        .async(
                                c -> {
                                    Row row = null;
                                    try (PreparedStatement ps =
                                            c.prepareStatement(
                                                    "SELECT "
                                                            + AuctionListings.COLUMNS
                                                            + " FROM auction_listing WHERE id = ? AND state = 'ACTIVE'")) {
                                        ps.setLong(1, shown.id());
                                        try (ResultSet rs = ps.executeQuery()) {
                                            if (rs.next()) {
                                                row = AuctionListings.row(rs);
                                            }
                                        }
                                    }
                                    if (row == null || row.price() != shown.price()) {
                                        return Optional.<Prepared>empty();
                                    }
                                    FraudMath.Signals signals =
                                            FraudMonitor.signals(
                                                    c,
                                                    buyer,
                                                    row.seller(),
                                                    row.price(),
                                                    row.refPrice(),
                                                    row.trapSrc(),
                                                    rules);
                                    return Optional.of(
                                            new Prepared(row, FraudMath.evaluate(signals, rules)));
                                });
        CompletableFuture<TradeResult> flow =
                prepared.thenComposeAsync(
                        found -> {
                            if (found.isEmpty()) {
                                if (player.isOnline()) {
                                    api.messages().send(player, "economy.auction.gone");
                                }
                                listings.invalidate();
                                return done(TradeResult.Status.GONE);
                            }
                            return settle(player, found.get(), season, rules);
                        },
                        tasks.main());
        return ticket.get()
                .track(flow)
                .exceptionallyAsync(
                        e -> {
                            log.warn("경매 구매 실패 {} #{}", buyer, shown.id(), e);
                            if (player.isOnline()) {
                                api.messages().send(player, "economy.auction.buy_failed");
                            }
                            return TradeResult.of(TradeResult.Status.FAILED);
                        },
                        tasks.main());
    }

    /** Main thread: the settlement transaction. */
    private CompletableFuture<TradeResult> settle(
            Player player, Prepared prepared, int season, EconomyBalance.FraudRules rules) {
        Row row = prepared.row();
        FraudMath.Verdict verdict = prepared.verdict();
        UUID buyer = player.getUniqueId();
        UUID seller = row.seller();
        boolean hold = verdict.hold();
        AuctionMath.Settlement settle = AuctionMath.settle(row.price(), row.saleFeeRate());
        String ref = AuctionHouse.ref(row.id());
        List<LedgerEntry> entries = new ArrayList<>();
        entries.add(
                new LedgerEntry(
                        buyer, Currency.GOLD, -settle.buyerPays(), LedgerReason.AUCTION_BUY, ref));
        if (!hold) {
            entries.add(
                    new LedgerEntry(
                            seller,
                            Currency.GOLD,
                            settle.sellerGross(),
                            LedgerReason.AUCTION_SALE,
                            ref));
            if (settle.fee() > 0) {
                entries.add(
                        new LedgerEntry(
                                seller,
                                Currency.GOLD,
                                -settle.fee(),
                                LedgerReason.AUCTION_FEE,
                                ref));
            }
        }
        byte[] data = Base64.getDecoder().decode(row.itemData());
        Instant holdUntil =
                rules.autoRelease()
                        ? Instant.now().plus(Duration.ofHours(rules.holdHours()))
                        : null;
        UUID uid = row.itemUuid();
        SqlConsumer extra =
                c -> {
                    lockActive(c, row.id(), row.price());
                    try (PreparedStatement ps =
                            c.prepareStatement(
                                    "INSERT INTO auction_sale (listing_id, buyer, seller, price, fee, held) VALUES (?, ?, ?, ?, ?, ?)")) {
                        ps.setLong(1, row.id());
                        ps.setString(2, buyer.toString());
                        ps.setString(3, seller.toString());
                        ps.setLong(4, row.price());
                        ps.setLong(5, settle.fee());
                        ps.setBoolean(6, hold);
                        ps.executeUpdate();
                    }
                    try (PreparedStatement ps =
                            c.prepareStatement(
                                    "UPDATE auction_listing SET state = 'SOLD', ended_at = CURRENT_TIMESTAMP(3) WHERE id = ? AND state = 'ACTIVE'")) {
                        ps.setLong(1, row.id());
                        if (ps.executeUpdate() != 1) {
                            throw new LedgerException(LedgerError.REJECTED, "이미 끝난 경매");
                        }
                    }
                    if (uid != null
                            && !FishLedger.transition(
                                    c, uid, FishLedger.State.IN_AUCTION, FishLedger.State.ACTIVE)) {
                        throw new LedgerException(
                                LedgerError.REJECTED, "장부 상태가 IN_AUCTION이 아님: " + uid);
                    }
                    MailManager.insert(
                            c,
                            buyer,
                            season,
                            "item",
                            data,
                            0,
                            MailManager.SOURCE_AUCTION_WON,
                            ref,
                            false,
                            null);
                    if (hold) {
                        MailManager.insert(
                                c,
                                seller,
                                season,
                                "gold",
                                null,
                                settle.sellerNet(),
                                MailManager.SOURCE_AUCTION_HOLD,
                                ref,
                                true,
                                holdUntil);
                    }
                    if (verdict.flag()) {
                        FraudMonitor.record(
                                c,
                                verdict,
                                buyer,
                                seller,
                                ref,
                                String.join(",", verdict.reasons())
                                        + " price="
                                        + row.price()
                                        + " ref="
                                        + row.refPrice(),
                                hold);
                    }
                };
        return Tx.run(api, tasks, entries, extra)
                .thenApply(
                        result -> {
                            listings.invalidate();
                            LedgerError error = Tx.error(result);
                            if (error != null) {
                                if (player.isOnline()) {
                                    api.messages()
                                            .send(
                                                    player,
                                                    switch (error) {
                                                        case INSUFFICIENT_FUNDS ->
                                                                "economy.auction.no_gold";
                                                        case REJECTED, DUPLICATE ->
                                                                "economy.auction.gone";
                                                        default -> "economy.auction.buy_failed";
                                                    });
                                }
                                return TradeResult.of(
                                        switch (error) {
                                            case INSUFFICIENT_FUNDS -> TradeResult.Status.NO_GOLD;
                                            case REJECTED, DUPLICATE -> TradeResult.Status.GONE;
                                            default -> TradeResult.Status.FAILED;
                                        });
                            }
                            log.info(
                                    "경매 낙찰 #{} {} → {} {}G (수수료 {}G{})",
                                    row.id(),
                                    row.sellerName(),
                                    player.getName(),
                                    row.price(),
                                    settle.fee(),
                                    hold ? ", 대금 보류" : "");
                            mail.notifyArrived(buyer);
                            if (hold) {
                                mail.notifyArrived(seller);
                            }
                            api.bus()
                                    .publish(
                                            AuctionHouse.SOLD,
                                            new SoldNotice(
                                                    seller,
                                                    row.itemName(),
                                                    row.price(),
                                                    settle.sellerNet(),
                                                    hold));
                            Bukkit.getPluginManager()
                                    .callEvent(
                                            new AuctionSoldEvent(
                                                    buyer,
                                                    seller,
                                                    row.id(),
                                                    row.itemId(),
                                                    row.price(),
                                                    settle.fee(),
                                                    hold));
                            if (verdict.flag()) {
                                fraud.announce(buyer, seller, ref, verdict, hold);
                            }
                            if (player.isOnline()) {
                                api.messages()
                                        .send(
                                                player,
                                                "economy.auction.bought",
                                                Placeholder.unparsed("name", row.itemName()),
                                                Placeholder.unparsed(
                                                        "price", String.valueOf(row.price())));
                                mail.autoClaim(player, MailManager.SOURCE_AUCTION_WON, ref);
                            }
                            return new TradeResult(
                                    TradeResult.Status.OK, row.price(), row.amount(), row.id());
                        });
    }

    /** Same transaction: locks the auction row and checks ACTIVE, price and deadline. */
    private static void lockActive(Connection c, long id, long price) throws SQLException {
        try (PreparedStatement ps =
                c.prepareStatement(
                        "SELECT state, price, expires_at FROM auction_listing WHERE id = ? FOR UPDATE")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()
                        || !rs.getString(1).equals("ACTIVE")
                        || rs.getLong(2) != price
                        || !rs.getTimestamp(3).toInstant().isAfter(Instant.now())) {
                    throw new LedgerException(LedgerError.REJECTED, "진행 중인 경매가 아님: " + id);
                }
            }
        }
    }
}
