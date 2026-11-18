package wiki.creeper.harbor.shop.content;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.content.ContentType;
import wiki.creeper.harbor.common.content.ParseContext;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.common.model.Keyed;
import wiki.creeper.harbor.common.yaml.YamlNode;

/**
 * 시즌 패스 「항해 일지」 (shop/pass_s&lt;n&gt;.yml, docs 20 12.4, '6. BM 기획' 3.1). 50단계,
 * 무료·프리미엄(990)·프리미엄+(1,990, 즉시 10단계). 경험치는 평소 플레이로만 오르고, 진주 단계 구매는 보상 코스메틱만 앞당긴다.
 *
 * @param expSources 출처 키 → 경험치 (catch.&lt;등급&gt;, quest_daily, quest_weekly, chart_percent,
 *     boss_clear …). Shop 밖 모듈은 {@code SeasonPassService.registerSource}로 같은 키를 쓴다
 */
public record PassDef(
        HarborId id,
        int season,
        String name,
        int levels,
        int expPerLevel,
        Prices prices,
        int premiumPlusLevels,
        Map<String, Integer> expSources,
        List<Reward> rewards) {

    /**
     * 가격 (진주).
     *
     * @param upgrade 프리미엄 → 프리미엄+ 추가 가격 (제안값: 차액)
     * @param level 단계 하나 구매 (제안값 100)
     */
    public record Prices(int premium, int premiumPlus, int upgrade, int level) {}

    /** 보상 트랙. premium_plus는 프리미엄+ 전용 (시즌 한정 칭호 테두리 등). */
    public enum Track implements Keyed {
        FREE,
        PREMIUM,
        PREMIUM_PLUS;

        @Override
        public String key() {
            return Keyed.keyOf(this);
        }

        /** 이 프리미엄 단계(0 무료 · 1 프리미엄 · 2 프리미엄+)에서 받을 수 있는 트랙인지. */
        public boolean openAt(int premium) {
            return switch (this) {
                case FREE -> true;
                case PREMIUM -> premium >= 1;
                case PREMIUM_PLUS -> premium >= 2;
            };
        }
    }

    /**
     * 단계 보상 하나. cosmetic · pearls · title 중 하나만.
     *
     * @param pearls 무료 묶음 진주 (PASS_REWARD)
     */
    public record Reward(
            int level,
            Track track,
            int slot,
            @Nullable HarborId cosmetic,
            int pearls,
            @Nullable String title) {

        /** season_pass_claim의 track 열·참조 ID에 쓰는 이름. 같은 단계·트랙에 보상이 둘 이상이면 순번이 붙는다. */
        public String claimKey() {
            return slot == 0 ? track.key() : track.key() + "_" + slot;
        }
    }

    public static final String PREFIX = "pass/";

    public static final ContentType.Entries<PassDef> TYPE =
            new ContentType.Entries<>(
                    "season_pass",
                    List.of("shop/pass_s*.yml"),
                    PREFIX,
                    PassDef::parse,
                    PassDef::id);

    /** 경험치 출처의 양. 없으면 0. */
    public int exp(String source) {
        return expSources.getOrDefault(source, 0);
    }

    /** 이 단계의 보상. */
    public List<Reward> rewardsAt(int level) {
        return rewards.stream().filter(r -> r.level() == level).toList();
    }

    static PassDef parse(YamlNode node, ParseContext ctx) {
        node.allowOnly(
                Set.of(
                        "id",
                        "season",
                        "name",
                        "levels",
                        "exp_per_level",
                        "prices",
                        "premium_plus_levels",
                        "exp_sources",
                        "rewards"));
        HarborId id =
                node.require("id")
                        .flatMap(n -> n.asId(PREFIX))
                        .orElse(HarborId.parse("pass/invalid"));
        int levels = node.integer("levels", 50, 1, 200);
        int expPerLevel = node.integer("exp_per_level", 1000, 1, 1_000_000);
        YamlNode p = node.require("prices").orElse(node);
        p.allowOnly(Set.of("premium", "premium_plus", "upgrade", "level"));
        int premium = p.integer("premium", 990, 1, 100_000);
        int premiumPlus = p.integer("premium_plus", 1990, 1, 100_000);
        Prices prices =
                new Prices(
                        premium,
                        premiumPlus,
                        p.integer("upgrade", Math.max(1, premiumPlus - premium), 1, 100_000),
                        p.integer("level", 100, 1, 100_000));
        Map<String, Integer> sources = new LinkedHashMap<>();
        node.get("exp_sources").ifPresent(s -> flatten("", s, sources));
        List<Reward> rewards = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        Map<String, Integer> slots = new HashMap<>();
        for (YamlNode r : node.list("rewards")) {
            r.allowOnly(Set.of("level", "track", "cosmetic", "pearls", "title"));
            int level = r.integer("level", 1, 1, levels);
            Track track = r.enumValue("track", Track.class).orElse(Track.FREE);
            HarborId cosmetic = null;
            if (r.has("cosmetic")) {
                YamlNode c = r.get("cosmetic").orElseThrow();
                cosmetic = c.asId(CosmeticDef.PREFIX).orElse(null);
                if (cosmetic != null) {
                    ctx.ref(CosmeticDef.TYPE.name(), cosmetic, c.pos());
                }
            }
            int pearls = r.integer("pearls", 0, 0, 100_000);
            String title = r.optString("title").orElse(null);
            if (title != null) {
                TitleRefs.ref(ctx, title, r);
            }
            int kinds = (cosmetic != null ? 1 : 0) + (pearls > 0 ? 1 : 0) + (title != null ? 1 : 0);
            if (kinds != 1) {
                r.error("보상은 cosmetic · pearls · title 중 하나만 적습니다");
            }
            if (!seen.add(
                    level + ":" + track.key() + ":" + cosmetic + ":" + pearls + ":" + title)) {
                r.error("같은 단계·트랙에 같은 보상이 두 번 있습니다");
            }
            int slot = slots.merge(level + ":" + track.key(), 1, Integer::sum) - 1;
            rewards.add(new Reward(level, track, slot, cosmetic, pearls, title));
        }
        rewards.sort(Comparator.comparingInt(Reward::level).thenComparing(Reward::track));
        return new PassDef(
                id,
                node.integer("season", 1, 1, 1000),
                node.string("name"),
                levels,
                expPerLevel,
                prices,
                node.integer("premium_plus_levels", 10, 0, levels),
                Map.copyOf(sources),
                List.copyOf(rewards));
    }

    private static void flatten(String prefix, YamlNode node, Map<String, Integer> out) {
        if (node.isMap()) {
            node.entries()
                    .forEach((k, v) -> flatten(prefix.isEmpty() ? k : prefix + "." + k, v, out));
        } else {
            int value = node.asInt();
            if (value < 0) {
                node.error("경험치는 0 이상이어야 합니다");
            }
            out.put(prefix, value);
        }
    }
}
