package wiki.creeper.harbor.quest.content;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.content.ParseContext;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.common.id.ItemId;
import wiki.creeper.harbor.common.id.NpcId;
import wiki.creeper.harbor.common.id.RegionId;
import wiki.creeper.harbor.common.yaml.YamlNode;

/**
 * 보상 ('11. 데이터 설계' 2.6 + 추가 제안 title·hints·chronicle·job_advance).
 *
 * @param gold 골드. -1이면 게시판 의뢰 기본값(board.yml gold_by_region, 해금한 가장 높은 지역 기준)
 * @param mulbineul 물비늘. -1이면 게시판 기본값 (일일·주간 1개당 1)
 * @param reputation 평판. -1이면 게시판 기본값 (그 밖의 유형은 0)
 * @param title 칭호 ID (Season TitleService)
 * @param chronicle 진실의 조각 ID (chronicle.yml)
 * @param jobAdvance 2 · 3이면 마지막 보고에서 Jobs requestAdvance (0이면 없음)
 */
public record Rewards(
        long gold,
        long exp,
        long mulbineul,
        List<ItemReward> items,
        int reputation,
        List<AffinityReward> affinity,
        List<FlagReward> flags,
        @Nullable String title,
        List<HintReward> hints,
        @Nullable String chronicle,
        int jobAdvance) {

    public record ItemReward(ItemId item, int amount) {}

    public record AffinityReward(NpcId npc, int value) {}

    /** scope: perm · season. server면 서버 플래그 (seasonal은 scope가 season일 때). */
    public record FlagReward(String flag, boolean server, boolean seasonal) {}

    /** region이 없으면 해금한 가장 높은 지역 (Collection grantRandom). */
    public record HintReward(@Nullable RegionId region, int count) {}

    public static final Rewards NONE =
            new Rewards(0, 0, 0, List.of(), 0, List.of(), List.of(), null, List.of(), null, 0);

    /** 게시판 의뢰에 rewards가 없을 때: 골드·물비늘·평판 모두 board.yml 기본값. */
    public static final Rewards BOARD_DEFAULT =
            new Rewards(-1, 0, -1, List.of(), -1, List.of(), List.of(), null, List.of(), null, 0);

    private static final Pattern FLAG = Pattern.compile("[a-z0-9_]+(/[a-z0-9_]+)*");

    public Rewards {
        items = List.copyOf(items);
        affinity = List.copyOf(affinity);
        flags = List.copyOf(flags);
        hints = List.copyOf(hints);
    }

    static Rewards parse(YamlNode n, ParseContext ctx, boolean board) {
        n.allowOnly(
                Set.of(
                        "gold",
                        "exp",
                        "mulbineul",
                        "items",
                        "reputation",
                        "affinity",
                        "flags",
                        "title",
                        "hints",
                        "chronicle",
                        "job_advance"));
        long fallback = board ? -1 : 0;
        List<ItemReward> items = new ArrayList<>();
        for (YamlNode i : n.list("items")) {
            i.allowOnly(Set.of("item", "amount"));
            HarborId id =
                    i.require("item")
                            .flatMap(x -> x.asId(ItemId.PREFIX))
                            .orElse(HarborId.parse("item/invalid"));
            ctx.ref("item", id, i.pos());
            items.add(new ItemReward(new ItemId(id), i.integer("amount", 1, 1, 6400)));
        }
        List<AffinityReward> affinity = new ArrayList<>();
        for (YamlNode a : n.list("affinity")) {
            a.allowOnly(Set.of("npc", "value"));
            HarborId id =
                    a.require("npc")
                            .flatMap(x -> x.asId(NpcId.PREFIX))
                            .orElse(HarborId.parse("npc/invalid"));
            ctx.ref("npc", id, a.pos());
            affinity.add(new AffinityReward(new NpcId(id), a.integer("value", 0, -1000, 10_000)));
        }
        List<FlagReward> flags = new ArrayList<>();
        for (YamlNode f : n.list("flags")) {
            if (f.isMap()) {
                f.allowOnly(Set.of("flag", "scope", "server"));
                String flag = f.string("flag");
                checkFlag(f, flag);
                String scope = f.string("scope", "perm");
                if (!scope.equals("perm") && !scope.equals("season")) {
                    f.error("scope는 perm 또는 season이어야 합니다: " + scope);
                }
                flags.add(new FlagReward(flag, f.bool("server", false), scope.equals("season")));
            } else {
                String flag = f.asString().strip();
                checkFlag(f, flag);
                flags.add(new FlagReward(flag, false, false));
            }
        }
        String title =
                n.optString("title")
                        .map(
                                t -> {
                                    HarborId id = HarborId.tryParse(t).orElse(null);
                                    if (id == null) {
                                        n.error("칭호 ID 형식이 틀렸습니다: " + t);
                                        return t;
                                    }
                                    HarborId full =
                                            id.hasPrefix("title/")
                                                    ? id
                                                    : new HarborId(
                                                            id.namespace(), "title/" + id.path());
                                    ctx.ref("title", full, n.pos());
                                    return full.toString();
                                })
                        .orElse(null);
        List<HintReward> hints = new ArrayList<>();
        n.get("hints")
                .ifPresent(
                        h -> {
                            List<YamlNode> list = h.isList() ? h.asList() : List.of(h);
                            for (YamlNode one : list) {
                                one.allowOnly(Set.of("region", "count"));
                                RegionId region =
                                        one.get("region")
                                                .flatMap(r -> r.asId(RegionId.PREFIX))
                                                .map(
                                                        id -> {
                                                            ctx.ref("region", id, one.pos());
                                                            return new RegionId(id);
                                                        })
                                                .orElse(null);
                                hints.add(new HintReward(region, one.integer("count", 1, 1, 20)));
                            }
                        });
        int jobAdvance = n.integer("job_advance", 0, 0, 3);
        if (jobAdvance == 1) {
            n.error("job_advance는 2 또는 3이어야 합니다");
        }
        return new Rewards(
                n.longValue("gold", fallback),
                n.longValue("exp", 0),
                n.longValue("mulbineul", fallback),
                items,
                n.integer("reputation", (int) fallback),
                affinity,
                flags,
                title,
                hints,
                n.optString("chronicle").orElse(null),
                jobAdvance);
    }

    private static void checkFlag(YamlNode n, String flag) {
        if (!FLAG.matcher(flag).matches()) {
            n.error("플래그 이름 형식이 틀렸습니다: " + flag);
        }
    }
}
