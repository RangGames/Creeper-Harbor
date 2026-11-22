package wiki.creeper.harbor.season.content;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.content.ContentType;
import wiki.creeper.harbor.common.content.ParseContext;
import wiki.creeper.harbor.common.id.RegionId;
import wiki.creeper.harbor.common.yaml.YamlNode;

/** season/seasons.yml: list of season definitions, in ascending season number. */
public record SeasonDefs(List<SeasonDef> seasons) {

    public static final ContentType.Single<SeasonDefs> TYPE =
            new ContentType.Single<>("season_defs", "season/seasons.yml", false, SeasonDefs::parse);

    private static final Pattern SHA1 = Pattern.compile("[0-9a-f]{40}");
    private static final Pattern DIR = Pattern.compile("[a-z0-9_]+(/[a-z0-9_]+)*");

    public SeasonDefs {
        seasons = List.copyOf(seasons);
    }

    public Optional<SeasonDef> get(int season) {
        return seasons.stream().filter(s -> s.season() == season).findFirst();
    }

    static SeasonDefs parse(YamlNode root, ParseContext ctx) {
        root.allowOnly(Set.of("seasons"));
        List<SeasonDef> result = new ArrayList<>();
        Set<Integer> seen = new HashSet<>();
        LocalDate previousStart = null;
        for (YamlNode node : root.list("seasons")) {
            node.allowOnly(
                    Set.of(
                            "season",
                            "name",
                            "starts_at",
                            "weeks",
                            "theme_region",
                            "resourcepack",
                            "content_dir"));
            int season = node.integer("season", 1, 1, 1000);
            if (!seen.add(season)) {
                node.error("시즌 " + season + "이(가) 중복됩니다");
            }
            String name = node.string("name", "S" + season);
            LocalDate date = LocalDate.of(2000, 1, 1);
            LocalTime time = null;
            Optional<YamlNode> starts = node.require("starts_at");
            if (starts.isPresent()) {
                String raw = starts.get().asString();
                try {
                    if (raw.contains("T")) {
                        LocalDateTime dt = LocalDateTime.parse(raw);
                        date = dt.toLocalDate();
                        time = dt.toLocalTime();
                    } else {
                        date = LocalDate.parse(raw);
                    }
                } catch (DateTimeException e) {
                    starts.get().error("starts_at은 2027-09-06 또는 2027-09-06T06:00 형식이어야 합니다");
                }
            }
            int weeks = node.integer("weeks", 12, 1, 52);
            RegionId region = null;
            Optional<YamlNode> regionNode = node.get("theme_region");
            if (regionNode.isPresent()) {
                region = regionNode.get().asId(RegionId.PREFIX).map(RegionId::new).orElse(null);
                if (region != null) {
                    ctx.ref("region", region.id(), regionNode.get().pos());
                }
            }
            String url = null;
            String sha1 = null;
            Optional<YamlNode> pack = node.get("resourcepack");
            if (pack.isPresent()) {
                YamlNode p = pack.get();
                p.allowOnly(Set.of("url", "sha1"));
                url = p.string("url");
                sha1 = p.string("sha1").toLowerCase(Locale.ROOT);
                if (!url.startsWith("https://") && !url.startsWith("http://")) {
                    p.error("리소스팩 url은 http(s)://로 시작해야 합니다");
                }
                if (!SHA1.matcher(sha1).matches()) {
                    p.error("sha1은 40자리 16진수여야 합니다");
                }
            }
            @Nullable String dir = node.optString("content_dir").orElse(null);
            if (dir != null && !DIR.matcher(dir).matches()) {
                node.error("content_dir은 소문자·숫자·밑줄과 / 로 된 상대 경로여야 합니다: " + dir);
            }
            if (previousStart != null && !date.isAfter(previousStart)) {
                node.error("시즌 시작일은 앞 시즌보다 늦어야 합니다");
            }
            previousStart = date;
            result.add(new SeasonDef(season, name, date, time, weeks, region, url, sha1, dir));
        }
        result.sort(Comparator.comparingInt(SeasonDef::season));
        return new SeasonDefs(result);
    }
}
