package wiki.creeper.harbor.shop.content;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.content.ContentType;
import wiki.creeper.harbor.common.content.ParseContext;
import wiki.creeper.harbor.common.donor.DonorTier;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.common.model.Grade;
import wiki.creeper.harbor.common.model.Keyed;
import wiki.creeper.harbor.common.yaml.YamlNode;

/**
 * Shop numbers and feature switches (shop/shop.yml, proposed). Documented numbers as-is;
 * undocumented ones are marked as proposed values in the file. Applied immediately on reload.
 *
 * @param features feature switches (post-season-2 features stay off, docs 20 12.1)
 * @param topup top-up validation (currency KRW, free amount in 10 KRW steps, minimum 1,000 KRW)
 * @param adminConfirmOver admin pearl grants above this need confirmation from another admin
 *     (proposed value 5,000)
 * @param codexRewards Collection CodexMilestoneEvent key (total_25, region name) → cosmetic granted
 *     ('17-2' 4.4)
 */
public record ShopSettings(
        Features features,
        Topup topup,
        int adminConfirmOver,
        Gift gift,
        NameChange nameChange,
        Map<DonorTier, DonorPerk> donor,
        int donorGraceDays,
        Map<String, HarborId> donorMonthlyCosmetics,
        Inbox inbox,
        int reconcileHour,
        int reconcileWindowHours,
        Pet pet,
        Map<Grade, FxStrength> catchFx,
        Map<String, HarborId> codexRewards) {

    /** Feature switches. */
    public record Features(
            boolean chest,
            boolean festival,
            boolean creatorCodes,
            boolean gifting,
            boolean nameChange) {}

    /** Top-up validation rules (docs 20 12.4 step 6). */
    public record Topup(String currency, long minKrw, long unitKrw) {}

    /** Gifting limits (proposed value, to prevent real-money trade workarounds). */
    public record Gift(int dailyLimit, int minPlaytimeHours) {}

    /** Display name change ticket (500 pearls, '6. BM plan' 3.6). */
    public record NameChange(int price, int minLength, int maxLength) {}

    /**
     * Status perks per donor tier (only during the subscription, '6. BM plan' 3.2).
     *
     * @param nameColor name color MiniMessage opening tag (e.g. {@code <aqua>})
     * @param chatIcon chat icon glyph (read by the Proxy chat format)
     * @param title donor title (Season, revoked when the subscription ends)
     */
    public record DonorPerk(
            long monthlyPearls, String nameColor, String chatIcon, @Nullable String title) {}

    /** Webhook inbox retries (docs 20 12.4: 1 min, 5 min, 30 min; admin alert after 5 failures). */
    public record Inbox(List<Integer> retryMinutes, int maxAttempts, int pollSeconds) {

        /**
         * Minutes until the next attempt after the n-th failure. The last value if failures exceed
         * the list.
         */
        public int retryAfterMinutes(int attempts) {
            if (retryMinutes.isEmpty()) {
                return 30;
            }
            return retryMinutes.get(Math.min(Math.max(attempts, 1), retryMinutes.size()) - 1);
        }
    }

    /**
     * Pet display (docs 20 12.6: skip updates beyond 32 blocks, at most 20 display parts per
     * player).
     */
    public record Pet(int viewDistance, double followDistance, int updateTicks, int maxParts) {}

    /**
     * Catch effect grade intensity (pillar height 0 means none, fireworks or not). Matches the
     * pillar/firework flags of Fishing's grade presets.
     */
    public record FxStrength(int pillarHeight, boolean firework) {}

    public static final ContentType.Single<ShopSettings> TYPE =
            new ContentType.Single<>("shop_settings", "shop/shop.yml", false, ShopSettings::parse);

    public DonorPerk perk(DonorTier tier) {
        DonorPerk perk = donor.get(tier);
        return perk != null ? perk : new DonorPerk(0, "", "", null);
    }

    public FxStrength fx(Grade grade) {
        FxStrength strength = catchFx.get(grade);
        return strength != null ? strength : new FxStrength(0, false);
    }

    /** Fleet-sponsor-only cosmetic for this month (YYYY-MM). */
    public Optional<HarborId> monthlyCosmetic(String yearMonth) {
        return Optional.ofNullable(donorMonthlyCosmetics.get(yearMonth));
    }

    static ShopSettings parse(YamlNode node, ParseContext ctx) {
        node.allowOnly(
                Set.of(
                        "features",
                        "topup",
                        "admin",
                        "gift",
                        "name_change",
                        "donor",
                        "inbox",
                        "reconcile",
                        "pet",
                        "catch_fx",
                        "codex_rewards"));
        YamlNode f = node.get("features").orElse(node);
        Features features =
                new Features(
                        f.bool("chest", false),
                        f.bool("festival", false),
                        f.bool("creator_codes", false),
                        f.bool("gifting", true),
                        f.bool("name_change", true));
        YamlNode t = node.get("topup").orElse(node);
        Topup topup =
                new Topup(
                        t.string("currency", "KRW"),
                        t.longValue("min_krw", 1000),
                        Math.max(1, t.longValue("unit_krw", 10)));
        YamlNode admin = node.get("admin").orElse(node);
        YamlNode g = node.get("gift").orElse(node);
        YamlNode n = node.get("name_change").orElse(node);
        YamlNode d = node.get("donor").orElse(node);
        Map<DonorTier, DonorPerk> perks = new EnumMap<>(DonorTier.class);
        for (DonorTier tier : List.of(DonorTier.HARBOR, DonorTier.FLEET)) {
            YamlNode p = d.get(tier.name().toLowerCase(Locale.ROOT)).orElse(null);
            if (p == null) {
                continue;
            }
            p.allowOnly(Set.of("monthly_pearls", "name_color", "chat_icon", "title"));
            String title = p.optString("title").orElse(null);
            if (title != null) {
                TitleRefs.ref(ctx, title, p);
            }
            perks.put(
                    tier,
                    new DonorPerk(
                            p.longValue("monthly_pearls", 0),
                            p.string("name_color", ""),
                            p.string("chat_icon", ""),
                            title));
        }
        Map<String, HarborId> monthly = new LinkedHashMap<>();
        d.get("monthly_cosmetics")
                .ifPresent(
                        m ->
                                m.entries()
                                        .forEach(
                                                (month, v) -> {
                                                    if (!month.matches("\\d{4}-\\d{2}")) {
                                                        v.error("월은 YYYY-MM 형식입니다: " + month);
                                                    }
                                                    v.asId(CosmeticDef.PREFIX)
                                                            .ifPresent(
                                                                    c -> {
                                                                        monthly.put(month, c);
                                                                        ctx.ref(
                                                                                CosmeticDef.TYPE
                                                                                        .name(),
                                                                                c,
                                                                                v.pos());
                                                                    });
                                                }));
        YamlNode i = node.get("inbox").orElse(node);
        List<Integer> retry = i.list("retry_minutes").stream().map(YamlNode::asInt).toList();
        YamlNode r = node.get("reconcile").orElse(node);
        YamlNode pet = node.get("pet").orElse(node);
        Map<Grade, FxStrength> fx = new EnumMap<>(Grade.class);
        node.get("catch_fx")
                .ifPresent(
                        c ->
                                c.entries()
                                        .forEach(
                                                (k, v) ->
                                                        Keyed.fromKey(Grade.class, k)
                                                                .ifPresentOrElse(
                                                                        grade ->
                                                                                fx.put(
                                                                                        grade,
                                                                                        new FxStrength(
                                                                                                v
                                                                                                        .integer(
                                                                                                                "pillar",
                                                                                                                0,
                                                                                                                0,
                                                                                                                64),
                                                                                                v
                                                                                                        .bool(
                                                                                                                "firework",
                                                                                                                false))),
                                                                        () ->
                                                                                v.error(
                                                                                        "알 수 없는 등급: "
                                                                                                + k))));
        Map<String, HarborId> codex = new LinkedHashMap<>();
        node.get("codex_rewards")
                .ifPresent(
                        c ->
                                c.entries()
                                        .forEach(
                                                (k, v) ->
                                                        v.asId(CosmeticDef.PREFIX)
                                                                .ifPresent(
                                                                        id -> {
                                                                            codex.put(k, id);
                                                                            ctx.ref(
                                                                                    CosmeticDef.TYPE
                                                                                            .name(),
                                                                                    id,
                                                                                    v.pos());
                                                                        })));
        return new ShopSettings(
                features,
                topup,
                admin.integer("pearl_confirm_over", 5000, 0, 10_000_000),
                new Gift(
                        g.integer("daily_limit", 5, 0, 100),
                        g.integer("min_playtime_hours", 5, 0, 1000)),
                new NameChange(
                        n.integer("price", 500, 1, 100_000),
                        n.integer("min_length", 2, 1, 16),
                        n.integer("max_length", 12, 1, 16)),
                Map.copyOf(perks),
                d.integer("grace_days", 3, 0, 60),
                Map.copyOf(monthly),
                new Inbox(
                        retry.isEmpty() ? List.of(1, 5, 30) : retry,
                        i.integer("max_attempts", 5, 1, 100),
                        i.integer("poll_seconds", 5, 1, 600)),
                r.integer("hour", 5, 0, 23),
                r.integer("window_hours", 48, 1, 24 * 14),
                new Pet(
                        pet.integer("view_distance", 32, 4, 128),
                        pet.number("follow_distance", 1.6),
                        pet.integer("update_ticks", 4, 1, 40),
                        pet.integer("max_parts", 20, 1, 300)),
                Map.copyOf(fx),
                Map.copyOf(codex));
    }
}
