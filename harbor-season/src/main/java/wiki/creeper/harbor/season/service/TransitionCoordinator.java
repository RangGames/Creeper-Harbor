package wiki.creeper.harbor.season.service;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.plugin.Plugin;
import org.slf4j.Logger;
import wiki.creeper.harbor.common.content.ContentLoader;
import wiki.creeper.harbor.common.content.ContentSnapshot;
import wiki.creeper.harbor.common.content.type.CoreContent;
import wiki.creeper.harbor.common.model.Currency;
import wiki.creeper.harbor.common.proxy.ProxyMessages;
import wiki.creeper.harbor.common.redis.Channels;
import wiki.creeper.harbor.common.util.Result;
import wiki.creeper.harbor.common.util.ValidationErrors;
import wiki.creeper.harbor.core.api.HarborApi;
import wiki.creeper.harbor.core.task.Tasks;
import wiki.creeper.harbor.season.SeasonContentView;
import wiki.creeper.harbor.season.api.SeasonHook;
import wiki.creeper.harbor.season.api.SeasonPhase;
import wiki.creeper.harbor.season.api.TitleId;
import wiki.creeper.harbor.season.clock.SeasonClockManager;
import wiki.creeper.harbor.season.content.SeasonContent;
import wiki.creeper.harbor.season.content.SeasonDef;
import wiki.creeper.harbor.season.hof.HallOfFameManager;
import wiki.creeper.harbor.season.net.SeasonMessages;
import wiki.creeper.harbor.season.ranking.RankingManager;
import wiki.creeper.harbor.season.transition.TransitionEnvironment;

/**
 * Main server's transition environment (TransitionEnvironment implementation) plus transition
 * participation on every server (docs 20 11.4 FREEZE).
 *
 * <ul>
 *   <li>Every server: on a harbor:season FreezeRequest, kicks players who are not admins (OP),
 *       drains the write-behind queue and replies with FlushAck. While season_meta is TRANSITIONING
 *       or ARCHIVED, non-OP logins are blocked (also blocked on the server because there is no API
 *       yet to turn on Proxy maintenance mode)
 *   <li>Transition server: learns the live server list from heartbeats (harbor:server) and waits
 *       until all of them reply
 * </ul>
 */
public final class TransitionCoordinator implements TransitionEnvironment, Listener {

    private static final Duration HEARTBEAT_FRESH = Duration.ofSeconds(15);
    private static final long MAX_PACK_BYTES = 512L << 20;

    private final HarborApi api;
    private final Plugin plugin;
    private final SeasonContentView content;
    private final SeasonClockManager clock;
    private final RankingManager rankings;
    private final HallOfFameManager hallOfFame;
    private final HookRegistry hooks;
    private final Tasks tasks;
    private final Logger log;
    private final Map<String, Instant> heartbeats = new ConcurrentHashMap<>();
    private final Map<String, Map<String, SeasonMessages.FlushAck>> acks =
            new ConcurrentHashMap<>();
    private volatile boolean frozen;

    public TransitionCoordinator(
            HarborApi api,
            Plugin plugin,
            SeasonContentView content,
            SeasonClockManager clock,
            RankingManager rankings,
            HallOfFameManager hallOfFame,
            HookRegistry hooks,
            Tasks tasks,
            Logger log) {
        this.api = api;
        this.plugin = plugin;
        this.content = content;
        this.clock = clock;
        this.rankings = rankings;
        this.hallOfFame = hallOfFame;
        this.hooks = hooks;
        this.tasks = tasks;
        this.log = log;
    }

    public void start() {
        api.bus()
                .subscribe(
                        ProxyMessages.HEARTBEATS,
                        envelope -> heartbeats.put(envelope.payload().serverId(), Instant.now()));
        api.bus().subscribe(SeasonMessages.FREEZE, envelope -> onFreeze(envelope.payload()));
        api.bus()
                .subscribe(
                        SeasonMessages.FLUSH_ACK,
                        envelope -> {
                            SeasonMessages.FlushAck ack = envelope.payload();
                            Map<String, SeasonMessages.FlushAck> map = acks.get(ack.transitionId());
                            if (map != null) {
                                map.put(ack.server(), ack);
                            }
                        });
        api.bus()
                .subscribe(
                        SeasonMessages.THAW,
                        envelope -> {
                            frozen = false;
                            log.info("시즌 전환 취소: 접속 차단을 풉니다");
                        });
    }

    /** Blocks non-OP logins during a transition (before Core loads). */
    @EventHandler(priority = EventPriority.LOW)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        if (event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) {
            return;
        }
        SeasonPhase phase = clock.state();
        boolean closed =
                frozen || phase == SeasonPhase.TRANSITIONING || phase == SeasonPhase.ARCHIVED;
        if (closed && !Bukkit.getOfflinePlayer(event.getUniqueId()).isOp()) {
            event.disallow(
                    AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                    api.messages().get("season.transition.closed"));
        }
    }

    // ------------------------------------------------------------------ FREEZE (all servers)

    private void onFreeze(SeasonMessages.FreezeRequest request) {
        if (request.season() != clock.current().value()) {
            return;
        }
        tasks.main()
                .execute(
                        () -> {
                            frozen = true;
                            Component reason = api.messages().get("season.transition.kick");
                            for (Player p : List.copyOf(Bukkit.getOnlinePlayers())) {
                                if (!p.isOp()) {
                                    p.kick(reason);
                                }
                            }
                            // Give quit saves (flushNow) time to finish, then drain the whole queue
                            var _ =
                                    tasks.later(
                                            60,
                                            () -> {
                                                int online =
                                                        (int)
                                                                Bukkit.getOnlinePlayers().stream()
                                                                        .filter(p -> !p.isOp())
                                                                        .count();
                                                var _ =
                                                        tasks.async(
                                                                () -> flushAndAck(request, online));
                                            });
                        });
    }

    private void flushAndAck(SeasonMessages.FreezeRequest request, int online) {
        boolean ok = true;
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        try {
            do {
                api.writeBehind().flush();
                if (api.writeBehind().pendingCount() == 0) {
                    break;
                }
                Thread.sleep(500);
            } while (System.nanoTime() < deadline);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            ok = false;
        } catch (RuntimeException e) {
            log.error("FREEZE 지연 쓰기 비우기 실패", e);
            ok = false;
        }
        int pending = api.writeBehind().pendingCount();
        api.bus()
                .publish(
                        SeasonMessages.FLUSH_ACK,
                        new SeasonMessages.FlushAck(
                                request.transitionId(),
                                api.infra().serverId(),
                                online,
                                pending,
                                ok && pending == 0));
    }

    @Override
    public FreezeResult freeze(String transitionId, int season, Duration timeout)
            throws InterruptedException {
        Set<String> expected = new TreeSet<>();
        Instant fresh = Instant.now().minus(HEARTBEAT_FRESH);
        heartbeats.forEach(
                (server, seen) -> {
                    if (seen.isAfter(fresh)) {
                        expected.add(server);
                    }
                });
        expected.add(api.infra().serverId());
        Map<String, SeasonMessages.FlushAck> received = new ConcurrentHashMap<>();
        acks.put(transitionId, received);
        try {
            long deadline = System.nanoTime() + timeout.toNanos();
            long nextSend = 0;
            while (System.nanoTime() < deadline) {
                if (System.nanoTime() >= nextSend) {
                    api.bus()
                            .publish(
                                    SeasonMessages.FREEZE,
                                    new SeasonMessages.FreezeRequest(transitionId, season));
                    nextSend = System.nanoTime() + Duration.ofSeconds(20).toNanos();
                }
                if (missing(expected, received).isEmpty()) {
                    return new FreezeResult(true, List.copyOf(expected), List.of());
                }
                Thread.sleep(500);
            }
            return new FreezeResult(false, List.copyOf(expected), missing(expected, received));
        } finally {
            acks.remove(transitionId);
        }
    }

    private static List<String> missing(
            Set<String> expected, Map<String, SeasonMessages.FlushAck> received) {
        List<String> result = new ArrayList<>();
        for (String server : expected) {
            SeasonMessages.FlushAck ack = received.get(server);
            if (ack == null) {
                result.add(server + "(응답 없음)");
            } else if (!ack.ok()) {
                result.add(server + "(큐 " + ack.pending() + "건 남음)");
            } else if (ack.online() > 0) {
                result.add(server + "(접속자 " + ack.online() + "명)");
            }
        }
        return result;
    }

    @Override
    public void thaw(String transitionId, int season) {
        frozen = false;
        api.bus().publish(SeasonMessages.THAW, new SeasonMessages.Thaw(transitionId, season));
    }

    @Override
    public void metaChanged(int season) {
        api.bus().publish(SeasonMessages.META, new SeasonMessages.MetaChanged(season));
        if (season == clock.current().value()) {
            var _ = clock.reloadMeta();
        }
    }

    // ------------------------------------------------------------------ checks

    @Override
    public int currentSeason() {
        return clock.current().value();
    }

    @Override
    public List<String> checkContent(int nextSeason) {
        List<String> errors = new ArrayList<>();
        Optional<SeasonDef> def = content.defs().get(nextSeason);
        if (def.isEmpty()) {
            errors.add("season/seasons.yml에 시즌 " + nextSeason + " 정의가 없습니다");
        }
        try {
            Result<ContentSnapshot, ValidationErrors> current =
                    api.content().reload(Set.of(), false).join();
            if (current
                    instanceof Result.Err<ContentSnapshot, ValidationErrors>(ValidationErrors e)) {
                errors.add("지금 콘텐츠 오류 " + e.size() + "건 (/harbor validate)");
            }
        } catch (RuntimeException e) {
            errors.add("콘텐츠 검사 실패: " + e.getMessage());
        }
        if (def.isPresent() && def.get().contentDir() != null) {
            String dirName = String.valueOf(def.get().contentDir());
            Path dir = api.content().root().resolve(dirName).normalize();
            if (!dir.startsWith(api.content().root().normalize()) || !Files.isDirectory(dir)) {
                errors.add("시즌 " + nextSeason + " content_dir이 없습니다: " + dir);
            } else if (Files.isRegularFile(dir.resolve("balance.yml"))) {
                // For a full content folder, pre-load it with the Core and Season types (other
                // modules' types are checked on server reload)
                ContentLoader loader = new ContentLoader();
                CoreContent.register(loader);
                SeasonContent.TYPES.forEach(loader::register);
                Result<ContentSnapshot, ValidationErrors> next = loader.load(dir);
                if (next
                        instanceof
                        Result.Err<ContentSnapshot, ValidationErrors>(ValidationErrors e)) {
                    errors.add(
                            "시즌 "
                                    + nextSeason
                                    + " 콘텐츠 오류 "
                                    + e.size()
                                    + "건: "
                                    + firstLines(e.format()));
                }
            }
        }
        return errors;
    }

    private static String firstLines(String text) {
        return String.join(" / ", text.lines().limit(5).toList());
    }

    @Override
    public List<String> checkResourcePack(int nextSeason) {
        Optional<SeasonDef> def = content.defs().get(nextSeason);
        if (def.isEmpty() || def.get().packUrl() == null) {
            return List.of();
        }
        String url = String.valueOf(def.get().packUrl());
        String expected = String.valueOf(def.get().packSha1());
        try (HttpClient http =
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(10))
                        .followRedirects(HttpClient.Redirect.NORMAL)
                        .build()) {
            HttpRequest request =
                    HttpRequest.newBuilder(URI.create(url))
                            .timeout(Duration.ofMinutes(3))
                            .GET()
                            .build();
            HttpResponse<InputStream> response =
                    http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) {
                return List.of("리소스팩 응답 " + response.statusCode() + ": " + url);
            }
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            long total = 0;
            try (InputStream in = response.body()) {
                byte[] buf = new byte[1 << 16];
                int n;
                while ((n = in.read(buf)) > 0) {
                    total += n;
                    if (total > MAX_PACK_BYTES) {
                        return List.of("리소스팩이 너무 큽니다: " + url);
                    }
                    sha1.update(buf, 0, n);
                }
            }
            String actual = HexFormat.of().formatHex(sha1.digest());
            return actual.equals(expected)
                    ? List.of()
                    : List.of("리소스팩 SHA-1이 다릅니다: 기대 " + expected + ", 실제 " + actual);
        } catch (IOException | IllegalArgumentException | NoSuchAlgorithmException e) {
            return List.of("리소스팩을 받지 못했습니다: " + url + " (" + e.getMessage() + ")");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return List.of("리소스팩 확인이 중단되었습니다");
        }
    }

    @Override
    public OptionalLong freeDiskBytes() {
        String host = api.infra().database().host();
        if (!host.equals("localhost") && !host.equals("127.0.0.1") && !host.equals("::1")) {
            return OptionalLong.empty();
        }
        try {
            return OptionalLong.of(
                    Files.getFileStore(plugin.getDataFolder().toPath()).getUsableSpace());
        } catch (IOException e) {
            return OptionalLong.empty();
        }
    }

    @Override
    public boolean titleExists(TitleId title) {
        return content.titles().containsKey(title.id());
    }

    @Override
    public SeasonWindow window(int season) {
        Optional<SeasonDef> def = content.defs().get(season);
        ZonedDateTime start =
                def.map(d -> d.startsAt(api.infra().zone(), api.infra().dailyReset()))
                        .orElse(api.time().now());
        int weeks = def.map(SeasonDef::weeks).orElse(12);
        return new SeasonWindow(start.toEpochSecond(), start.plusWeeks(weeks).toEpochSecond());
    }

    // ------------------------------------------------------------------ notifications

    @Override
    public void announce(String messageKey, Map<String, String> placeholders) {
        Component msg = api.messages().get(messageKey, resolvers(placeholders));
        api.bus()
                .publish(
                        Channels.ANNOUNCEMENTS,
                        new Channels.Announcement(MiniMessage.miniMessage().serialize(msg)));
    }

    @Override
    public void progress(String messageKey, Map<String, String> placeholders) {
        Component msg = api.messages().get(messageKey, resolvers(placeholders));
        log.info(
                "[시즌 전환] {}",
                MiniMessage.miniMessage().stripTags(MiniMessage.miniMessage().serialize(msg)));
        tasks.main()
                .execute(
                        () ->
                                Bukkit.getOnlinePlayers().stream()
                                        .filter(
                                                p ->
                                                        p.hasPermission(
                                                                "harbor.admin.season.transition"))
                                        .forEach(p -> p.sendMessage(msg)));
    }

    private static TagResolver[] resolvers(Map<String, String> placeholders) {
        return placeholders.entrySet().stream()
                .map(e -> Placeholder.unparsed(e.getKey(), e.getValue()))
                .toArray(TagResolver[]::new);
    }

    // ------------------------------------------------------------------ ledger · finalization ·
    // hooks

    @Override
    public long resetBalances(int season, Set<Currency> currencies) throws Exception {
        if (currentSeason() != season) {
            throw new IllegalStateException("CurrencyService가 다른 시즌을 가리킵니다");
        }
        return SeasonBalanceReset.run(api, log, season, currencies);
    }

    @Override
    public int snapshotRankings(Connection c, int season, String archiveSchema)
            throws SQLException {
        return rankings.snapshot(c, season, RankingManager.FINAL_WEEK, archiveSchema);
    }

    @Override
    public Map<String, Integer> finalizeHallOfFame(Connection c, int season, String archiveSchema)
            throws SQLException {
        return hallOfFame.finalizeSeason(c, season, archiveSchema);
    }

    @Override
    public List<SeasonHook> hooks() {
        return hooks.hooks();
    }

    @Override
    public List<String> switchContent(int nextSeason) {
        List<String> errors = new ArrayList<>(checkContent(nextSeason));
        errors.addAll(checkResourcePack(nextSeason));
        if (!errors.isEmpty()) {
            return errors;
        }
        // Core reads the season number and resource pack from harbor-infra.yml, so the new values
        // are written to a file and the operator applies them on each server
        SeasonDef def = content.defs().get(nextSeason).orElseThrow();
        StringBuilder text = new StringBuilder();
        text.append("# 시즌 ").append(nextSeason).append(" 전환 후 모든 서버 harbor-infra.yml에 반영할 값\n");
        text.append("season:\n  id: ").append(nextSeason).append('\n');
        text.append("  start: \"").append(def.startDate()).append("\"\n");
        if (def.packUrl() != null) {
            text.append("resource_packs:\n  - id: season_").append(nextSeason).append('\n');
            text.append("    url: \"").append(def.packUrl()).append("\"\n");
            text.append("    sha1: \"").append(def.packSha1()).append("\"\n");
        }
        if (def.contentDir() != null) {
            text.append("# 시즌 콘텐츠 폴더: ").append(def.contentDir()).append('\n');
        }
        try {
            Files.createDirectories(plugin.getDataFolder().toPath());
            Files.writeString(dataPath("next-season.yml"), text.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            errors.add("next-season.yml을 쓰지 못했습니다: " + e.getMessage());
        }
        return errors;
    }

    @Override
    public Path dataPath(String relative) {
        return plugin.getDataFolder().toPath().resolve(relative).normalize();
    }
}
