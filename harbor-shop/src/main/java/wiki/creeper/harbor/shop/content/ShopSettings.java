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
 * Shop 수치·기능 스위치 (shop/shop.yml, 추가 제안). 문서 수치는 그대로, 문서에 없는 수치는 파일에 "제안값"으로 표시한다. 리로드하면 바로 반영된다.
 *
 * @param features 기능 스위치 (시즌 2 이후 기능은 꺼 둔다, docs 20 12.1)
 * @param topup 충전 검증 (통화 KRW, 자유 금액 10원 단위·최소 1,000원)
 * @param adminConfirmOver 관리자 진주 지급이 이 값을 넘으면 다른 관리자 확인 필요 (제안값 5,000)
 * @param codexRewards Collection CodexMilestoneEvent 키(total_25, 지역 이름) → 지급 코스메틱 ('17-2' 4.4)
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

    /** 기능 스위치. */
    public record Features(
            boolean chest,
            boolean festival,
            boolean creatorCodes,
            boolean gifting,
            boolean nameChange) {}

    /** 충전 검증 규칙 (docs 20 12.4 6단계). */
    public record Topup(String currency, long minKrw, long unitKrw) {}

    /** 선물하기 제한 (제안값, 현금 거래 우회 방지). */
    public record Gift(int dailyLimit, int minPlaytimeHours) {}

    /** 닉네임 표시 변경권 (500 진주, '6. BM 기획' 3.6). */
    public record NameChange(int price, int minLength, int maxLength) {}

    /**
     * 후원 등급별 상태형 혜택 (구독 기간에만, '6. BM 기획' 3.2).
     *
     * @param nameColor 이름 색 MiniMessage 여는 태그 (예 {@code <aqua>})
     * @param chatIcon 채팅 아이콘 글리프 (Proxy 채팅 형식이 읽는다)
     * @param title 후원자 칭호 (Season, 구독이 끝나면 회수)
     */
    public record DonorPerk(
            long monthlyPearls, String nameColor, String chatIcon, @Nullable String title) {}

    /** 웹훅 수신함 재시도 (docs 20 12.4: 1분·5분·30분, 5번 실패하면 관리자 알림). */
    public record Inbox(List<Integer> retryMinutes, int maxAttempts, int pollSeconds) {

        /** n번째 실패 뒤 다음 시도까지 분. 목록보다 많이 실패하면 마지막 값. */
        public int retryAfterMinutes(int attempts) {
            if (retryMinutes.isEmpty()) {
                return 30;
            }
            return retryMinutes.get(Math.min(Math.max(attempts, 1), retryMinutes.size()) - 1);
        }
    }

    /** 펫 표시 (docs 20 12.6: 32블록 밖 갱신 생략, 플레이어당 표시 부품 20개 이하). */
    public record Pet(int viewDistance, double followDistance, int updateTicks, int maxParts) {}

    /** 포획 연출 등급 강도 (빛기둥 높이 0이면 없음, 폭죽 여부). Fishing 등급 프리셋의 pillar·firework 플래그와 맞춘다. */
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

    /** 이 달(YYYY-MM)의 선단 후원자 한정 코스메틱. */
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
