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
 * Shop cache of an online player (PlayerComponent, docs 20 12.3). An immutable value; to change it,
 * build a new value with with… and put it with HarborPlayer.set. Pearls, ownership and
 * subscriptions change only after commit; only pass experience rises in memory first and is saved
 * via the write-behind queue.
 *
 * @param pass pass progress for this season
 * @param displayName display name changed with the nickname display change ticket
 *     (players.display_name)
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
     * Season pass progress.
     *
     * @param exp cumulative experience
     * @param bought levels bought with pearls (including premium+ instant levels)
     * @param claimed claimed rewards (level:track keys, PassMath.claimKey)
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
