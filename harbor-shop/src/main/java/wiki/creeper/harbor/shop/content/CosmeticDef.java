package wiki.creeper.harbor.shop.content;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.content.ContentType;
import wiki.creeper.harbor.common.content.ParseContext;
import wiki.creeper.harbor.common.id.FishId;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.common.id.RigId;
import wiki.creeper.harbor.common.model.Grade;
import wiki.creeper.harbor.common.model.Keyed;
import wiki.creeper.harbor.common.yaml.YamlNode;

/**
 * 코스메틱 정의 (cosmetics.yml, '11. 데이터 설계' 2.8, docs 20 12.3). 진주로 산 것은 아이템이 아니라 계정 소유
 * 기록(cosmetics_owned)이라 거래·경매로 옮겨지지 않는다. 그래서 tradeable은 없거나 false여야 한다 ('6. BM 기획' 1장 경제 분리).
 *
 * @param price 진주 가격 (0이면 상점에서 팔지 않음: 패스·보물함·후원·무료 지급 전용)
 * @param season 시즌 한정 상품의 시즌 (0이면 상시). availability = season이면 그 시즌 동안만 판다
 * @param requiresFish 구매 조건: 이 어종을 직접 잡았어야 함 (전설 어종 기념 스킨, Collection codex_total)
 * @param model 아이템 모델 (낚싯대 스킨의 item_model, 상점 아이콘)
 * @param preset 포획 연출 프리셋 (catch_fx, shop/catch_fx.yml)
 * @param rig 펫·선박 장식 리그 (Core RigService)
 * @param color 이름 색 (name_color, MiniMessage 태그 예 {@code <gradient:#55ffff:#5555ff>})
 * @param glyph 채팅 아이콘·칭호 테두리 글리프 (리소스팩 폰트 문자, 비우면 글자 표시)
 * @param particle 찌·낚싯줄 효과 파티클 (line_fx, 바닐라 파티클 키)
 * @param particleColor 파티클 색 (#rrggbb, dust 계열만)
 */
public record CosmeticDef(
        HarborId id,
        String name,
        List<String> lore,
        Category category,
        Grade grade,
        int price,
        Availability availability,
        int season,
        @Nullable FishId requiresFish,
        @Nullable String model,
        @Nullable HarborId preset,
        @Nullable RigId rig,
        @Nullable String color,
        @Nullable String glyph,
        @Nullable String particle,
        @Nullable String particleColor,
        boolean giftable) {

    /** 분류 ('11. 데이터 설계' 2.8). 장착 칸도 분류와 같다 (분류마다 한 개). */
    public enum Category implements Keyed {
        ROD_SKIN,
        LINE_FX,
        CATCH_FX,
        BOAT_SKIN,
        PET,
        TITLE_FRAME,
        NAME_COLOR,
        CHAT_ICON,
        EMOTE,
        OUTFIT,
        AQUARIUM_THEME,
        HOUSING_DECO;

        @Override
        public String key() {
            return Keyed.keyOf(this);
        }
    }

    /** 얻는 경로. 상점 진열은 permanent·rotation·season만. */
    public enum Availability implements Keyed {
        PERMANENT,
        ROTATION,
        SEASON,
        PASS,
        CHEST,
        DONOR,
        FREE;

        @Override
        public String key() {
            return Keyed.keyOf(this);
        }

        /** 진주 상점에서 파는 경로인지. */
        public boolean sold() {
            return this == PERMANENT || this == ROTATION || this == SEASON;
        }
    }

    public static final String PREFIX = "cosmetic/";

    public static final ContentType.Entries<CosmeticDef> TYPE =
            new ContentType.Entries<>(
                    "cosmetic",
                    List.of("cosmetics.yml", "cosmetics/*.yml"),
                    PREFIX,
                    CosmeticDef::parse,
                    CosmeticDef::id);

    private static final Set<String> FIELDS =
            Set.of(
                    "id",
                    "name",
                    "lore",
                    "category",
                    "grade",
                    "price",
                    "availability",
                    "season",
                    "requires",
                    "model",
                    "preset",
                    "rig",
                    "color",
                    "glyph",
                    "particle",
                    "particle_color",
                    "giftable",
                    "tradeable");

    /** 진주 상점에서 지금 팔 수 있는지 (시즌 한정은 그 시즌만). 로테이션 여부는 {@link RotationPlan}이 따로 본다. */
    public boolean sellableIn(int currentSeason) {
        if (price <= 0 || !availability.sold()) {
            return false;
        }
        return availability != Availability.SEASON || season == currentSeason;
    }

    public Optional<FishId> fishRequirement() {
        return Optional.ofNullable(requiresFish);
    }

    static CosmeticDef parse(YamlNode node, ParseContext ctx) {
        node.allowOnly(FIELDS);
        HarborId id =
                node.require("id")
                        .flatMap(n -> n.asId(PREFIX))
                        .orElse(HarborId.parse("cosmetic/invalid"));
        Category category = node.enumValue("category", Category.class).orElse(Category.EMOTE);
        Grade grade = node.enumValue("grade", Grade.class).orElse(Grade.COMMON);
        Availability availability =
                node.enumValue("availability", Availability.class).orElse(Availability.FREE);
        int price = node.integer("price", 0, 0, 100_000);
        int season = node.integer("season", 0, 0, 1000);
        if (availability.sold() && price <= 0) {
            node.error("상점 판매(" + availability.key() + ") 코스메틱은 price가 있어야 합니다");
        }
        if (availability == Availability.SEASON && season == 0) {
            node.error("availability: season이면 season 번호가 있어야 합니다");
        }
        // 경제 분리 ('6. BM 기획' 1장): 코스메틱은 거래 불가
        node.get("tradeable")
                .ifPresent(
                        t -> {
                            if (t.asBoolean()) {
                                t.error("코스메틱은 거래할 수 없습니다 (tradeable은 없거나 false)");
                            }
                        });
        FishId fish = null;
        if (node.has("requires")) {
            YamlNode r = node.get("requires").orElseThrow();
            r.allowOnly(Set.of("fish_caught"));
            HarborId fishId = r.get("fish_caught").flatMap(n -> n.asId(FishId.PREFIX)).orElse(null);
            if (fishId != null) {
                fish = new FishId(fishId);
                ctx.ref("fish", fishId, r.get("fish_caught").orElseThrow().pos());
            }
        }
        HarborId preset = null;
        if (node.has("preset")) {
            YamlNode p = node.get("preset").orElseThrow();
            preset = p.asId(CatchFxPreset.PREFIX).orElse(null);
            if (preset != null) {
                ctx.ref(CatchFxPreset.TYPE.name(), preset, p.pos());
            }
        }
        RigId rig = null;
        if (node.has("rig")) {
            YamlNode r = node.get("rig").orElseThrow();
            HarborId rigId = r.asId(RigId.PREFIX).orElse(null);
            if (rigId != null) {
                rig = new RigId(rigId);
                ctx.ref("rig", rigId, r.pos());
            }
        }
        String model = node.optString("model").orElse(null);
        String color = node.optString("color").orElse(null);
        String particle =
                node.optString("particle").map(s -> s.toLowerCase(Locale.ROOT)).orElse(null);
        String particleColor = node.optString("particle_color").orElse(null);
        if (particleColor != null && !particleColor.matches("#[0-9a-fA-F]{6}")) {
            node.error("particle_color는 #rrggbb 형식이어야 합니다: " + particleColor);
        }
        switch (category) {
            case ROD_SKIN -> require(node, model, "낚싯대 스킨은 model(item_model)이 있어야 합니다");
            case CATCH_FX -> require(node, preset, "포획 연출은 preset이 있어야 합니다");
            case PET, BOAT_SKIN -> require(node, rig, "펫·선박 스킨은 rig가 있어야 합니다");
            case NAME_COLOR -> require(node, color, "이름 색은 color가 있어야 합니다");
            case LINE_FX -> require(node, particle, "찌·낚싯줄 효과는 particle이 있어야 합니다");
            default -> {}
        }
        return new CosmeticDef(
                id,
                node.string("name"),
                node.get("lore").map(YamlNode::asStringOrList).orElse(List.of()),
                category,
                grade,
                price,
                availability,
                season,
                fish,
                model,
                preset,
                rig,
                color,
                node.optString("glyph").orElse(null),
                particle,
                particleColor,
                node.bool("giftable", price > 0));
    }

    private static void require(YamlNode node, @Nullable Object value, String message) {
        if (value == null) {
            node.error(message);
        }
    }
}
