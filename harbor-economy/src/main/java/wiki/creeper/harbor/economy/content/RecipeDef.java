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
 * 제작 레시피 (recipes.yml, '11. 데이터 설계' 2.3 + 2.9, docs 20 7.3·7.4). 서버 API를 쓰지 않아 harbor-tools도 같은 코드로
 * 검사한다.
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
 *     - {vanilla: minecraft:iron_ingot, count: 4}          # (추가 제안) 바닐라 재료
 *     - {match: {grade_min: rare}, count: 1}                # 희귀 이상 어종 1
 *     - {match: {fish_any: [fish/a, fish/b]}, count: 1}     # 둘 중 하나
 *   unlock: {job_tier: 2, level: 20, visited: region/coral_isles, quest: quest/main/ch4,
 *            feature: feature_legend_bait_recipe, flags: [boss_clear/swamp_catfish]}
 *   weekly_limit: 1
 * }</pre>
 *
 * @param gold 제작 비용 (CRAFT_COST, 재료 절약과 관계없이 그대로)
 * @param weeklyLimit 주간 제작 제한 (0이면 없음). Season weekly_counter 키 {@code craft:<레시피 이름>}
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

    /** 제작 장소 (어부 길드 작업대, 요리 화로, 조선소). */
    public enum Station implements Keyed {
        WORKSHOP,
        COOKING,
        SHIPYARD;

        @Override
        public String key() {
            return Keyed.keyOf(this);
        }
    }

    /** 분류 ('11. 데이터 설계' 2.9). gear는 '장인의 손' 재료 절약, bait는 '미끼 장인' 결과 추가 대상. */
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

    /** 재료 한 줄. */
    public sealed interface Ingredient permits Item, Vanilla, FishMatch {
        int count();
    }

    /** Harbor 아이템. */
    public record Item(ItemId item, int count) implements Ingredient {}

    /** (추가 제안) Harbor 표시가 없는 바닐라 아이템 (철 주괴, 화약 …). */
    public record Vanilla(String key, int count) implements Ingredient {}

    /**
     * 조건 어종 ('11. 데이터 설계' 2.9 match). 모든 조건을 만족해야 한다.
     *
     * @param gradeMin 이 등급 이상 (null이면 무관)
     * @param fishAny 이 중 하나 (비면 무관)
     * @param region 이 지역 어종 (null이면 무관)
     */
    public record FishMatch(
            @Nullable Grade gradeMin, List<FishId> fishAny, @Nullable RegionId region, int count)
            implements Ingredient {}

    /**
     * 해금 조건. 모두 만족해야 한다.
     *
     * @param visited 지역 첫 방문. 플레이어 플래그 {@code visited/<지역 이름>} (World가 세우는 플래그, 추가 제안)
     * @param quest 퀘스트 완료. 플레이어 플래그 {@code quest/<퀘스트 경로>} (Quest가 세우는 플래그, 추가 제안)
     * @param feature 스킬 노드가 올리는 보정자 키 (1 이상이면 보유. Jobs API에 스킬 보유 조회가 없어 이렇게 본다, 추가 제안)
     * @param flags 그 밖의 플레이어 플래그 (보스 처치 등)
     */
    public record Unlock(
            int jobTier,
            int level,
            @Nullable RegionId visited,
            @Nullable QuestId quest,
            @Nullable String feature,
            List<String> flags) {

        public static final Unlock NONE = new Unlock(0, 0, null, null, null, List.of());

        /** 확인할 플레이어 플래그 이름 목록 (visited·quest 포함). */
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

    /** 바닐라 아이템 키 형식. 실제 존재 여부는 서버에서 검사한다 (ItemDef와 같은 규칙). */
    public static final Pattern RESOURCE_KEY = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");

    private static final Pattern FLAG = Pattern.compile("[a-z0-9_]+(/[a-z0-9_]+)*");

    public static final ContentType.Entries<RecipeDef> TYPE =
            new ContentType.Entries<>(
                    "recipe", List.of("recipes.yml"), PREFIX, RecipeDef::parse, RecipeDef::id);

    /** 주간 제한 키에 쓰는 이름 (recipe/ 뒤). */
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
