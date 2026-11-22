package wiki.creeper.harbor.shop.chest;

import java.security.SecureRandom;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.bukkit.entity.Player;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.common.ledger.LedgerError;
import wiki.creeper.harbor.common.ledger.LedgerReason;
import wiki.creeper.harbor.common.model.Grade;
import wiki.creeper.harbor.common.model.Keyed;
import wiki.creeper.harbor.common.util.Result;
import wiki.creeper.harbor.shop.ShopRuntime;
import wiki.creeper.harbor.shop.content.ChestDef;
import wiki.creeper.harbor.shop.content.CosmeticDef;
import wiki.creeper.harbor.shop.cosmetic.CosmeticManager;
import wiki.creeper.harbor.shop.data.ChestRepository;
import wiki.creeper.harbor.shop.data.ShopRepository;
import wiki.creeper.harbor.shop.pearl.PearlLedger;
import wiki.creeper.harbor.shop.pearl.PearlManager;
import wiki.creeper.harbor.shop.pearl.ShopReject;

/**
 * Pearl chest (docs 20 12.4, '6. BM plan' 3.4). A post-season-2 feature, so it opens only when
 * shop.yml features.chest is on. 1 open 150 · 10 opens 1,350 pearls, top grade guaranteed within 50
 * opens (carried over within the season). A 10-open processes pearl deduction, 10 rolls, pity,
 * ownership records and open records in one transaction, and reverts everything on failure midway.
 * Rolls use SecureRandom.
 */
public final class ChestManager {

    /** Result of one open. */
    public record Pull(
            HarborId cosmetic, Grade grade, boolean duplicate, int shards, boolean pityHit) {}

    /** Batch of open results. */
    public record Opened(List<Pull> pulls, int pityAfter, long shardsAfter) {}

    private final ShopRuntime rt;
    private final PearlManager pearls;
    private final CosmeticManager cosmetics;
    private final SecureRandom random = new SecureRandom();

    public ChestManager(ShopRuntime rt, PearlManager pearls, CosmeticManager cosmetics) {
        this.rt = rt;
        this.pearls = pearls;
        this.cosmetics = cosmetics;
    }

    public boolean enabled() {
        return rt.settings().features().chest();
    }

    /** Chest opened in the current season. */
    public List<ChestDef> available() {
        int season = rt.season();
        return rt.snapshot().all(ChestDef.TYPE).values().stream()
                .filter(c -> c.season() == season)
                .toList();
    }

    public CompletableFuture<Integer> pity(UUID player, ChestDef chest) {
        int season = rt.season();
        return rt.db(c -> ChestRepository.pity(c, player, season, chest.id()));
    }

    /**
     * Opens.
     *
     * @param count 1 or 10
     */
    public CompletableFuture<Result<Opened, PearlManager.Failure>> open(
            Player player, ChestDef chest, int count, UUID orderId) {
        UUID uuid = player.getUniqueId();
        int season = rt.season();
        if (!enabled() || chest.season() != season || (count != 1 && count != 10)) {
            return CompletableFuture.completedFuture(
                    Result.err(
                            new PearlManager.Failure(LedgerError.REJECTED, "shop.chest.closed")));
        }
        long price = count == 1 ? chest.priceSingle() : chest.priceTen();
        return pearls.<Opened>transact(
                        tx -> {
                            // The wallet lock must be the first statement so the ownership read
                            // below sees the previous opening's commit
                            ShopRepository.lockWallet(tx.connection(), uuid);
                            tx.requireUnlocked(uuid);
                            if (!ShopRepository.insertOrder(
                                    tx.connection(),
                                    orderId,
                                    uuid,
                                    "CHEST",
                                    chest.id().path() + "x" + count,
                                    price,
                                    null)) {
                                throw new ShopReject("shop.error.duplicate");
                            }
                            tx.apply(
                                    PearlLedger.Change.debit(
                                            uuid,
                                            price,
                                            LedgerReason.CHEST_OPEN,
                                            "order:" + orderId));
                            int pity =
                                    ChestRepository.lockPity(
                                            tx.connection(), uuid, season, chest.id());
                            Set<HarborId> owned = ShopRepository.owned(tx.connection(), uuid);
                            List<Pull> pulls = new ArrayList<>();
                            long shards = 0;
                            for (int i = 0; i < count; i++) {
                                ChestRoller.Outcome o =
                                        ChestRoller.roll(
                                                chest,
                                                pity,
                                                () -> random.nextInt(ChestDef.PPM_TOTAL));
                                HarborId result = o.entry().cosmetic();
                                boolean duplicate = !owned.add(result);
                                int gained = 0;
                                if (duplicate) {
                                    gained = chest.shards().getOrDefault(o.entry().grade(), 1);
                                    shards += gained;
                                } else {
                                    ShopRepository.insertOwned(
                                            tx.connection(),
                                            uuid,
                                            result,
                                            "CHEST",
                                            "order:" + orderId + ":" + i);
                                }
                                ChestRepository.logOpen(
                                        tx.connection(),
                                        new ChestRepository.OpenLog(
                                                uuid,
                                                chest.id(),
                                                chest.tableVersion(),
                                                o.roll(),
                                                result,
                                                o.entry().grade().key(),
                                                o.pityHit(),
                                                duplicate,
                                                o.pityBefore(),
                                                orderId));
                                pulls.add(
                                        new Pull(
                                                result,
                                                o.entry().grade(),
                                                duplicate,
                                                gained,
                                                o.pityHit()));
                                pity = o.pityAfter();
                            }
                            ChestRepository.setPity(
                                    tx.connection(), uuid, season, chest.id(), pity);
                            if (shards > 0) {
                                ChestRepository.addShards(tx.connection(), uuid, shards);
                            }
                            return new Opened(
                                    List.copyOf(pulls),
                                    pity,
                                    ChestRepository.shards(tx.connection(), uuid));
                        })
                .thenApply(
                        outcome -> {
                            if (outcome.result()
                                    instanceof Result.Ok<Opened, PearlManager.Failure>(Opened o)) {
                                Set<HarborId> fresh = new HashSet<>();
                                o.pulls().stream()
                                        .filter(p -> !p.duplicate())
                                        .forEach(p -> fresh.add(p.cosmetic()));
                                cosmetics.granted(uuid, fresh, "CHEST");
                                rt.update(uuid, p -> p.withShards(o.shardsAfter()));
                                rt.log()
                                        .info(
                                                "보물함 {} {}회 {} → {}",
                                                player.getName(),
                                                count,
                                                chest.id(),
                                                o.pulls());
                            }
                            return outcome.result();
                        });
    }

    /** Exchanges pearl shards for chest contents (cannot be exchanged back to pearls). */
    public CompletableFuture<Result<HarborId, PearlManager.Failure>> exchange(
            Player player, ChestDef chest, HarborId cosmetic, UUID orderId) {
        UUID uuid = player.getUniqueId();
        Optional<ChestDef.Entry> entry =
                chest.entries().stream().filter(e -> e.cosmetic().equals(cosmetic)).findFirst();
        Integer price = entry.map(e -> chest.exchange().get(e.grade())).orElse(null);
        if (!enabled() || price == null || price <= 0) {
            return CompletableFuture.completedFuture(
                    Result.err(
                            new PearlManager.Failure(
                                    LedgerError.REJECTED, "shop.chest.no_exchange")));
        }
        int cost = price;
        return pearls.<HarborId>transact(
                        tx -> {
                            if (!ShopRepository.insertOrder(
                                    tx.connection(),
                                    orderId,
                                    uuid,
                                    "SHARD_EXCHANGE",
                                    cosmetic.path(),
                                    0,
                                    null)) {
                                throw new ShopReject("shop.error.duplicate");
                            }
                            if (ShopRepository.ownsRow(tx.connection(), uuid, cosmetic)) {
                                throw new ShopReject("shop.error.owned");
                            }
                            if (!ChestRepository.addShards(tx.connection(), uuid, -cost)) {
                                throw new ShopReject("shop.chest.no_shards");
                            }
                            ShopRepository.insertOwned(
                                    tx.connection(), uuid, cosmetic, "SHARD", "order:" + orderId);
                            return cosmetic;
                        })
                .thenApply(
                        outcome -> {
                            if (outcome.result()
                                    instanceof Result.Ok<HarborId, PearlManager.Failure>) {
                                cosmetics.granted(uuid, Set.of(cosmetic), "SHARD");
                                rt.update(uuid, p -> p.withShards(Math.max(0, p.shards() - cost)));
                            }
                            return outcome.result();
                        });
    }

    /** Admin: actual per-grade ratios vs published odds (for the current table version). */
    public CompletableFuture<Map<Grade, long[]>> stats(ChestDef chest) {
        return rt.db(
                c -> {
                    Map<Grade, long[]> map = new EnumMap<>(Grade.class);
                    for (ChestDef.Entry e : chest.entries()) {
                        map.computeIfAbsent(e.grade(), g -> new long[2])[1] += e.ppm();
                    }
                    try (PreparedStatement ps =
                            c.prepareStatement(
                                    "SELECT grade, COUNT(*) FROM chest_open_log WHERE chest_id = ?"
                                            + " AND table_version = ? GROUP BY grade")) {
                        ps.setString(1, chest.id().toString());
                        ps.setInt(2, chest.tableVersion());
                        try (ResultSet rs = ps.executeQuery()) {
                            while (rs.next()) {
                                Optional<Grade> grade = Keyed.fromKey(Grade.class, rs.getString(1));
                                if (grade.isPresent()) {
                                    map.computeIfAbsent(grade.get(), x -> new long[2])[0] =
                                            rs.getLong(2);
                                }
                            }
                        }
                    }
                    return map;
                });
    }

    /** For display: cosmetic name. */
    public String name(HarborId cosmetic) {
        return rt.cosmetic(cosmetic).map(CosmeticDef::name).orElse(cosmetic.toString());
    }
}
