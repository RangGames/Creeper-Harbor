package wiki.creeper.harbor.season.content;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.StringReader;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiFunction;
import org.junit.jupiter.api.Test;
import wiki.creeper.harbor.common.content.ContentLoader;
import wiki.creeper.harbor.common.content.ContentSnapshot;
import wiki.creeper.harbor.common.content.ParseContext;
import wiki.creeper.harbor.common.content.type.CoreContent;
import wiki.creeper.harbor.common.model.Currency;
import wiki.creeper.harbor.common.util.Result;
import wiki.creeper.harbor.common.util.ValidationErrors;
import wiki.creeper.harbor.common.yaml.YamlNode;
import wiki.creeper.harbor.season.api.League;
import wiki.creeper.harbor.season.api.TitleId;

class SeasonContentTest {

    private static final Path CONTENT = Path.of("..", "content");

    private static <T> T parse(
            String yaml, BiFunction<YamlNode, ParseContext, T> parser, ValidationErrors errors) {
        YamlNode node = YamlNode.parse("test.yml", new StringReader(yaml), errors).orElseThrow();
        return parser.apply(node, new ParseContext());
    }

    @Test
    void sampleContentLoadsWithCoreLoader() {
        ContentLoader loader = new ContentLoader();
        CoreContent.register(loader);
        SeasonContent.TYPES.forEach(loader::register);
        Result<ContentSnapshot, ValidationErrors> result = loader.load(CONTENT);
        if (result
                instanceof Result.Err<ContentSnapshot, ValidationErrors>(ValidationErrors errors)) {
            fail(errors.format());
        }
        ContentSnapshot snap = result.orThrow();
        ResetPlan plan = snap.single(ResetPlan.TYPE);
        // Main quest carry-over rule from the docs
        ResetPlan.TableRule quest = plan.rule("quest_progress").orElseThrow();
        assertEquals(ResetPlan.Policy.ARCHIVE_RESET, quest.policy());
        assertEquals("quest_id LIKE 'harbor:quest/main/%'", quest.keepWhere());
        assertEquals(ResetPlan.Scope.FULL, plan.rule("auction_listing").orElseThrow().scope());
        assertEquals(Set.of(Currency.GOLD, Currency.MULBINEUL), plan.currencies());
        assertEquals(List.of(30, 10, 5, 1), plan.transition().noticeMinutes());
        assertEquals(50_000, plan.transition().batchRows());
        assertFalse(snap.all(TitleDef.TYPE).isEmpty());
        assertTrue(snap.has(TitleDef.TYPE.name(), TitleId.of("codex_25").id()));
        RankingBoards boards = snap.single(RankingBoards.TYPE);
        assertEquals(
                List.of(League.ALL, League.TIER1, League.TIER2),
                boards.get("level").orElseThrow().leagues());
        HallOfFameRules hof = snap.single(HallOfFameRules.TYPE);
        assertTrue(hof.get("fastest_boss").orElseThrow().ascending());
        SeasonDefs defs = snap.single(SeasonDefs.TYPE);
        assertEquals(12, defs.get(2).orElseThrow().weeks());
    }

    @Test
    void resetPlanRejectsUnsafeKeepWhereAndDuplicates() {
        ValidationErrors errors = new ValidationErrors();
        parse(
                """
                tables:
                  - {table: quest_progress, policy: archive_reset, keep_where: "1=1; DROP TABLE players"}
                  - {table: quest_progress, policy: keep}
                  - {table: records, policy: archive_keep, keep_where: "uuid IS NULL"}
                  - {table: Bad-Name, policy: keep}
                currencies: [pearl]
                """,
                ResetPlan::parse,
                errors);
        String text = errors.format();
        assertTrue(text.contains("keep_where에는"), text);
        assertTrue(text.contains("두 번"), text);
        assertTrue(text.contains("archive_reset에만"), text);
        assertTrue(text.contains("소문자"), text);
        assertTrue(text.contains("진주"), text);
    }

    @Test
    void resetPlanDefaults() {
        ValidationErrors errors = new ValidationErrors();
        ResetPlan plan =
                parse(
                        "tables:\n  - {table: player_season, policy: archive_reset}\n",
                        ResetPlan::parse,
                        errors);
        assertTrue(errors.isEmpty(), errors.format());
        ResetPlan.TableRule rule = plan.tables().getFirst();
        assertEquals("season", rule.column());
        assertEquals(ResetPlan.Scope.SEASON_COLUMN, rule.scope());
        assertTrue(plan.gate().resetVanillaState());
        assertEquals(60, plan.transition().maxMinutes());
    }

    @Test
    void seasonDefsParseDateAndTime() {
        ValidationErrors errors = new ValidationErrors();
        SeasonDefs defs =
                parse(
                        """
                        seasons:
                          - {season: 1, starts_at: 2027-05-03, weeks: 8}
                          - {season: 2, starts_at: "2027-09-06T12:30", resourcepack: {url: "https://x.invalid/p.zip", sha1: "ABCDEFABCDEFABCDEFABCDEFABCDEFABCDEFABCD"}}
                        """,
                        SeasonDefs::parse,
                        errors);
        assertTrue(errors.isEmpty(), errors.format());
        assertEquals(LocalDate.of(2027, 5, 3), defs.get(1).orElseThrow().startDate());
        assertEquals(Optional.empty(), Optional.ofNullable(defs.get(1).orElseThrow().startTime()));
        assertEquals(LocalTime.of(12, 30), defs.get(2).orElseThrow().startTime());
        assertEquals(
                "abcdefabcdefabcdefabcdefabcdefabcdefabcd", defs.get(2).orElseThrow().packSha1());
        assertEquals(12, defs.get(2).orElseThrow().weeks());
    }

    @Test
    void seasonDefsRejectOutOfOrderAndBadSha() {
        ValidationErrors errors = new ValidationErrors();
        parse(
                """
                seasons:
                  - {season: 2, starts_at: 2027-09-06}
                  - {season: 3, starts_at: 2027-01-01, resourcepack: {url: "ftp://x", sha1: "zz"}}
                """,
                SeasonDefs::parse,
                errors);
        String text = errors.format();
        assertTrue(text.contains("앞 시즌보다"), text);
        assertTrue(text.contains("sha1"), text);
        assertTrue(text.contains("http"), text);
    }

    @Test
    void rankingBoardsNeedSubject() {
        ValidationErrors errors = new ValidationErrors();
        parse(
                """
                boards:
                  - {id: fish, source: records}
                  - {id: codex, source: collection_season, leagues: [tier1]}
                """,
                RankingBoards::parse,
                errors);
        String text = errors.format();
        assertTrue(text.contains("subject"), text);
        assertTrue(text.contains("리그 all만"), text);
    }

    @Test
    void hallOfFameTitleTemplate() {
        ValidationErrors errors = new ValidationErrors();
        HallOfFameRules rules =
                parse(
                        """
                        categories:
                          - {category: fish_kings, source: records, rule: top1, title: "title/hof_fish/<subject>"}
                          - {category: lv, source: level_first, rule: top1}
                        """,
                        HallOfFameRules::parse,
                        errors);
        assertTrue(errors.format().contains("first_n"), errors.format());
        HallOfFameCategory cat = rules.get("fish_kings").orElseThrow();
        assertEquals(TitleId.of("hof_fish/harbor_mackerel"), cat.titleFor("harbor_mackerel"));
        assertEquals(1, cat.count());
    }
}
