package wiki.creeper.harbor.common.content.type;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.content.ContentType;
import wiki.creeper.harbor.common.content.ParseContext;
import wiki.creeper.harbor.common.id.FishId;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.common.id.ItemId;
import wiki.creeper.harbor.common.id.RegionId;
import wiki.creeper.harbor.common.model.Grade;
import wiki.creeper.harbor.common.yaml.YamlNode;

/**
 * Fish species definition (fish/*.yml). Fields follow '11. Data design' 2.1, plus (proposed, docs
 * 20 17-1 3.3) {@code tags} (bait bonus targets: sea, fresh, carnivore, toothy …) and {@code extra}
 * (special spawn conditions: player flag, server flag, worn equipment tag).
 */
public record FishDef(
        FishId id,
        int no,
        String name,
        List<String> lore,
        Grade grade,
        RegionId region,
        List<String> area,
        List<String> time,
        List<String> weather,
        List<String> moon,
        List<ItemId> bait,
        int jobTierMin,
        @Nullable String unlockFlag,
        double sizeMin,
        double sizeMax,
        int basePrice,
        int weight,
        boolean releaseOnly,
        String model,
        int season,
        List<String> tags,
        List<Extra> extra) {

    public static final int DEFAULT_WEIGHT = 100;

    /** Tag and flag name format (lowercase, digits, underscore; flags may contain '/'). */
    private static final Pattern TAG = Pattern.compile("[a-z0-9_]+");

    private static final Pattern FLAG = Pattern.compile("[a-z0-9_]+(/[a-z0-9_]+)*");

    /**
     * One special spawn condition (proposed, docs 20 17-1 3.3 {@code extra}).
     *
     * @param type condition kind
     * @param value flag name or equipment tag
     */
    public record Extra(Type type, String value) {

        /** Condition kinds. */
        public enum Type {
            /** The player has the flag (e.g. story/ch7_done, boss_clear/void_nebula). */
            PLAYER_FLAG,
            /** The server has the flag. */
            WORLD_FLAG,
            /** The player wears equipment with the tag (gold = any golden armour piece). */
            EQUIPMENT_TAG;

            /** YAML key (player_flag, world_flag, equipment_tag). */
            public String key() {
                return name().toLowerCase(java.util.Locale.ROOT);
            }
        }
    }

    public FishDef {
        lore = List.copyOf(lore);
        area = List.copyOf(area);
        time = List.copyOf(time);
        weather = List.copyOf(weather);
        moon = List.copyOf(moon);
        bait = List.copyOf(bait);
        tags = List.copyOf(tags);
        extra = List.copyOf(extra);
    }

    /** Definition without tags or special conditions (the original '11. Data design' fields). */
    public FishDef(
            FishId id,
            int no,
            String name,
            List<String> lore,
            Grade grade,
            RegionId region,
            List<String> area,
            List<String> time,
            List<String> weather,
            List<String> moon,
            List<ItemId> bait,
            int jobTierMin,
            @Nullable String unlockFlag,
            double sizeMin,
            double sizeMax,
            int basePrice,
            int weight,
            boolean releaseOnly,
            String model,
            int season) {
        this(
                id,
                no,
                name,
                lore,
                grade,
                region,
                area,
                time,
                weather,
                moon,
                bait,
                jobTierMin,
                unlockFlag,
                sizeMin,
                sizeMax,
                basePrice,
                weight,
                releaseOnly,
                model,
                season,
                List.of(),
                List.of());
    }

    private static final Set<String> FIELDS =
            Set.of(
                    "id",
                    "no",
                    "name",
                    "lore",
                    "grade",
                    "region",
                    "area",
                    "time",
                    "weather",
                    "moon",
                    "bait",
                    "job_tier_min",
                    "unlock_flag",
                    "size_cm",
                    "base_price",
                    "weight",
                    "release_only",
                    "model",
                    "season",
                    "tags",
                    "extra");

    public static final ContentType.Entries<FishDef> TYPE =
            new ContentType.Entries<>(
                    "fish", List.of("fish/*.yml"), FishId.PREFIX, FishDef::parse, f -> f.id().id());

    /** Mythic fish cannot be sold, so their base buy price must be 0. */
    public boolean sellable() {
        return basePrice > 0;
    }

    static FishDef parse(YamlNode node, ParseContext ctx) {
        node.allowOnly(FIELDS);
        HarborId rawId =
                node.require("id").flatMap(n -> n.asId(FishId.PREFIX)).orElse(Placeholder.FISH);
        Grade grade = node.enumValue("grade", Grade.class).orElse(Grade.COMMON);

        RegionId region = new RegionId(Placeholder.REGION);
        Optional<YamlNode> regionNode = node.require("region");
        if (regionNode.isPresent()) {
            Optional<HarborId> regionId = regionNode.get().asId(RegionId.PREFIX);
            if (regionId.isPresent()) {
                region = new RegionId(regionId.get());
                ctx.ref("region", regionId.get(), regionNode.get().pos());
            }
        }

        List<ItemId> bait = Refs.items(node.list("bait"), ctx);

        double sizeMin = 0;
        double sizeMax = 0;
        Optional<YamlNode> size = node.require("size_cm");
        if (size.isPresent()) {
            List<YamlNode> pair = size.get().asList();
            if (pair.size() != 2) {
                size.get().error("size_cm은 [최소, 최대] 두 값이어야 합니다");
            } else {
                sizeMin = pair.get(0).asDouble();
                sizeMax = pair.get(1).asDouble();
                if (sizeMin <= 0) {
                    size.get().error("size_cm 최솟값은 0보다 커야 합니다");
                }
                if (sizeMin > sizeMax) {
                    size.get().error("size_cm 최솟값이 최댓값보다 큼");
                }
            }
        }

        int basePrice = node.integer("base_price");
        if (basePrice < 0) {
            node.error("base_price는 0 이상이어야 합니다");
        }
        if (grade == Grade.MYTHIC && basePrice != 0) {
            node.error("신화 어종은 판매 불가라 base_price가 0이어야 합니다");
        }

        int weight = node.integer("weight", DEFAULT_WEIGHT, 1, 1_000_000);

        List<String> tags = new java.util.ArrayList<>();
        for (YamlNode t : node.list("tags")) {
            String tag = t.asString();
            if (!TAG.matcher(tag).matches()) {
                t.error("tags는 소문자·숫자·밑줄이어야 합니다: '" + tag + "'");
            } else if (!tags.contains(tag)) {
                tags.add(tag);
            }
        }
        List<Extra> extra = new java.util.ArrayList<>();
        for (YamlNode e : node.list("extra")) {
            e.allowOnly(Set.of("type", "value"));
            Optional<Extra.Type> type = e.require("type").flatMap(FishDef::extraType);
            String value = e.string("value");
            if (type.isEmpty()) {
                continue;
            }
            boolean ok =
                    type.get() == Extra.Type.EQUIPMENT_TAG
                            ? TAG.matcher(value).matches()
                            : FLAG.matcher(value).matches();
            if (!ok) {
                e.error("extra value 형식이 틀렸습니다: '" + value + "'");
                continue;
            }
            extra.add(new Extra(type.get(), value));
        }

        return new FishDef(
                new FishId(rawId),
                node.integer("no"),
                node.string("name"),
                node.require("lore").map(YamlNode::asStringOrList).orElse(List.of()),
                grade,
                region,
                node.strings("area"),
                node.strings("time"),
                node.strings("weather"),
                node.strings("moon"),
                bait,
                node.integer("job_tier_min", 0, 0, 3),
                node.optString("unlock_flag").orElse(null),
                sizeMin,
                sizeMax,
                basePrice,
                weight,
                node.bool("release_only", false),
                node.string("model"),
                node.integer("season"),
                tags,
                extra);
    }

    private static Optional<Extra.Type> extraType(YamlNode node) {
        String raw = node.asString();
        for (Extra.Type t : Extra.Type.values()) {
            if (t.key().equals(raw)) {
                return Optional.of(t);
            }
        }
        node.error(
                "extra type은 player_flag · world_flag · equipment_tag 중 하나여야 합니다: '" + raw + "'");
        return Optional.empty();
    }
}
