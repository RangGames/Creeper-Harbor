package wiki.creeper.harbor.common.config;

import java.nio.file.Path;
import java.time.DateTimeException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.db.DatabaseConfig;
import wiki.creeper.harbor.common.model.SeasonId;
import wiki.creeper.harbor.common.redis.RedisConfig;
import wiki.creeper.harbor.common.session.SessionLocks;
import wiki.creeper.harbor.common.time.HarborTime;
import wiki.creeper.harbor.common.util.Result;
import wiki.creeper.harbor.common.util.ValidationErrors;
import wiki.creeper.harbor.common.yaml.YamlNode;

/**
 * 인프라 설정 (harbor-infra.yml). 서버마다 다른 값(서버 ID, DB·Redis 주소)만 둔다. 게임 수치는 콘텐츠의 balance.yml에 둔다.
 *
 * @param serverId 서버 ID (세션 잠금·메시지 발신자). 프록시에 등록한 서버 이름과 같아야 한다.
 * @param dev 개발 모드: 메인 스레드 입출력 감지 경고 등
 * @param contentDir 콘텐츠 폴더 (null이면 plugins/Harbor/content)
 * @param lobbyServer 리소스팩 거절·세션 대기 때 보낼 로비 서버 이름
 */
public record InfraConfig(
        String serverId,
        ServerRole role,
        boolean dev,
        DatabaseConfig database,
        RedisConfig redis,
        SeasonId season,
        LocalDate seasonStart,
        ZoneId zone,
        LocalTime dailyReset,
        DayOfWeek weeklyReset,
        @Nullable Path contentDir,
        String lobbyServer,
        List<ResourcePack> resourcePacks) {

    /** 접속 때 보내는 필수 리소스팩. sha1은 40자리 16진수. */
    public record ResourcePack(String id, String url, String sha1) {}

    private static final Pattern SHA1 = Pattern.compile("[0-9a-f]{40}");

    public HarborTime time() {
        return new HarborTime(java.time.Clock.system(zone), dailyReset, weeklyReset, seasonStart);
    }

    public static Result<InfraConfig, ValidationErrors> load(Path file) {
        ValidationErrors errors = new ValidationErrors();
        Optional<YamlNode> root = YamlNode.read(file, file.getFileName().toString(), errors);
        if (root.isEmpty()) {
            return Result.err(errors);
        }
        InfraConfig config = parse(root.get());
        return errors.isEmpty() ? Result.ok(config) : Result.err(errors);
    }

    static InfraConfig parse(YamlNode root) {
        root.allowOnly(Set.of("server", "dev", "database", "redis", "season", "time", "content_dir", "lobby_server", "resource_packs"));

        YamlNode server = root.require("server").orElse(root);
        String serverId = server.string("id");
        try {
            if (!serverId.isEmpty()) {
                SessionLocks.requireServerId(serverId);
            }
        } catch (IllegalArgumentException e) {
            server.error(e.getMessage());
        }
        ServerRole role = server.enumValue("role", ServerRole.class).orElse(ServerRole.MAIN);

        YamlNode db = root.require("database").orElse(root);
        DatabaseConfig database =
                new DatabaseConfig(
                        db.string("host", "localhost"),
                        db.integer("port", 3306, 1, 65535),
                        db.string("name", "harbor"),
                        db.string("user"),
                        db.string("password", ""),
                        db.integer("pool_size", role.defaultPoolSize(), 1, 200),
                        "harbor-" + (serverId.isEmpty() ? "unknown" : serverId));

        RedisConfig redis = new RedisConfig(root.require("redis").orElse(root).string("uri"));

        YamlNode seasonNode = root.require("season").orElse(root);
        int seasonNumber = seasonNode.integer("id", 1, 1, 1000);
        LocalDate seasonStart = LocalDate.now(HarborTime.DEFAULT_ZONE);
        Optional<YamlNode> start = seasonNode.require("start");
        if (start.isPresent()) {
            try {
                seasonStart = LocalDate.parse(start.get().asString());
            } catch (DateTimeException e) {
                start.get().error("날짜는 2027-09-01 형식이어야 합니다");
            }
        }

        ZoneId zone = HarborTime.DEFAULT_ZONE;
        LocalTime dailyReset = LocalTime.of(6, 0);
        DayOfWeek weeklyReset = DayOfWeek.MONDAY;
        Optional<YamlNode> time = root.get("time");
        if (time.isPresent()) {
            YamlNode t = time.get();
            try {
                zone = ZoneId.of(t.string("zone", HarborTime.DEFAULT_ZONE.getId()));
                dailyReset = LocalTime.parse(t.string("daily_reset", "06:00"));
                weeklyReset = DayOfWeek.valueOf(t.string("weekly_reset", "MONDAY").toUpperCase(Locale.ROOT));
            } catch (DateTimeException | IllegalArgumentException e) {
                t.error("time 설정이 틀렸습니다 (zone: Asia/Seoul, daily_reset: \"06:00\", weekly_reset: MONDAY): " + e.getMessage());
            }
        }

        List<ResourcePack> packs = new ArrayList<>();
        for (YamlNode pack : root.list("resource_packs")) {
            String sha1 = pack.string("sha1").toLowerCase(Locale.ROOT);
            if (!SHA1.matcher(sha1).matches()) {
                pack.error("sha1은 40자리 16진수여야 합니다");
            }
            packs.add(new ResourcePack(pack.string("id"), pack.string("url"), sha1));
        }

        return new InfraConfig(
                serverId,
                role,
                root.bool("dev", false),
                database,
                redis,
                new SeasonId(Math.max(1, seasonNumber)),
                seasonStart,
                zone,
                dailyReset,
                weeklyReset,
                root.optString("content_dir").map(Path::of).orElse(null),
                root.string("lobby_server", "lobby"),
                List.copyOf(packs));
    }
}
