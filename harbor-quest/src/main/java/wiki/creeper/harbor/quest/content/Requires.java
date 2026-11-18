package wiki.creeper.harbor.quest.content;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.content.ParseContext;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.common.id.NpcId;
import wiki.creeper.harbor.common.id.QuestId;
import wiki.creeper.harbor.common.yaml.YamlNode;

/**
 * 시작 조건 ('11. 데이터 설계' 2.6 + 추가 제안 affinity·reputation).
 *
 * @param quests 모두 완료해야 하는 선행 퀘스트
 * @param jobTier 필요한 직업 단계 (1~3)
 * @param week 시즌 주차 잠금 (Season SeasonClock, 0이면 없음)
 * @param reputation 필요한 평판 등급 (0이면 없음)
 */
public record Requires(
        List<QuestId> quests,
        int level,
        int jobTier,
        List<FlagCond> flags,
        int week,
        @Nullable AffinityReq affinity,
        int reputation) {

    public static final Requires NONE = new Requires(List.of(), 0, 1, List.of(), 0, null, 0);

    /** 플래그 조건. YAML: {@code flag}, {@code !flag}(꺼져 있어야 함), {@code server:flag}(서버 플래그). */
    public record FlagCond(String flag, boolean server, boolean negate) {}

    public record AffinityReq(NpcId npc, int level) {}

    private static final Pattern FLAG = Pattern.compile("[a-z0-9_]+(/[a-z0-9_]+)*");

    public Requires {
        quests = List.copyOf(quests);
        flags = List.copyOf(flags);
    }

    static Requires parse(YamlNode n, ParseContext ctx) {
        n.allowOnly(
                Set.of("quests", "level", "job_tier", "flags", "week", "affinity", "reputation"));
        List<QuestId> quests = new ArrayList<>();
        for (YamlNode q : n.list("quests")) {
            HarborId id = q.asId(QuestId.PREFIX).orElse(HarborId.parse("quest/invalid"));
            ctx.ref("quest", id, q.pos());
            quests.add(new QuestId(id));
        }
        List<FlagCond> flags = new ArrayList<>();
        for (YamlNode f : n.list("flags")) {
            String raw = f.asString().strip();
            boolean negate = raw.startsWith("!");
            if (negate) {
                raw = raw.substring(1);
            }
            boolean server = raw.startsWith("server:");
            if (server) {
                raw = raw.substring("server:".length());
            }
            if (!FLAG.matcher(raw).matches()) {
                f.error("플래그 이름 형식이 틀렸습니다: " + raw);
            }
            flags.add(new FlagCond(raw, server, negate));
        }
        AffinityReq affinity =
                n.get("affinity")
                        .map(
                                a -> {
                                    a.allowOnly(Set.of("npc", "level"));
                                    HarborId npc =
                                            a.require("npc")
                                                    .flatMap(x -> x.asId(NpcId.PREFIX))
                                                    .orElse(HarborId.parse("npc/invalid"));
                                    ctx.ref("npc", npc, a.pos());
                                    return new AffinityReq(
                                            new NpcId(npc), a.integer("level", 0, 0, 20));
                                })
                        .orElse(null);
        return new Requires(
                quests,
                n.integer("level", 0, 0, 100),
                n.integer("job_tier", 1, 1, 3),
                flags,
                n.integer("week", 0, 0, 52),
                affinity,
                n.integer("reputation", 0, 0, 20));
    }
}
