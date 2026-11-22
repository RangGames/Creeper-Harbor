package wiki.creeper.harbor.economy.content;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.content.ContentType;
import wiki.creeper.harbor.common.content.ParseContext;
import wiki.creeper.harbor.common.id.FishId;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.common.id.ItemId;
import wiki.creeper.harbor.common.id.QuestId;
import wiki.creeper.harbor.common.id.RegionId;
import wiki.creeper.harbor.common.model.Grade;
import wiki.creeper.harbor.common.model.Keyed;
import wiki.creeper.harbor.common.yaml.YamlNode;

/**
 * Crafting recipe (recipes.yml, "11. Data design" 2.3 + 2.9, docs 20 7.3, 7.4). Uses no server API,
 * so harbor-tools checks with the same code.
 *
 * <pre>{@code
 * - id: recipe/coral_carbon_rod
 *   result: item/rod/coral_carbon
 *   amount: 1
 *   station: workshop
 *   category: gear
 *   gold: 6000
 *   ingredients:
 *     - {item: item/rod/harbor_standard, count: 1}
 *     - {vanilla: minecraft:iron_ingot, count: 4}          # (proposed) vanilla material
 *     - {match: {grade_min: rare}, count: 1}                # 1 fish of rare or higher
 *     - {match: {fish_any: [fish/a, fish/b]}, count: 1}     # either one
 *   unlock: {job_tier: 2, level: 20, visited: region/coral_isles, quest: quest/main/ch4,
 *            feature: feature_legend_bait_recipe, flags: [boss_clear/swamp_catfish]}
 *   weekly_limit: 1
 * }</pre>
 *
 * @param gold crafting cost (CRAFT_COST, unaffected by material saving)
 * @param weeklyLimit weekly crafting limit (0 for none). Season weekly_counter key {@code
 *     craft:<recipe name>}
 */
public record RecipeDef(
        HarborId id,
        ItemId result,
        int amount,
        Station station,
        Category category,
        long gold,
        List<Ingredient> ingredients,
        Unlock unlock,
        int weeklyLimit) {

    /** Crafting place (fishers' guild workbench, cooking furnace, shipyard). */
    public enum Station implements Keyed {
        WORKSHOP,
        COOKING,
        SHIPYARD;

        @Override
        public String key() {
            return Keyed.keyOf(this);
        }
    }

    /**
     * Category ("11. Data design" 2.9). gear gets "Artisan's Hand" material saving; bait gets "Bait
     * Artisan" extra results.
     */
    public enum Category implements Keyed {
        GEAR,
        BAIT,
        FOOD,
        TRAP,
        BOAT;

        @Override
        public String key() {
            return Keyed.keyOf(this);
        }
    }

    /** One ingredient line. */
    public sealed interface Ingredient permits Item, Vanilla, FishMatch {
        int count();
    }

    /** Harbor item. */
    public record Item(ItemId item, int count) implements Ingredient {}

    /** (proposed) Vanilla item without a Harbor marker (iron ingot, gunpowder …). */
    public record Vanilla(String key, int count) implements Ingredient {}

    /**
     * Fish matching conditions ("11. Data design" 2.9 match). All conditions must be met.
     *
     * @param gradeMin this grade or higher (null means any)
     * @param fishAny one of these (empty means any)
     * @param region species of this region (null means any)
     */
    public record FishMatch(
            @Nullable Grade gradeMin, List<FishId> fishAny, @Nullable RegionId region, int count)
            implements Ingredient {}

    /**
     * Unlock conditions. All must be met.
     *
     * @param visited first visit to a region. Player flag {@code visited/<region name>} (a flag set
     *     by World, proposed)
     * @param quest quest completed. Player flag {@code quest/<quest path>} (a flag set by Quest,
     *     proposed)
     * @param feature modifier key raised by a skill node (1 or more means owned. The Jobs API has
     *     no skill ownership query, so it is checked this way, proposed)
     * @param flags other player flags (boss kills, etc.)
     */
    public record Unlock(
            int jobTier,
            int level,
            @Nullable RegionId visited,
            @Nullable QuestId quest,
            @Nullable String feature,
            List<String> flags) {

        public static final Unlock NONE = new Unlock(0, 0, null, null, null, List.of());

        /** Player flag names to check (including visited and quest). */
        public List<String> requiredFlags() {
            List<String> all = new ArrayList<>(flags);
            if (visited != null) {
                all.add(visitedFlag(visited));
            }
            if (quest != null) {
                all.add(questFlag(quest));
            }
            return all;
        }
    }

    public static String visitedFlag(RegionId region) {
        return "visited/" + region.id().name();
    }

    public static String questFlag(QuestId quest) {
        return quest.id().path();
    }

    public static final String PREFIX = "recipe/";

    /**
     * Vanilla item key format. Actual existence is checked on the server (same rule as ItemDef).
     */
    public static final Pattern RESOURCE_KEY = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");

    private static final Pattern FLAG = Pattern.compile("[a-z0-9_]+(/[a-z0-9_]+)*");

    public static final ContentType.Entries<RecipeDef> TYPE =
            new ContentType.Entries<>(
                    "recipe", List.of("recipes.yml"), PREFIX, RecipeDef::parse, RecipeDef::id);

    /** Name used in the weekly limit key (after recipe/). */
    public String limitName() {
        return id.path().substring(PREFIX.length()).replace('/', '_');
    }

    static RecipeDef parse(YamlNode node, ParseContext ctx) {
        node.allowOnly(
                Set.of(
                        "id",
                        "result",
                        "amount",
                        "station",
                        "category",
                        "gold",
                        "ingredients",
                        "unlock",
                        "weekly_limit"));
        HarborId id =
                node.require("id")
                        .flatMap(n -> n.asId(PREFIX))
                        .orElse(HarborId.parse("recipe/invalid"));
        HarborId result =
                node.require("result")
                        .flatMap(n -> n.asId(ItemId.PREFIX))
                        .orElse(HarborId.parse("item/invalid"));
        node.get("result").ifPresent(n -> ctx.ref("item", result, n.pos()));
        Station station =
                node.require("station")
                        .flatMap(n -> n.asEnum(Station.class))
                        .orElse(Station.WORKSHOP);
        Category category =
                node.has("category")
                        ? node.enumValue("category", Category.class).orElse(Category.GEAR)
                        : Category.GEAR;
        long gold = node.longValue("gold", 0);
        if (gold < 0) {
            node.error("gold는 0 이상이어야 합니다");
        }
        List<Ingredient> ingredients = new ArrayList<>();
        List<YamlNode> rows = node.list("ingredients");
        if (rows.isEmpty()) {
            node.error("재료(ingredients)가 하나 이상 있어야 합니다");
        }
        for (YamlNode row : rows) {
            Ingredient ingredient = ingredient(row, ctx);
            if (ingredient != null) {
                ingredients.add(ingredient);
            }
        }
        Unlock unlock =
                node.has("unlock") ? unlock(node.get("unlock").orElseThrow(), ctx) : Unlock.NONE;
        return new RecipeDef(
                id,
                new ItemId(result),
                node.integer("amount", 1, 1, 64),
                station,
                category,
                Math.max(0, gold),
                List.copyOf(ingredients),
                unlock,
                node.integer("weekly_limit", 0, 0, 1000));
    }

    private static @Nullable Ingredient ingredient(YamlNode row, ParseContext ctx) {
        row.allowOnly(Set.of("item", "vanilla", "match", "count"));
        int count = row.integer("count", 1, 1, 64 * 36);
        int kinds =
                (row.has("item") ? 1 : 0)
                        + (row.has("vanilla") ? 1 : 0)
                        + (row.has("match") ? 1 : 0);
        if (kinds != 1) {
            row.error("재료는 item · vanilla · match 중 하나만 써야 합니다");
            return null;
        }
        if (row.has("item")) {
            YamlNode n = row.get("item").orElseThrow();
            HarborId item = n.asId(ItemId.PREFIX).orElse(null);
            if (item == null) {
                return null;
            }
            ctx.ref("item", item, n.pos());
            return new Item(new ItemId(item), count);
        }
        if (row.has("vanilla")) {
            YamlNode n = row.get("vanilla").orElseThrow();
            String key = n.asString();
            if (!RESOURCE_KEY.matcher(key).matches()) {
                n.error("바닐라 아이템 키 형식이 틀렸습니다 (예: minecraft:iron_ingot): '" + key + "'");
                return null;
            }
            if (key.startsWith("harbor:")) {
                n.error("Harbor 아이템은 item으로 씁니다: '" + key + "'");
                return null;
            }
            return new Vanilla(key, count);
        }
        YamlNode m = row.get("match").orElseThrow();
        m.allowOnly(Set.of("grade_min", "fish_any", "region"));
        Grade grade =
                m.has("grade_min") ? m.enumValue("grade_min", Grade.class).orElse(null) : null;
        List<FishId> any = new ArrayList<>();
        for (YamlNode f : m.list("fish_any")) {
            HarborId fish = f.asId(FishId.PREFIX).orElse(null);
            if (fish != null) {
                ctx.ref("fish", fish, f.pos());
                any.add(new FishId(fish));
            }
        }
        RegionId region = null;
        if (m.has("region")) {
            YamlNode r = m.get("region").orElseThrow();
            HarborId rid = r.asId(RegionId.PREFIX).orElse(null);
            if (rid != null) {
                ctx.ref("region", rid, r.pos());
                region = new RegionId(rid);
            }
        }
        if (grade == null && any.isEmpty() && region == null) {
            m.error("match에는 grade_min · fish_any · region 중 하나 이상이 있어야 합니다");
        }
        return new FishMatch(grade, List.copyOf(any), region, count);
    }

    private static Unlock unlock(YamlNode u, ParseContext ctx) {
        u.allowOnly(Set.of("job_tier", "level", "visited", "quest", "feature", "flags"));
        RegionId visited = null;
        if (u.has("visited")) {
            YamlNode n = u.get("visited").orElseThrow();
            HarborId rid = n.asId(RegionId.PREFIX).orElse(null);
            if (rid != null) {
                ctx.ref("region", rid, n.pos());
                visited = new RegionId(rid);
            }
        }
        QuestId quest = null;
        if (u.has("quest")) {
            YamlNode n = u.get("quest").orElseThrow();
            HarborId qid = n.asId(QuestId.PREFIX).orElse(null);
            if (qid != null) {
                ctx.ref("quest", qid, n.pos());
                quest = new QuestId(qid);
            }
        }
        String feature = u.optString("feature").orElse(null);
        if (feature != null && !feature.matches("[a-z0-9_]+")) {
            u.error("feature는 보정자 키(소문자·숫자·밑줄)여야 합니다: '" + feature + "'");
            feature = null;
        }
        List<String> flags = new ArrayList<>();
        for (String flag : u.strings("flags")) {
            if (FLAG.matcher(flag).matches()) {
                flags.add(flag);
            } else {
                u.error("플래그 이름 형식이 틀렸습니다: '" + flag + "'");
            }
        }
        return new Unlock(
                u.integer("job_tier", 0, 0, 3),
                u.integer("level", 0, 0, 100),
                visited,
                quest,
                feature,
                List.copyOf(flags));
    }
}
