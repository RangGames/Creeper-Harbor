package wiki.creeper.harbor.season.hof;

import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.SQLSyntaxErrorException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import wiki.creeper.harbor.common.content.type.FishDef;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.common.model.Grade;
import wiki.creeper.harbor.common.redis.Channels;
import wiki.creeper.harbor.common.redis.DistributedLock;
import wiki.creeper.harbor.common.util.Json;
import wiki.creeper.harbor.core.api.HarborApi;
import wiki.creeper.harbor.core.task.Tasks;
import wiki.creeper.harbor.season.SeasonContentView;
import wiki.creeper.harbor.season.api.HallOfFameEntry;
import wiki.creeper.harbor.season.api.HallOfFameService;
import wiki.creeper.harbor.season.api.HallOfFameSubmission;
import wiki.creeper.harbor.season.api.SeasonClock;
import wiki.creeper.harbor.season.api.SeasonPhase;
import wiki.creeper.harbor.season.api.TitleId;
import wiki.creeper.harbor.season.api.event.HallOfFameUpdatedEvent;
import wiki.creeper.harbor.season.content.HallOfFameCategory;
import wiki.creeper.harbor.season.data.TitleStore;
import wiki.creeper.harbor.season.ranking.BossMembers;
import wiki.creeper.harbor.season.ranking.SchemaName;

/**
 * HallOfFameService 구현 (docs 20 11.4 명예의 전당).
 *
 * <ul>
 *   <li>top1: (시즌, 분류, 대상, 순위 1) 행을 잠그고 비교해 바꾼다
 *   <li>first_n · all: 같은 플레이어는 한 번만, 순위 = 지금 인원 + 1. (시즌, 분류, 대상, 순위) 기본 키가 겹치면 다시 시도한다
 *   <li>level_first · codex_first 분류는 제출하는 모듈이 없으므로 랭킹 집계 서버가 10분마다 데이터에서 계산해 잠정 기록을 채운다
 *   <li>확정({@link #finalizeSeason})은 전환 SNAPSHOT 단계에서 아카이브 데이터로 다시 계산하고 칭호를 준다
 * </ul>
 */
public final class HallOfFameManager implements HallOfFameService {

    private static final Type DETAIL = new TypeToken<Map<String, String>>() {}.getType();
    private static final String DERIVED_LOCK = "harbor:lock:hof-derived";
    private static final int MAX_RETRY = 3;

    private final HarborApi api;
    private final SeasonContentView content;
    private final SeasonClock clock;
    private final Tasks tasks;
    private final Logger log;
    private final Map<String, Instant> lastAnnounce = new ConcurrentHashMap<>();

    private record Applied(
            Outcome outcome, @Nullable HallOfFameEntry entry, @Nullable HallOfFameEntry previous) {}

    public HallOfFameManager(
            HarborApi api, SeasonContentView content, SeasonClock clock, Tasks tasks, Logger log) {
        this.api = api;
        this.content = content;
        this.clock = clock;
        this.tasks = tasks;
        this.log = log;
    }

    // ------------------------------------------------------------------ HallOfFameService

    @Override
    public CompletableFuture<Outcome> submit(HallOfFameSubmission sub) {
        Optional<HallOfFameCategory> category = category(sub.category());
        if (category.isEmpty() || clock.state() == SeasonPhase.TRANSITIONING) {
            return CompletableFuture.completedFuture(Outcome.REJECTED);
        }
        int season = clock.current().value();
        HallOfFameCategory cat = category.get();
        return api.database()
                .async(c -> apply(c, season, cat, sub, false))
                .handleAsync(
                        (applied, error) -> {
                            if (error != null) {
                                log.error("명예의 전당 제출 실패: {}", sub, error);
                                return Outcome.FAILED;
                            }
                            publish(cat, applied);
                            return applied.outcome();
                        },
                        tasks.main());
    }

    @Override
    public List<HallOfFameCategory> categories() {
        return content.hallOfFame().categories();
    }

    @Override
    public Optional<HallOfFameCategory> category(String category) {
        return content.hallOfFame().get(category);
    }

    @Override
    public CompletableFuture<List<HallOfFameEntry>> entries(int season, String category) {
        return api.database()
                .async(
                        c -> {
                            List<HallOfFameEntry> result = new ArrayList<>();
                            try (PreparedStatement ps =
                                    c.prepareStatement(
                                            "SELECT season, category, subject, `rank`, uuid, name_at_time, value, detail, recorded_at, `final`"
                                                    + " FROM hall_of_fame WHERE season = ? AND category = ? ORDER BY subject, `rank`")) {
                                ps.setInt(1, season);
                                ps.setString(2, category);
                                try (ResultSet rs = ps.executeQuery()) {
                                    while (rs.next()) {
                                        result.add(read(rs));
                                    }
                                }
                            }
                            return List.copyOf(result);
                        })
                .thenApplyAsync(r -> r, tasks.main());
    }

    @Override
    public CompletableFuture<List<Integer>> seasons() {
        int current = clock.current().value();
        return api.database()
                .async(
                        c -> {
                            List<Integer> result = new ArrayList<>();
                            try (PreparedStatement ps =
                                            c.prepareStatement(
                                                    "SELECT DISTINCT season FROM hall_of_fame ORDER BY season DESC");
                                    ResultSet rs = ps.executeQuery()) {
                                while (rs.next()) {
                                    result.add(rs.getInt(1));
                                }
                            }
                            if (!result.contains(current)) {
                                result.addFirst(current);
                            }
                            return List.copyOf(result);
                        })
                .thenApplyAsync(r -> r, tasks.main());
    }

    // ------------------------------------------------------------------ 잠정 기록

    private Applied apply(
            Connection c,
            int season,
            HallOfFameCategory cat,
            HallOfFameSubmission sub,
            boolean finalRow)
            throws SQLException {
        String subject = subjectKey(sub.subject());
        for (int attempt = 0; ; attempt++) {
            try {
                return switch (cat.rule()) {
                    case TOP1 -> applyTop1(c, season, cat, subject, sub, finalRow);
                    case FIRST_N, ALL -> applyFirstN(c, season, cat, subject, sub, finalRow);
                };
            } catch (SQLIntegrityConstraintViolationException e) {
                if (attempt >= MAX_RETRY) {
                    throw e;
                }
            }
        }
    }

    private Applied applyTop1(
            Connection c,
            int season,
            HallOfFameCategory cat,
            String subject,
            HallOfFameSubmission sub,
            boolean finalRow)
            throws SQLException {
        Optional<HallOfFameEntry> old = rowForUpdate(c, season, cat.category(), subject, 1);
        if (old.isPresent()) {
            HallOfFameEntry o = old.get();
            if (!HofMath.better(
                    cat.ascending(), sub.value(), sub.achievedAt(), o.value(), o.recordedAt())) {
                return new Applied(Outcome.NOT_RANKED, null, null);
            }
            try (PreparedStatement ps =
                    c.prepareStatement(
                            "UPDATE hall_of_fame SET uuid = ?, name_at_time = ?, value = ?, detail = ?, recorded_at = ?, `final` = ?"
                                    + " WHERE season = ? AND category = ? AND subject = ? AND `rank` = 1")) {
                ps.setString(1, sub.player().toString());
                ps.setString(2, clipName(sub.name()));
                ps.setDouble(3, sub.value());
                ps.setString(4, Json.GSON.toJson(sub.detail()));
                ps.setTimestamp(5, Timestamp.from(sub.achievedAt()));
                ps.setBoolean(6, finalRow);
                ps.setInt(7, season);
                ps.setString(8, cat.category());
                ps.setString(9, subject);
                ps.executeUpdate();
            }
            return new Applied(
                    Outcome.NEW_LEADER, entry(season, cat, subject, 1, sub, finalRow), o);
        }
        insert(c, season, cat.category(), subject, 1, sub, finalRow);
        return new Applied(Outcome.NEW_LEADER, entry(season, cat, subject, 1, sub, finalRow), null);
    }

    private Applied applyFirstN(
            Connection c,
            int season,
            HallOfFameCategory cat,
            String subject,
            HallOfFameSubmission sub,
            boolean finalRow)
            throws SQLException {
        int count;
        try (PreparedStatement ps =
                c.prepareStatement(
                        "SELECT uuid FROM hall_of_fame WHERE season = ? AND category = ? AND subject = ? FOR UPDATE")) {
            ps.setInt(1, season);
            ps.setString(2, cat.category());
            ps.setString(3, subject);
            count = 0;
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    count++;
                    if (sub.player().toString().equalsIgnoreCase(rs.getString(1))) {
                        return new Applied(Outcome.NOT_RANKED, null, null);
                    }
                }
            }
        }
        if (cat.count() > 0 && count >= cat.count()) {
            return new Applied(Outcome.NOT_RANKED, null, null);
        }
        int rank = count + 1;
        insert(c, season, cat.category(), subject, rank, sub, finalRow);
        return new Applied(
                rank == 1 ? Outcome.NEW_LEADER : Outcome.RANKED,
                entry(season, cat, subject, rank, sub, finalRow),
                null);
    }

    private static Optional<HallOfFameEntry> rowForUpdate(
            Connection c, int season, String category, String subject, int rank)
            throws SQLException {
        try (PreparedStatement ps =
                c.prepareStatement(
                        "SELECT season, category, subject, `rank`, uuid, name_at_time, value, detail, recorded_at, `final`"
                                + " FROM hall_of_fame WHERE season = ? AND category = ? AND subject = ? AND `rank` = ? FOR UPDATE")) {
            ps.setInt(1, season);
            ps.setString(2, category);
            ps.setString(3, subject);
            ps.setInt(4, rank);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(read(rs)) : Optional.empty();
            }
        }
    }

    private static void insert(
            Connection c,
            int season,
            String category,
            String subject,
            int rank,
            HallOfFameSubmission sub,
            boolean finalRow)
            throws SQLException {
        try (PreparedStatement ps =
                c.prepareStatement(
                        "INSERT INTO hall_of_fame (season, category, subject, `rank`, uuid, name_at_time, value, detail, recorded_at, `final`)"
                                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            ps.setInt(1, season);
            ps.setString(2, category);
            ps.setString(3, subject);
            ps.setInt(4, rank);
            ps.setString(5, sub.player().toString());
            ps.setString(6, clipName(sub.name()));
            ps.setDouble(7, sub.value());
            ps.setString(8, Json.GSON.toJson(sub.detail()));
            ps.setTimestamp(9, Timestamp.from(sub.achievedAt()));
            ps.setBoolean(10, finalRow);
            ps.executeUpdate();
        }
    }

    private static HallOfFameEntry entry(
            int season,
            HallOfFameCategory cat,
            String subject,
            int rank,
            HallOfFameSubmission sub,
            boolean finalRow) {
        return new HallOfFameEntry(
                season,
                cat.category(),
                subject,
                rank,
                sub.player(),
                sub.name(),
                sub.value(),
                sub.detail(),
                sub.achievedAt(),
                finalRow);
    }

    private static HallOfFameEntry read(ResultSet rs) throws SQLException {
        Map<String, String> detail = Map.of();
        String raw = rs.getString(8);
        if (raw != null && !raw.isBlank()) {
            try {
                Map<String, String> parsed = Json.GSON.fromJson(raw, DETAIL);
                if (parsed != null) {
                    detail = parsed;
                }
            } catch (RuntimeException e) {
                detail = Map.of();
            }
        }
        return new HallOfFameEntry(
                rs.getInt(1),
                rs.getString(2),
                rs.getString(3),
                rs.getInt(4),
                UUID.fromString(rs.getString(5)),
                rs.getString(6),
                rs.getDouble(7),
                detail,
                rs.getTimestamp(9).toInstant(),
                rs.getBoolean(10));
    }

    /** 메인 스레드: 이벤트와 공지. */
    private void publish(HallOfFameCategory cat, Applied applied) {
        HallOfFameEntry entry = applied.entry();
        if (entry == null
                || (applied.outcome() != Outcome.NEW_LEADER
                        && applied.outcome() != Outcome.RANKED)) {
            return;
        }
        Bukkit.getPluginManager().callEvent(new HallOfFameUpdatedEvent(entry, applied.previous()));
        if (!content.balance().hallOfFameAnnounce()) {
            return;
        }
        String key = cat.category() + "|" + entry.subject();
        Instant now = Instant.now();
        Instant last = lastAnnounce.get(key);
        int throttle = content.balance().hallOfFameAnnounceThrottleSeconds();
        if (last != null && Duration.between(last, now).toSeconds() < throttle) {
            return;
        }
        lastAnnounce.put(key, now);
        Component msg =
                api.messages()
                        .get(
                                "season.hof.announce",
                                Placeholder.parsed("category", cat.name()),
                                Placeholder.unparsed("subject", subjectName(entry.subject())),
                                Placeholder.unparsed("player", entry.nameAtTime()),
                                Placeholder.unparsed("rank", String.valueOf(entry.rank())),
                                Placeholder.unparsed(
                                        "value", HofMath.format(entry.value(), cat.unit())));
        api.bus()
                .publish(
                        Channels.ANNOUNCEMENTS,
                        new Channels.Announcement(MiniMessage.miniMessage().serialize(msg)));
    }

    /** 대상 표시 이름: 어종이면 어종 이름, 그 밖은 ID 마지막 마디. */
    public String subjectName(String subject) {
        if (subject.equals(HallOfFameEntry.NO_SUBJECT)) {
            return "";
        }
        Optional<HarborId> id = HarborId.tryParse(subject);
        if (id.isEmpty()) {
            return subject;
        }
        if (id.get().hasPrefix("fish/")) {
            for (FishDef fish : content.snapshot().all(FishDef.TYPE).values()) {
                if (fish.id().id().equals(id.get())) {
                    return fish.name();
                }
            }
        }
        return id.get().name();
    }

    private static String subjectKey(@Nullable HarborId subject) {
        return subject == null ? HallOfFameEntry.NO_SUBJECT : subject.toString();
    }

    private static String clipName(String name) {
        return name.length() > 64 ? name.substring(0, 64) : name;
    }

    // ------------------------------------------------------------------ 파생 기록 (level_first ·
    // codex_first)

    /** 랭킹 집계 서버가 부른다 (가상 스레드). 10분에 한 번만 한다. */
    public void refreshDerived() {
        Optional<DistributedLock.Handle> lock;
        try {
            lock = api.locks().tryAcquire(DERIVED_LOCK, Duration.ofMinutes(10));
        } catch (RuntimeException e) {
            return;
        }
        if (lock.isEmpty()) {
            return;
        }
        int season = clock.current().value();
        if (clock.state() == SeasonPhase.TRANSITIONING) {
            return;
        }
        for (HallOfFameCategory cat : categories()) {
            if (cat.source() != HallOfFameCategory.Source.LEVEL_FIRST
                    && cat.source() != HallOfFameCategory.Source.CODEX_FIRST) {
                continue;
            }
            try {
                List<Applied> applied =
                        api.database()
                                .async(
                                        c -> {
                                            List<Applied> out = new ArrayList<>();
                                            for (HallOfFameSubmission sub :
                                                    derive(c, cat, season, null)) {
                                                out.add(apply(c, season, cat, sub, false));
                                            }
                                            return out;
                                        })
                                .join();
                tasks.main().execute(() -> applied.forEach(a -> publish(cat, a)));
            } catch (RuntimeException e) {
                Throwable cause = e.getCause() == null ? e : e.getCause();
                if (!(cause instanceof SQLSyntaxErrorException)) {
                    log.warn(
                            "명예의 전당 {} 계산 실패: {}",
                            cat.category(),
                            String.valueOf(cause.getMessage()));
                }
            }
        }
    }

    /** level_first · codex_first 분류의 선착순 명단을 데이터에서 만든다 (달성 시각 순). */
    List<HallOfFameSubmission> derive(
            Connection c, HallOfFameCategory cat, int season, @Nullable String schema)
            throws SQLException {
        return switch (cat.source()) {
            case LEVEL_FIRST -> deriveLevel(c, cat, season, schema);
            case CODEX_FIRST -> deriveCodex(c, cat, season, schema);
            default -> List.of();
        };
    }

    /**
     * 레벨 선착순. player_season(인덱스 season·level)으로 후보를 찾고, Jobs가 레벨업 때 남기는 원장 LEVEL_UP 기록(참조 ID {@code
     * lvl:<시즌>:<uuid>:<레벨>}, 유니크 인덱스)의 시각으로 줄을 세운다. Season은 Jobs 코드에 의존하지 않고 이 데이터 형식에만 기댄다.
     */
    private List<HallOfFameSubmission> deriveLevel(
            Connection c, HallOfFameCategory cat, int season, @Nullable String schema)
            throws SQLException {
        record Candidate(UUID uuid, String name, Instant at) {}
        List<Candidate> list = new ArrayList<>();
        try (PreparedStatement ps =
                c.prepareStatement(
                        "SELECT s.uuid, p.name, l.created_at FROM "
                                + SchemaName.prefix(schema)
                                + "player_season s"
                                + " LEFT JOIN players p ON p.uuid = s.uuid"
                                + " JOIN ledger l ON l.uuid = s.uuid AND l.currency = 'MULBINEUL' AND l.reason = 'LEVEL_UP'"
                                + " AND l.ref_id = CONCAT('lvl:', ?, ':', s.uuid, ':', ?)"
                                + " WHERE s.season = ? AND s.level >= ?"
                                + " ORDER BY l.created_at ASC LIMIT ?")) {
            ps.setInt(1, season);
            ps.setInt(2, cat.level());
            ps.setInt(3, season);
            ps.setInt(4, cat.level());
            ps.setInt(5, cat.count() > 0 ? cat.count() : 1000);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    UUID uuid = UUID.fromString(rs.getString(1));
                    String name = rs.getString(2);
                    list.add(
                            new Candidate(
                                    uuid,
                                    name == null ? uuid.toString().substring(0, 8) : name,
                                    rs.getTimestamp(3).toInstant()));
                }
            }
        }
        List<HallOfFameSubmission> result = new ArrayList<>();
        for (Candidate cand : list) {
            result.add(
                    new HallOfFameSubmission(
                            cat.category(),
                            null,
                            cand.uuid(),
                            cand.name(),
                            cat.level(),
                            Map.of("level", String.valueOf(cat.level())),
                            cand.at()));
        }
        return result;
    }

    /** 시즌 도감 100% 선착순 (신화 제외 전 어종, 마지막 첫 포획 시각 순). */
    private List<HallOfFameSubmission> deriveCodex(
            Connection c, HallOfFameCategory cat, int season, @Nullable String schema)
            throws SQLException {
        List<String> fish =
                content.snapshot().all(FishDef.TYPE).values().stream()
                        .filter(f -> f.grade() != Grade.MYTHIC)
                        .map(f -> f.id().toString())
                        .toList();
        if (fish.isEmpty()) {
            return List.of();
        }
        String marks = String.join(",", Collections.nCopies(fish.size(), "?"));
        List<HallOfFameSubmission> result = new ArrayList<>();
        try (PreparedStatement ps =
                c.prepareStatement(
                        "SELECT c.uuid, MAX(p.name), COUNT(*) AS n, MAX(c.first_caught) AS done_at FROM "
                                + SchemaName.prefix(schema)
                                + "codex_season c LEFT JOIN players p ON p.uuid = c.uuid"
                                + " WHERE c.season = ? AND c.fish_id IN ("
                                + marks
                                + ") GROUP BY c.uuid HAVING n >= ? ORDER BY done_at ASC LIMIT ?")) {
            int i = 1;
            ps.setInt(i++, season);
            for (String f : fish) {
                ps.setString(i++, f);
            }
            ps.setInt(i++, fish.size());
            ps.setInt(i, cat.count() > 0 ? cat.count() : 1000);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    UUID uuid = UUID.fromString(rs.getString(1));
                    String name = rs.getString(2);
                    result.add(
                            new HallOfFameSubmission(
                                    cat.category(),
                                    null,
                                    uuid,
                                    name == null ? uuid.toString().substring(0, 8) : name,
                                    rs.getInt(3),
                                    Map.of("species", String.valueOf(rs.getInt(3))),
                                    rs.getTimestamp(4).toInstant()));
                }
            }
        }
        return result;
    }

    // ------------------------------------------------------------------ 시즌 확정

    /**
     * 시즌 n 명예의 전당을 확정한다 (전환 SNAPSHOT 단계, 블로킹, 한 트랜잭션). 다시 계산할 수 있는 분류는 아카이브 데이터로 다시 만들고, 나머지는 잠정
     * 기록을 확정한다. 연결 칭호를 지급한다 (INSERT IGNORE라 다시 실행해도 같다).
     *
     * @return 분류별 확정 행 수
     */
    public Map<String, Integer> finalizeSeason(Connection c, int season, String archiveSchema)
            throws SQLException {
        Map<String, Integer> result = new LinkedHashMap<>();
        for (HallOfFameCategory cat : categories()) {
            List<HallOfFameSubmission> recomputed = recompute(c, cat, season, archiveSchema);
            if (!recomputed.isEmpty()) {
                try (PreparedStatement ps =
                        c.prepareStatement(
                                "DELETE FROM hall_of_fame WHERE season = ? AND category = ?")) {
                    ps.setInt(1, season);
                    ps.setString(2, cat.category());
                    ps.executeUpdate();
                }
                for (HallOfFameSubmission sub : recomputed) {
                    apply(c, season, cat, sub, true);
                }
            }
            try (PreparedStatement ps =
                    c.prepareStatement(
                            "UPDATE hall_of_fame SET `final` = 1 WHERE season = ? AND category = ?")) {
                ps.setInt(1, season);
                ps.setString(2, cat.category());
                ps.executeUpdate();
            }
            int granted = 0;
            int rows = 0;
            try (PreparedStatement ps =
                    c.prepareStatement(
                            "SELECT subject, uuid FROM hall_of_fame WHERE season = ? AND category = ?")) {
                ps.setInt(1, season);
                ps.setString(2, cat.category());
                List<String[]> list = new ArrayList<>();
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        list.add(new String[] {rs.getString(1), rs.getString(2)});
                    }
                }
                for (String[] row : list) {
                    rows++;
                    String subject = row[0];
                    String subjectName =
                            subject.equals(HallOfFameEntry.NO_SUBJECT)
                                    ? ""
                                    : HarborId.tryParse(subject)
                                            .map(HarborId::name)
                                            .orElse(subject);
                    TitleId title = cat.titleFor(subjectName);
                    if (title != null
                            && content.titles().containsKey(title.id())
                            && TitleStore.insert(
                                    c,
                                    UUID.fromString(row[1]),
                                    title,
                                    season,
                                    HofMath.titleRef(season, cat.category(), subject))) {
                        granted++;
                    }
                }
            }
            result.put(cat.category(), rows);
            if (granted > 0) {
                log.info("명예의 전당 {} 칭호 {}명 지급", cat.category(), granted);
            }
        }
        return result;
    }

    /** 확정용 재계산. 원천 테이블이 없으면 빈 목록 (잠정 기록을 그대로 확정). */
    private List<HallOfFameSubmission> recompute(
            Connection c, HallOfFameCategory cat, int season, String archiveSchema)
            throws SQLException {
        try {
            return switch (cat.source()) {
                case RECORDS -> topRecords(c, cat, season, archiveSchema);
                case BOSS_RECORDS -> topBoss(c, cat, season);
                case LEVEL_FIRST, CODEX_FIRST -> derive(c, cat, season, archiveSchema);
                case SUBMITTED -> List.of();
            };
        } catch (SQLSyntaxErrorException e) {
            log.warn("명예의 전당 {} 재계산 원천이 없어 잠정 기록을 확정합니다: {}", cat.category(), e.getMessage());
            return List.of();
        }
    }

    /** 어종별 전체 리그 1위 (아카이브 records). */
    private List<HallOfFameSubmission> topRecords(
            Connection c, HallOfFameCategory cat, int season, String archiveSchema)
            throws SQLException {
        Map<String, HallOfFameSubmission> best = new HashMap<>();
        try (PreparedStatement ps =
                c.prepareStatement(
                        "SELECT r.fish_id, r.uuid, p.name, r.size, r.caught_at FROM "
                                + SchemaName.prefix(archiveSchema)
                                + "records r LEFT JOIN players p ON p.uuid = r.uuid"
                                + " WHERE r.season = ? AND r.league = 'all'"
                                + " ORDER BY r.fish_id, r.size DESC, r.caught_at ASC")) {
            ps.setInt(1, season);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String fish = rs.getString(1);
                    if (best.containsKey(fish)) {
                        continue;
                    }
                    Optional<HarborId> id = HarborId.tryParse(fish);
                    if (id.isEmpty()) {
                        continue;
                    }
                    UUID uuid = UUID.fromString(rs.getString(2));
                    String name = rs.getString(3);
                    best.put(
                            fish,
                            new HallOfFameSubmission(
                                    cat.category(),
                                    id.get(),
                                    uuid,
                                    name == null ? uuid.toString().substring(0, 8) : name,
                                    rs.getDouble(4),
                                    Map.of(),
                                    rs.getTimestamp(5).toInstant()));
                }
            }
        }
        return List.copyOf(best.values());
    }

    /** 보스별 최단 처치 (boss_records는 이월 테이블이라 지금 DB의 시즌 n 행). */
    private List<HallOfFameSubmission> topBoss(Connection c, HallOfFameCategory cat, int season)
            throws SQLException {
        record Raw(HarborId boss, List<UUID> members, long duration, Instant at) {}
        Map<String, Raw> best = new LinkedHashMap<>();
        try (PreparedStatement ps =
                c.prepareStatement(
                        "SELECT boss_id, members, duration_ms, cleared_at FROM boss_records"
                                + " WHERE season = ? ORDER BY boss_id, duration_ms ASC, cleared_at ASC")) {
            ps.setInt(1, season);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String boss = rs.getString(1);
                    if (best.containsKey(boss)) {
                        continue;
                    }
                    Optional<HarborId> id = HarborId.tryParse(boss);
                    List<UUID> members = BossMembers.parse(rs.getString(2));
                    if (id.isEmpty() || members.isEmpty()) {
                        continue;
                    }
                    best.put(
                            boss,
                            new Raw(
                                    id.get(),
                                    members,
                                    rs.getLong(3),
                                    rs.getTimestamp(4).toInstant()));
                }
            }
        }
        Map<UUID, String> names =
                BossMembers.names(
                        c, best.values().stream().map(r -> r.members().getFirst()).toList());
        List<HallOfFameSubmission> result = new ArrayList<>();
        for (Raw raw : best.values()) {
            UUID leader = raw.members().getFirst();
            result.add(
                    new HallOfFameSubmission(
                            cat.category(),
                            raw.boss(),
                            leader,
                            names.getOrDefault(leader, leader.toString().substring(0, 8)),
                            raw.duration(),
                            Map.of("members", String.valueOf(raw.members().size())),
                            raw.at()));
        }
        return result;
    }
}
