package wiki.creeper.harbor.shop.data;

import java.time.Instant;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.donor.DonorTier;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.shop.api.PearlService;
import wiki.creeper.harbor.shop.content.CosmeticDef;
import wiki.creeper.harbor.shop.donor.DonorGrace;

/**
 * 접속 중인 플레이어의 Shop 캐시 (PlayerComponent, docs 20 12.3). 변경 불가 값이며 바꿀 때는 with…로 새 값을 만들어
 * HarborPlayer.set으로 넣는다. 진주·소유·구독은 커밋 뒤에만 바뀌고, 패스 경험치만 메모리에서 먼저 오른 뒤 지연 쓰기 큐로 저장된다.
 *
 * @param pass 이번 시즌 패스 진행
 * @param displayName 닉네임 표시 변경권으로 바꾼 표시 이름 (players.display_name)
 */
public record ShopProfile(
        PearlService.Balance wallet,
        boolean locked,
        Set<HarborId> owned,
        Map<CosmeticDef.Category, HarborId> equipped,
        DonorTier tier,
        DonorGrace.State subscription,
        @Nullable Instant periodEnd,
        Pass pass,
        @Nullable String displayName,
        long shards,
        @Nullable String creatorCode) {

    public static final ShopProfile EMPTY =
            new ShopProfile(
                    PearlService.Balance.EMPTY,
                    false,
                    Set.of(),
                    Map.of(),
                    DonorTier.NONE,
                    DonorGrace.State.ENDED,
                    null,
                    Pass.EMPTY,
                    null,
                    0,
                    null);

    public ShopProfile {
        owned = Set.copyOf(owned);
        equipped = equipped.isEmpty() ? Map.of() : Map.copyOf(new EnumMap<>(equipped));
    }

    /**
     * 시즌 패스 진행.
     *
     * @param exp 누적 경험치
     * @param bought 진주로 산 단계 (프리미엄+ 즉시 단계 포함)
     * @param claimed 받은 보상 (단계:트랙 키, PassMath.claimKey)
     */
    public record Pass(int season, long exp, int bought, int premium, Set<String> claimed) {

        public static final Pass EMPTY = new Pass(0, 0, 0, 0, Set.of());

        public Pass {
            claimed = Set.copyOf(claimed);
        }

        public Pass withExp(long newExp) {
            return new Pass(season, newExp, bought, premium, claimed);
        }

        public Pass withClaimed(Set<String> more) {
            Set<String> all = new HashSet<>(claimed);
            all.addAll(more);
            return new Pass(season, exp, bought, premium, all);
        }

        public Pass withPurchase(int newBought, int newPremium) {
            return new Pass(season, exp, newBought, newPremium, claimed);
        }
    }

    public boolean owns(HarborId cosmetic) {
        return owned.contains(cosmetic);
    }

    public ShopProfile withWallet(PearlService.Balance newWallet) {
        return new ShopProfile(
                newWallet,
                locked,
                owned,
                equipped,
                tier,
                subscription,
                periodEnd,
                pass,
                displayName,
                shards,
                creatorCode);
    }

    public ShopProfile withLocked(boolean newLocked) {
        return new ShopProfile(
                wallet,
                newLocked,
                owned,
                equipped,
                tier,
                subscription,
                periodEnd,
                pass,
                displayName,
                shards,
                creatorCode);
    }

    public ShopProfile withOwned(Set<HarborId> newOwned) {
        return new ShopProfile(
                wallet,
                locked,
                newOwned,
                equipped,
                tier,
                subscription,
                periodEnd,
                pass,
                displayName,
                shards,
                creatorCode);
    }

    public ShopProfile plusOwned(Set<HarborId> more) {
        Set<HarborId> all = new HashSet<>(owned);
        all.addAll(more);
        return withOwned(all);
    }

    public ShopProfile withEquipped(CosmeticDef.Category slot, @Nullable HarborId cosmetic) {
        Map<CosmeticDef.Category, HarborId> map = new HashMap<>(equipped);
        if (cosmetic == null) {
            map.remove(slot);
        } else {
            map.put(slot, cosmetic);
        }
        return new ShopProfile(
                wallet,
                locked,
                owned,
                map,
                tier,
                subscription,
                periodEnd,
                pass,
                displayName,
                shards,
                creatorCode);
    }

    public ShopProfile withDonor(
            DonorTier newTier, DonorGrace.State newState, @Nullable Instant newEnd) {
        return new ShopProfile(
                wallet,
                locked,
                owned,
                equipped,
                newTier,
                newState,
                newEnd,
                pass,
                displayName,
                shards,
                creatorCode);
    }

    public ShopProfile withPass(Pass newPass) {
        return new ShopProfile(
                wallet,
                locked,
                owned,
                equipped,
                tier,
                subscription,
                periodEnd,
                newPass,
                displayName,
                shards,
                creatorCode);
    }

    public ShopProfile withDisplayName(@Nullable String name) {
        return new ShopProfile(
                wallet,
                locked,
                owned,
                equipped,
                tier,
                subscription,
                periodEnd,
                pass,
                name,
                shards,
                creatorCode);
    }

    public ShopProfile withShards(long newShards) {
        return new ShopProfile(
                wallet,
                locked,
                owned,
                equipped,
                tier,
                subscription,
                periodEnd,
                pass,
                displayName,
                newShards,
                creatorCode);
    }

    public ShopProfile withCreator(@Nullable String code) {
        return new ShopProfile(
                wallet,
                locked,
                owned,
                equipped,
                tier,
                subscription,
                periodEnd,
                pass,
                displayName,
                shards,
                code);
    }
}
