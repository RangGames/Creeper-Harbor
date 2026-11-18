package wiki.creeper.harbor.shop.pass;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.common.ledger.LedgerReason;
import wiki.creeper.harbor.common.util.Result;
import wiki.creeper.harbor.fishing.api.event.FishCaughtEvent;
import wiki.creeper.harbor.season.api.SeasonServices;
import wiki.creeper.harbor.season.api.TitleId;
import wiki.creeper.harbor.shop.ShopRuntime;
import wiki.creeper.harbor.shop.api.SeasonPassService;
import wiki.creeper.harbor.shop.api.event.SeasonPassLevelUpEvent;
import wiki.creeper.harbor.shop.content.CosmeticDef;
import wiki.creeper.harbor.shop.content.PassDef;
import wiki.creeper.harbor.shop.cosmetic.CosmeticManager;
import wiki.creeper.harbor.shop.data.ShopProfile;
import wiki.creeper.harbor.shop.data.ShopRepository;
import wiki.creeper.harbor.shop.pearl.PearlLedger;
import wiki.creeper.harbor.shop.pearl.PearlManager;
import wiki.creeper.harbor.shop.pearl.PearlTx;
import wiki.creeper.harbor.shop.pearl.ShopReject;

/**
 * 시즌 패스 「항해 일지」 (SeasonPassService 구현, docs 20 12.4). 경험치는 메모리에서 먼저 올리고 지연 쓰기 큐(키
 * player:&lt;uuid&gt;:shop_pass, 최대 5초 유실 허용)로 저장한다. 단계 보상은 즉시 트랜잭션으로 season_pass_claim 유니크와 함께 한
 * 번만 준다. 프리미엄·단계 구매는 진주 차감과 한 트랜잭션이다. 지난 시즌 패스는 팔지 않는다 (지금 시즌 패스만 다룸).
 */
public final class PassManager implements SeasonPassService, Listener {

    private record Source(Plugin owner, String key) {}

    /** 보상 지급 결과. */
    record Claimed(Set<String> keys, Set<HarborId> cosmetics, long pearls, List<String> titles) {}

    private final ShopRuntime rt;
    private final PearlManager pearls;
    private final CosmeticManager cosmetics;
    private final Set<Source> sources = ConcurrentHashMap.newKeySet();

    public PassManager(ShopRuntime rt, PearlManager pearls, CosmeticManager cosmetics) {
        this.rt = rt;
        this.pearls = pearls;
        this.cosmetics = cosmetics;
    }

    // ------------------------------------------------------------------ 조회

    private ShopProfile.Pass pass(UUID player) {
        return rt.profile(player).pass();
    }

    @Override
    public int level(UUID player) {
        return rt.currentPass()
                .map(def -> PassMath.level(def, pass(player).exp(), pass(player).bought()))
                .orElse(0);
    }

    @Override
    public long exp(UUID player) {
        return pass(player).exp();
    }

    @Override
    public int premium(UUID player) {
        return pass(player).premium();
    }

    // ------------------------------------------------------------------ 경험치

    @Override
    public boolean addExp(UUID player, String source, int units) {
        Optional<PassDef> def = rt.currentPass();
        if (def.isEmpty() || units <= 0 || rt.online(player).isEmpty()) {
            return false;
        }
        long amount = (long) def.get().exp(source) * units;
        if (amount <= 0) {
            return false;
        }
        ShopProfile.Pass before = pass(player);
        if (before.season() != def.get().season()) {
            return false;
        }
        int oldLevel = PassMath.level(def.get(), before.exp(), before.bought());
        long exp = PassMath.addExp(before.exp(), amount);
        rt.update(player, p -> p.withPass(p.pass().withExp(exp)));
        int newLevel = PassMath.level(def.get(), exp, before.bought());
        int season = def.get().season();
        rt.api()
                .writeBehind()
                .enqueue(
                        "player:" + player + ":shop_pass",
                        c -> ShopRepository.savePassExp(c, player, season, exp, newLevel));
        if (newLevel > oldLevel) {
            leveledUp(player, season, oldLevel, newLevel, false);
        }
        return true;
    }

    @Override
    public PassExpSink registerSource(Plugin owner, String source) {
        sources.add(new Source(owner, source));
        return new PassExpSink() {
            @Override
            public String source() {
                return source;
            }

            @Override
            public boolean award(UUID player, int units) {
                return owner.isEnabled() && addExp(player, source, units);
            }
        };
    }

    @Override
    public Set<String> sources() {
        Set<String> keys = new HashSet<>();
        sources.removeIf(s -> !s.owner().isEnabled());
        sources.forEach(s -> keys.add(s.key()));
        rt.currentPass()
                .ifPresent(
                        d ->
                                d.expSources().keySet().stream()
                                        .filter(k -> k.startsWith("catch."))
                                        .forEach(keys::add));
        return keys;
    }

    /** 기본 출처: 포획 (등급별). */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onCatch(FishCaughtEvent event) {
        addExp(event.getPlayer().getUniqueId(), PassMath.catchSource(event.fish().grade()), 1);
    }

    private void leveledUp(UUID player, int season, int from, int to, boolean bought) {
        rt.call(new SeasonPassLevelUpEvent(player, season, from, to, bought));
        Player online = Bukkit.getPlayer(player);
        if (online != null) {
            rt.api()
                    .messages()
                    .send(
                            online,
                            "shop.pass.level_up",
                            Placeholder.unparsed("level", String.valueOf(to)));
        }
        claim(player);
    }

    // ------------------------------------------------------------------ 보상

    /** 받을 수 있는 보상을 모두 지급한다 (단계 도달·프리미엄 구매·접속 때). */
    public void claim(UUID player) {
        Optional<PassDef> def = rt.currentPass();
        if (def.isEmpty()) {
            return;
        }
        ShopProfile.Pass p = pass(player);
        int level = PassMath.level(def.get(), p.exp(), p.bought());
        if (PassMath.claimable(def.get(), level, p.premium(), p.claimed()).isEmpty()) {
            return;
        }
        long exp = p.exp();
        var _ =
                pearls.transact(tx -> claimInTx(tx, player, def.get(), exp, -1))
                        .thenAccept(
                                outcome -> {
                                    if (outcome.result()
                                            instanceof
                                            Result.Ok<Claimed, PearlManager.Failure>(Claimed c)) {
                                        afterClaim(player, c, def.get().season());
                                    }
                                });
    }

    /**
     * 트랜잭션 안에서 보상을 지급한다. 패스 행을 잠그고 DB의 경험치와 캐시 경험치 중 큰 값으로 단계를 정한다. 이미 받은 보상은 season_pass_claim
     * 유니크로 건너뛴다.
     *
     * @param forcedPremium 0 이상이면 이 프리미엄 단계로 계산 (구매 직후)
     */
    Claimed claimInTx(PearlTx tx, UUID player, PassDef def, long cacheExp, int forcedPremium)
            throws SQLException {
        ShopProfile.Pass row = ShopRepository.lockPass(tx.connection(), player, def.season());
        long exp = Math.max(row.exp(), cacheExp);
        int level = PassMath.level(def, exp, row.bought());
        int premium = forcedPremium >= 0 ? forcedPremium : row.premium();
        ShopRepository.savePassExp(tx.connection(), player, def.season(), exp, level);
        return grantRewards(
                tx, player, def, PassMath.claimable(def, level, premium, row.claimed()));
    }

    /** 보상 목록을 지급한다 (클레임 행이 새로 들어간 것만). */
    static Claimed grantRewards(PearlTx tx, UUID player, PassDef def, List<PassDef.Reward> rewards)
            throws SQLException {
        Set<String> keys = new HashSet<>();
        Set<HarborId> cosmetics = new HashSet<>();
        List<String> titles = new ArrayList<>();
        long pearlSum = 0;
        for (PassDef.Reward reward : rewards) {
            if (!ShopRepository.insertClaim(
                    tx.connection(), player, def.season(), reward.level(), reward.claimKey())) {
                keys.add(PassMath.claimKey(reward));
                continue;
            }
            keys.add(PassMath.claimKey(reward));
            String ref = "pass:" + def.season() + ":" + reward.level() + ":" + reward.claimKey();
            HarborId cosmetic = reward.cosmetic();
            String title = reward.title();
            if (cosmetic != null) {
                if (ShopRepository.insertOwned(tx.connection(), player, cosmetic, "PASS", ref)) {
                    cosmetics.add(cosmetic);
                }
            } else if (reward.pearls() > 0) {
                tx.apply(
                        PearlLedger.Change.credit(
                                player, reward.pearls(), LedgerReason.PASS_REWARD, ref, false));
                pearlSum += reward.pearls();
            } else if (title != null) {
                titles.add(title);
            }
        }
        return new Claimed(keys, cosmetics, pearlSum, titles);
    }

    private void afterClaim(UUID player, Claimed claimed, int season) {
        rt.update(player, p -> p.withPass(p.pass().withClaimed(claimed.keys())));
        cosmetics.granted(player, claimed.cosmetics(), "PASS");
        for (String title : claimed.titles()) {
            var _ =
                    SeasonServices.titles()
                            .grant(player, TitleId.of(title), "pass:" + season + ":" + title);
        }
        Player p = Bukkit.getPlayer(player);
        if (p == null) {
            return;
        }
        for (HarborId c : claimed.cosmetics()) {
            rt.api()
                    .messages()
                    .send(
                            p,
                            "shop.pass.reward_cosmetic",
                            Placeholder.unparsed(
                                    "name",
                                    rt.cosmetic(c).map(CosmeticDef::name).orElse(c.toString())));
        }
        if (claimed.pearls() > 0) {
            rt.api()
                    .messages()
                    .send(
                            p,
                            "shop.pass.reward_pearls",
                            Placeholder.unparsed("amount", String.valueOf(claimed.pearls())));
        }
    }

    // ------------------------------------------------------------------ 구매

    /** 구매 결과 (새 상태). */
    record Purchase(int premium, int bought, int level, Claimed claimed) {}

    /** 프리미엄 단계를 올린다 (1 프리미엄 · 2 프리미엄+). */
    public CompletableFuture<Optional<String>> buyPremium(Player player, int target, UUID orderId) {
        return purchase(player, orderId, "PASS_PREMIUM", target, 0);
    }

    /** 단계 n개를 산다 (보상 코스메틱만 앞당김). */
    public CompletableFuture<Optional<String>> buyLevels(Player player, int count, UUID orderId) {
        return purchase(player, orderId, "PASS_LEVEL", -1, count);
    }

    /**
     * @return 실패 문구 키 (성공이면 빈 값)
     */
    private CompletableFuture<Optional<String>> purchase(
            Player player, UUID orderId, String kind, int targetPremium, int levels) {
        UUID uuid = player.getUniqueId();
        Optional<PassDef> found = rt.currentPass();
        if (found.isEmpty()) {
            return CompletableFuture.completedFuture(Optional.of("shop.pass.none"));
        }
        PassDef def = found.get();
        long cacheExp = pass(uuid).exp();
        int oldLevel = level(uuid);
        return pearls.transact(
                        tx -> {
                            tx.requireUnlocked(uuid);
                            ShopProfile.Pass row =
                                    ShopRepository.lockPass(tx.connection(), uuid, def.season());
                            long exp = Math.max(row.exp(), cacheExp);
                            int premium = row.premium();
                            int bought = row.bought();
                            long price;
                            LedgerReason reason;
                            String target;
                            if (targetPremium >= 0) {
                                price = PassMath.premiumPrice(def, premium, targetPremium);
                                bought += PassMath.premiumPlusBonus(def, premium, targetPremium);
                                bought = Math.min(bought, def.levels());
                                premium = targetPremium;
                                reason = LedgerReason.PASS_PREMIUM;
                                target = def.id().path() + ":premium" + targetPremium;
                            } else {
                                price = PassMath.levelPrice(def, exp, bought, levels);
                                bought += levels;
                                reason = LedgerReason.PASS_LEVEL_BUY;
                                target = def.id().path() + ":level+" + levels;
                            }
                            if (price <= 0) {
                                throw new ShopReject("shop.pass.invalid");
                            }
                            if (!ShopRepository.insertOrder(
                                    tx.connection(), orderId, uuid, kind, target, price, null)) {
                                throw new ShopReject("shop.error.duplicate");
                            }
                            tx.apply(
                                    PearlLedger.Change.debit(
                                            uuid, price, reason, "order:" + orderId));
                            int level = PassMath.level(def, exp, bought);
                            ShopRepository.updatePassPurchase(
                                    tx.connection(), uuid, def.season(), premium, bought, level);
                            ShopRepository.savePassExp(
                                    tx.connection(), uuid, def.season(), exp, level);
                            Claimed claimed =
                                    grantRewards(
                                            tx,
                                            uuid,
                                            def,
                                            PassMath.claimable(def, level, premium, row.claimed()));
                            return new Purchase(premium, bought, level, claimed);
                        })
                .thenApply(
                        outcome ->
                                switch (outcome.result()) {
                                    case Result.Ok<Purchase, PearlManager.Failure>(Purchase p) -> {
                                        rt.update(
                                                uuid,
                                                prof ->
                                                        prof.withPass(
                                                                prof.pass()
                                                                        .withPurchase(
                                                                                p.bought(),
                                                                                p.premium())));
                                        afterClaim(uuid, p.claimed(), def.season());
                                        if (p.level() > oldLevel) {
                                            rt.call(
                                                    new SeasonPassLevelUpEvent(
                                                            uuid,
                                                            def.season(),
                                                            oldLevel,
                                                            p.level(),
                                                            true));
                                        }
                                        yield Optional.<String>empty();
                                    }
                                    case Result.Err<Purchase, PearlManager.Failure>(
                                                    PearlManager.Failure f) ->
                                            Optional.of(f.messageKey());
                                });
    }

    /** 관리자: 경험치를 직접 준다 (출처 admin). */
    public boolean adminExp(UUID player, long amount) {
        Optional<PassDef> def = rt.currentPass();
        if (def.isEmpty() || rt.online(player).isEmpty() || amount <= 0) {
            return false;
        }
        ShopProfile.Pass before = pass(player);
        int oldLevel = PassMath.level(def.get(), before.exp(), before.bought());
        long exp = PassMath.addExp(before.exp(), amount);
        rt.update(player, p -> p.withPass(p.pass().withExp(exp)));
        int newLevel = PassMath.level(def.get(), exp, before.bought());
        int season = def.get().season();
        rt.api()
                .writeBehind()
                .enqueue(
                        "player:" + player + ":shop_pass",
                        c -> ShopRepository.savePassExp(c, player, season, exp, newLevel));
        if (newLevel > oldLevel) {
            leveledUp(player, season, oldLevel, newLevel, false);
        }
        return true;
    }

    /** 다른 서버에서 바뀐 패스 상태를 다시 읽는다 (경험치는 큰 값 유지). */
    public void refresh(UUID player) {
        int season = rt.season();
        var _ =
                rt.db(c -> ShopRepository.pass(c, player, season))
                        .thenAccept(
                                row ->
                                        rt.update(
                                                player,
                                                p ->
                                                        p.withPass(
                                                                new ShopProfile.Pass(
                                                                        season,
                                                                        Math.max(
                                                                                row.exp(),
                                                                                p.pass().exp()),
                                                                        row.bought(),
                                                                        row.premium(),
                                                                        row.claimed()))));
    }
}
