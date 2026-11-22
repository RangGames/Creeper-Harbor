package wiki.creeper.harbor.collection.service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.slf4j.Logger;
import wiki.creeper.harbor.collection.api.RecordEntry;
import wiki.creeper.harbor.collection.api.WeeklyRecord;
import wiki.creeper.harbor.collection.api.event.RecordBrokenEvent;
import wiki.creeper.harbor.collection.data.CollectionRepository;
import wiki.creeper.harbor.collection.service.CollectionManager.RecordUpdated;
import wiki.creeper.harbor.common.content.type.FishDef;
import wiki.creeper.harbor.common.id.FishId;
import wiki.creeper.harbor.common.model.SeasonId;
import wiki.creeper.harbor.common.redis.Channels;
import wiki.creeper.harbor.core.api.HarborApi;
import wiki.creeper.harbor.core.api.HarborPlayer;
import wiki.creeper.harbor.core.task.Tasks;

/**
 * Server biggest-fish records (docs 20 4.4): the top 10 cache per species and league, immediate
 * upserts on a virtual thread, cross-server sync and this week's new records.
 */
final class RecordBoard {

    private static final int TOP = 10;
    private static final Duration ANNOUNCE_THROTTLE = Duration.ofSeconds(10);
    private static final int WEEKLY_MAX = 30;

    private final HarborApi api;
    private final Tasks tasks;
    private final Logger log;
    private final SeasonLink seasonLink;
    private final Map<String, List<RecordEntry>> top = new ConcurrentHashMap<>();
    private final List<WeeklyRecord> weekly = new ArrayList<>();
    private final Map<FishId, Instant> lastAnnounce = new HashMap<>();

    RecordBoard(HarborApi api, Tasks tasks, Logger log, SeasonLink seasonLink) {
        this.api = api;
        this.tasks = tasks;
        this.log = log;
        this.seasonLink = seasonLink;
    }

    void start() {
        SeasonId season = api.infra().season();
        var _ =
                api.database()
                        .async(c -> CollectionRepository.topRecords(c, season, TOP))
                        .thenAcceptAsync(
                                rows -> {
                                    rows.forEach(
                                            r ->
                                                    put(
                                                            r.fish().toString(),
                                                            r.league(),
                                                            new RecordEntry(
                                                                    r.player(),
                                                                    r.name(),
                                                                    r.size(),
                                                                    r.caughtAt())));
                                    log.info("최대어 기록 캐시 {}행", rows.size());
                                },
                                tasks.main());
        api.bus()
                .subscribe(
                        CollectionManager.RECORDS,
                        envelope -> {
                            if (!envelope.server().equals(api.infra().serverId())) {
                                RecordUpdated u = envelope.payload();
                                tasks.main()
                                        .execute(
                                                () -> {
                                                    RecordEntry entry =
                                                            new RecordEntry(
                                                                    u.player(),
                                                                    u.name(),
                                                                    u.size(),
                                                                    u.caughtAt());
                                                    if (put(u.fish(), u.league(), entry)
                                                            && u.league().equals("all")) {
                                                        weekly(u.fish(), entry);
                                                    }
                                                });
                            }
                        });
    }

    void serverRecord(HarborPlayer hp, FishDef fish, double size, Instant at) {
        UUID uuid = hp.uuid();
        SeasonId season = hp.season();
        String name = hp.name();
        for (String league : CollectionManager.leagues(hp.jobTier())) {
            Optional<RecordEntry> mine = mine(fish.id().toString(), league, uuid);
            if (mine.isPresent() && mine.get().size() >= size) {
                continue;
            }
            var _ =
                    api.database()
                            .asyncVoid(
                                    c ->
                                            CollectionRepository.upsertRecord(
                                                    c, fish.id(), season, league, uuid, size, at))
                            .thenRunAsync(
                                    () ->
                                            afterRecord(
                                                    fish,
                                                    league,
                                                    new RecordEntry(uuid, name, size, at)),
                                    tasks.main())
                            .exceptionally(
                                    e -> {
                                        log.error("기록 저장 실패: {} {} {}", fish.id(), league, uuid, e);
                                        return null;
                                    });
        }
    }

    private void afterRecord(FishDef fish, String league, RecordEntry entry) {
        String key = fish.id().toString();
        List<RecordEntry> before = top.getOrDefault(key + "|" + league, List.of());
        RecordEntry previousLeader = before.isEmpty() ? null : before.getFirst();
        var _ = put(key, league, entry);
        api.bus()
                .publish(
                        CollectionManager.RECORDS,
                        new RecordUpdated(
                                key,
                                league,
                                entry.player(),
                                entry.name(),
                                entry.size(),
                                entry.caughtAt()));
        List<RecordEntry> after = top.getOrDefault(key + "|" + league, List.of());
        boolean newLeader =
                !after.isEmpty()
                        && after.getFirst().player().equals(entry.player())
                        && (previousLeader == null || entry.size() > previousLeader.size());
        if (!newLeader) {
            return;
        }
        double previous = previousLeader == null ? 0 : previousLeader.size();
        if (league.equals("all")) {
            weekly(key, entry);
            // Provisional Hall of Fame record (Season finalizes it at season close)
            seasonLink.submitBiggestFish(
                    fish.id().id(), entry.player(), entry.name(), entry.size(), entry.caughtAt());
        }
        Bukkit.getPluginManager()
                .callEvent(
                        new RecordBrokenEvent(
                                entry.player(),
                                fish.id(),
                                league,
                                RecordBrokenEvent.Scope.SERVER,
                                entry.size(),
                                previous));
        Instant now = Instant.now();
        Instant last = lastAnnounce.get(fish.id());
        if (league.equals("all")
                && previousLeader != null
                && (last == null || Duration.between(last, now).compareTo(ANNOUNCE_THROTTLE) > 0)) {
            lastAnnounce.put(fish.id(), now);
            Component msg =
                    api.messages()
                            .get(
                                    "collection.announce.server_record",
                                    Placeholder.unparsed("player", entry.name()),
                                    Placeholder.unparsed("name", fish.name()),
                                    Placeholder.unparsed(
                                            "size", CollectionManager.fmt(entry.size())));
            api.bus()
                    .publish(
                            Channels.ANNOUNCEMENTS,
                            new Channels.Announcement(MiniMessage.miniMessage().serialize(msg)));
        }
    }

    private Optional<RecordEntry> mine(String fish, String league, UUID uuid) {
        return top.getOrDefault(fish + "|" + league, List.of()).stream()
                .filter(e -> e.player().equals(uuid))
                .findFirst();
    }

    /**
     * Puts an entry in the cache: one (largest) value per player, sorted by size descending with
     * the earlier catch first, top 10. Returns true if this entry changed the leader.
     */
    private boolean put(String fish, String league, RecordEntry entry) {
        List<RecordEntry> before = top.getOrDefault(fish + "|" + league, List.of());
        RecordEntry previousLeader = before.isEmpty() ? null : before.getFirst();
        top.compute(
                fish + "|" + league,
                (k, list) -> {
                    List<RecordEntry> next = new ArrayList<>(list == null ? List.of() : list);
                    Optional<RecordEntry> existing =
                            next.stream()
                                    .filter(e -> e.player().equals(entry.player()))
                                    .findFirst();
                    if (existing.isPresent()) {
                        if (existing.get().size() >= entry.size()) {
                            return list;
                        }
                        next.remove(existing.get());
                    }
                    next.add(entry);
                    next.sort(
                            Comparator.comparingDouble(RecordEntry::size)
                                    .reversed()
                                    .thenComparing(RecordEntry::caughtAt));
                    return List.copyOf(next.subList(0, Math.min(TOP, next.size())));
                });
        List<RecordEntry> after = top.getOrDefault(fish + "|" + league, List.of());
        return !after.isEmpty()
                && after.getFirst().player().equals(entry.player())
                && (previousLeader == null || entry.size() > previousLeader.size());
    }

    private void weekly(String fish, RecordEntry entry) {
        try {
            weekly.addFirst(new WeeklyRecord(FishId.parse(fish), entry));
        } catch (IllegalArgumentException e) {
            return;
        }
        while (weekly.size() > WEEKLY_MAX) {
            weekly.removeLast();
        }
    }

    /**
     * This week's server records (newest first, only those this server received; empty after a
     * restart).
     */
    List<WeeklyRecord> weeklyRecords() {
        return List.copyOf(weekly);
    }

    void clearWeekly() {
        weekly.clear();
    }

    List<RecordEntry> serverTop(FishId fish, String league, int limit) {
        List<RecordEntry> list = top.getOrDefault(fish + "|" + league, List.of());
        return list.subList(0, Math.min(limit, list.size()));
    }

    /** Admin: deletes a fraudulent record and removes it from the cache. */
    void removeRecord(FishId fish, String league, UUID player) {
        SeasonId season = api.infra().season();
        var _ =
                api.database()
                        .asyncVoid(
                                c ->
                                        CollectionRepository.deleteRecord(
                                                c, fish, season, league, player))
                        .thenRunAsync(
                                () ->
                                        top.computeIfPresent(
                                                fish + "|" + league,
                                                (k, list) ->
                                                        list.stream()
                                                                .filter(
                                                                        e ->
                                                                                !e.player()
                                                                                        .equals(
                                                                                                player))
                                                                .toList()),
                                tasks.main());
    }
}
