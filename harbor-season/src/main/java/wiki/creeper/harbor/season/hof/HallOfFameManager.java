package wiki.creeper.harbor.season.hof;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLSyntaxErrorException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
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
import org.slf4j.Logger;
import wiki.creeper.harbor.common.content.type.FishDef;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.common.redis.Channels;
import wiki.creeper.harbor.common.redis.DistributedLock;
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

/**
 * HallOfFameService implementation (docs 20 11.4 hall of fame).
 *
 * <ul>
 *   <li>top1: locks the (season, category, subject, rank 1) row, compares, and replaces it
 *   <li>first_n · all: each player only once, rank = current count + 1. Retries when the (season,
 *       category, subject, rank) primary key collides
 *   <li>No module submits level_first · codex_first categories, so the ranking aggregation server
 *       computes them from data every 10 minutes and fills in provisional entries
 *   <li>Finalization ({@link #finalizeSeason}) recomputes from archive data during the transition
 *       SNAPSHOT step and grants titles
 * </ul>
 */
public final class HallOfFameManager implements HallOfFameService {

    private static final String DERIVED_LOCK = "harbor:lock:hof-derived";

    private final HarborApi api;
    private final SeasonContentView content;
    private final SeasonClock clock;
    private final Tasks tasks;
    private final Logger log;
    private final Map<String, Instant> lastAnnounce = new ConcurrentHashMap<>();

    private final HofDerivation derivation;

    public HallOfFameManager(
            HarborApi api, SeasonContentView content, SeasonClock clock, Tasks tasks, Logger log) {
        this.api = api;
        this.content = content;
        this.clock = clock;
        this.tasks = tasks;
        this.log = log;
        this.derivation = new HofDerivation(content, log);
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
                .async(c -> HofRows.apply(c, season, cat, sub, false))
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
                                        result.add(HofRows.read(rs));
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

    /** Main thread: event and announcement. */
    private void publish(HallOfFameCategory cat, HofRows.Applied applied) {
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

    /** Subject display name: the species name for fish, otherwise the last segment of the ID. */
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

    // ------------------------------------------------------------------ derived entries
    // (level_first · codex_first)

    /**
     * Called by the ranking aggregation server (virtual thread). Runs at most once per 10 minutes.
     */
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
                List<HofRows.Applied> applied =
                        api.database()
                                .async(
                                        c -> {
                                            List<HofRows.Applied> out = new ArrayList<>();
                                            for (HallOfFameSubmission sub :
                                                    derivation.derive(c, cat, season, null)) {
                                                out.add(HofRows.apply(c, season, cat, sub, false));
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

    // ------------------------------------------------------------------ season finalization

    /**
     * Finalizes the hall of fame for season n (transition SNAPSHOT step, blocking, one
     * transaction). Categories that can be recomputed are rebuilt from archive data; the rest have
     * their provisional entries finalized. Grants the linked titles (INSERT IGNORE, so re-running
     * gives the same result).
     *
     * @return finalized row count per category
     */
    public Map<String, Integer> finalizeSeason(Connection c, int season, String archiveSchema)
            throws SQLException {
        Map<String, Integer> result = new LinkedHashMap<>();
        for (HallOfFameCategory cat : categories()) {
            List<HallOfFameSubmission> recomputed =
                    derivation.recompute(c, cat, season, archiveSchema);
            if (!recomputed.isEmpty()) {
                try (PreparedStatement ps =
                        c.prepareStatement(
                                "DELETE FROM hall_of_fame WHERE season = ? AND category = ?")) {
                    ps.setInt(1, season);
                    ps.setString(2, cat.category());
                    ps.executeUpdate();
                }
                for (HallOfFameSubmission sub : recomputed) {
                    HofRows.apply(c, season, cat, sub, true);
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
}
