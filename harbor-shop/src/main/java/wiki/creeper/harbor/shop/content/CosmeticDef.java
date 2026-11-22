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
 * Cosmetic definition (cosmetics.yml, '11. Data design' 2.8, docs 20 12.3). Purchases with pearls
 * are not items but account ownership records (cosmetics_owned), so they cannot move through trades
 * or auctions. Hence tradeable must be absent or false ('6. BM plan' ch. 1, economy separation).
 *
 * @param price pearl price (0 means not sold in the shop: pass, chest, sponsorship or free grant
 *     only)
 * @param season season of a season-limited item (0 means permanent). With availability = season,
 *     sold only during that season
 * @param requiresFish purchase condition: must have caught this species personally (legendary
 *     species commemorative skin, Collection codex_total)
 * @param model item model (item_model of a rod skin, shop icon)
 * @param preset catch effect preset (catch_fx, shop/catch_fx.yml)
 * @param rig pet / boat decoration rig (Core RigService)
 * @param color name color (name_color, MiniMessage tag e.g. {@code <gradient:#55ffff:#5555ff>})
 * @param glyph chat icon / title frame glyph (resource pack font character; text is shown if empty)
 * @param particle bobber / fishing line effect particle (line_fx, vanilla particle key)
 * @param particleColor particle color (#rrggbb, dust types only)
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

    /** Category ('11. Data design' 2.8). The equip slot equals the category (one per category). */
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

    /** How it is obtained. Only permanent, rotation and season are listed in the shop. */
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

        /** Whether this route is sold in the pearl shop. */
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

    /**
     * Whether it can be sold in the pearl shop now (season-limited only in that season). Rotation
     * is checked separately by {@link RotationPlan}.
     */
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
        // Economy separation ('6. BM plan' ch. 1): cosmetics cannot be traded
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
