package wiki.creeper.harbor.common.content.type;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.content.ContentType;
import wiki.creeper.harbor.common.content.ParseContext;
import wiki.creeper.harbor.common.id.FishId;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.common.id.ItemId;
import wiki.creeper.harbor.common.id.RegionId;
import wiki.creeper.harbor.common.model.Grade;
import wiki.creeper.harbor.common.yaml.YamlNode;

/** 어종 정의 (fish/*.yml). 필드는 '11. 데이터 설계' 2.1을 따른다. */
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
        int season) {

    public static final int DEFAULT_WEIGHT = 100;

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
                    "season");

    public static final ContentType.Entries<FishDef> TYPE =
            new ContentType.Entries<>(
                    "fish", List.of("fish/*.yml"), FishId.PREFIX, FishDef::parse, f -> f.id().id());

    /** 신화 어종은 판매 불가라 기본 매입가가 0이어야 한다. */
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
                node.integer("season"));
    }
}
