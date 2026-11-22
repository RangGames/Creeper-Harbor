package wiki.creeper.harbor.season.content;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.content.ContentType;
import wiki.creeper.harbor.common.content.ParseContext;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.common.yaml.YamlNode;
import wiki.creeper.harbor.season.api.League;

/** season/rankings.yml: list of ranking boards. */
public record RankingBoards(List<RankingBoardDef> boards) {

    public static final ContentType.Single<RankingBoards> TYPE =
            new ContentType.Single<>(
                    "ranking_boards", "season/rankings.yml", false, RankingBoards::parse);

    /** Built-in sources. */
    public static final String RECORDS = "records";

    public static final String LEVEL = "level";
    public static final String COLLECTION_SEASON = "collection_season";
    public static final String BOSS_RECORDS = "boss_records";

    private static final Pattern ID = Pattern.compile("[a-z0-9_]{1,48}");

    public RankingBoards {
        boards = List.copyOf(boards);
    }

    public Optional<RankingBoardDef> get(String id) {
        return boards.stream().filter(b -> b.id().equals(id)).findFirst();
    }

    static RankingBoards parse(YamlNode root, ParseContext ctx) {
        root.allowOnly(Set.of("boards"));
        List<RankingBoardDef> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (YamlNode node : root.list("boards")) {
            node.allowOnly(
                    Set.of(
                            "id", "name", "source", "subject", "leagues", "size", "refresh",
                            "icon"));
            String id = node.string("id");
            if (!ID.matcher(id).matches()) {
                node.error("보드 ID는 소문자·숫자·밑줄 1~48자여야 합니다: " + id);
            }
            if (!seen.add(id)) {
                node.error("보드 " + id + "이(가) 중복됩니다");
            }
            String source = node.string("source");
            if (!ID.matcher(source).matches()) {
                node.error("source 형식이 틀렸습니다: " + source);
            }
            @Nullable HarborId subject = null;
            Optional<YamlNode> subjectNode = node.get("subject");
            if (subjectNode.isPresent()) {
                subject = subjectNode.get().asId().orElse(null);
            }
            if (subject != null) {
                switch (source) {
                    case RECORDS -> ctx.ref("fish", subject, subjectNode.get().pos());
                    case BOSS_RECORDS -> ctx.ref("boss", subject, subjectNode.get().pos());
                    default -> {}
                }
            } else if (source.equals(RECORDS) || source.equals(BOSS_RECORDS)) {
                node.error("source: " + source + "에는 subject(어종·보스 ID)가 필요합니다");
            }
            List<League> leagues = new ArrayList<>();
            for (YamlNode l : node.list("leagues")) {
                l.asEnum(League.class).ifPresent(leagues::add);
            }
            if (leagues.isEmpty()) {
                leagues.add(League.ALL);
            }
            if ((source.equals(COLLECTION_SEASON) || source.equals(BOSS_RECORDS))
                    && leagues.stream().anyMatch(l -> l != League.ALL)) {
                node.error("source: " + source + "는 리그 all만 지원합니다");
            }
            result.add(
                    new RankingBoardDef(
                            id,
                            node.string("name", id),
                            source,
                            subject,
                            leagues,
                            node.integer("size", 100, 1, 500),
                            node.integer("refresh", 10, 1, 1440),
                            node.optString("icon").orElse(null)));
        }
        return new RankingBoards(result);
    }
}
