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
import wiki.creeper.harbor.shop.data.ShopRepository;
import wiki.creeper.harbor.shop.pearl.PearlLedger;
import wiki.creeper.harbor.shop.pearl.PearlManager;
import wiki.creeper.harbor.shop.pearl.ShopReject;

/**
 * 진주 보물함 (docs 20 12.4, '6. BM 기획' 3.4). 시즌 2 이후 기능이라 shop.yml features.chest가 켜져 있어야 연다. 1회 150 ·
 * 10회 1,350 진주, 50회 안에 최상위 등급 확정(시즌 안에서 이월). 10회 묶음은 진주 차감·10번 추첨·천장·소유 기록·개봉 기록을 한 트랜잭션으로 처리하고,
 * 중간에 실패하면 전부 되돌린다. 추첨은 SecureRandom.
 */
public final class ChestManager {

    /** 개봉 한 번의 결과. */
    public record Pull(
            HarborId cosmetic, Grade grade, boolean duplicate, int shards, boolean pityHit) {}

    /** 개봉 결과 묶음. */
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

    /** 지금 시즌에 여는 보물함. */
    public List<ChestDef> available() {
        int season = rt.season();
        return rt.snapshot().all(ChestDef.TYPE).values().stream()
                .filter(c -> c.season() == season)
                .toList();
    }

    public CompletableFuture<Integer> pity(UUID player, ChestDef chest) {
        int season = rt.season();
        return rt.db(c -> ShopRepository.pity(c, player, season, chest.id()));
    }

    /**
     * 연다.
     *
     * @param count 1 또는 10
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
                            // 지갑 잠금이 첫 문장이어야 아래 소유 목록 읽기가 앞선 개봉의 커밋을 본다
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
                                    ShopRepository.lockPity(
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
                                ShopRepository.logOpen(
                                        tx.connection(),
                                        new ShopRepository.OpenLog(
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
                            ShopRepository.setPity(tx.connection(), uuid, season, chest.id(), pity);
                            if (shards > 0) {
                                ShopRepository.addShards(tx.connection(), uuid, shards);
                            }
                            return new Opened(
                                    List.copyOf(pulls),
                                    pity,
                                    ShopRepository.shards(tx.connection(), uuid));
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

    /** 진주 조각으로 보물함 구성품을 바꾼다 (진주로 되바꿀 수 없음). */
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
                            if (!ShopRepository.addShards(tx.connection(), uuid, -cost)) {
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

    /** 관리자: 실제 등급별 비율 대 공개 확률 (지금 표 버전 기준). */
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

    /** 표시용: 코스메틱 이름. */
    public String name(HarborId cosmetic) {
        return rt.cosmetic(cosmetic).map(CosmeticDef::name).orElse(cosmetic.toString());
    }
}
