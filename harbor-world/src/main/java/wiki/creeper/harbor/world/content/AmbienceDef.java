package wiki.creeper.harbor.world.content;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.content.ContentType;
import wiki.creeper.harbor.common.content.ParseContext;
import wiki.creeper.harbor.common.content.type.SoundTable;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.common.id.RegionId;
import wiki.creeper.harbor.common.yaml.YamlNode;

/**
 * Region ambience without a datapack (world/ambience.yml, "4. Fishing presentation" 1.3 region
 * ambience (screen · sound) and time-of-day presentation, proposed). The plugin plays it per player
 * while they are in the region: background music and ambient loops, a resource pack post effect
 * (tint/mist instead of biome fog and light colors, which need a datapack), ambient particles and
 * time-of-day phases that fade in and out by overworld time. No server API.
 *
 * @param id region ID
 * @param music background music sound key (looped by sounds.yml loop; vanilla music is cut while it
 *     plays, null for none)
 * @param ambient ambient loop sound key (null for none)
 * @param postEffect resource pack post effect while in the region (null for none)
 * @param weather personal weather while in the region (clear · downfall, null = world weather)
 * @param particles ambient particles while in the region
 * @param phases time-of-day phases
 */
public record AmbienceDef(
        RegionId id,
        @Nullable String music,
        @Nullable String ambient,
        @Nullable String postEffect,
        @Nullable String weather,
        List<ParticleSpec> particles,
        List<Phase> phases) {

    public static final ContentType.Entries<AmbienceDef> TYPE =
            new ContentType.Entries<>(
                    "world_ambience",
                    List.of("world/ambience.yml"),
                    RegionId.PREFIX,
                    AmbienceDef::parse,
                    a -> a.id().id());

    private static final Pattern NAME = Pattern.compile("[a-z0-9_]+");
    private static final Pattern COLOR = Pattern.compile("#[0-9a-fA-F]{6}");

    public AmbienceDef {
        particles = List.copyOf(particles);
        phases = List.copyOf(phases);
    }

    /**
     * Ambient particles around the player.
     *
     * @param type particle name (org.bukkit.Particle, lowercase)
     * @param count particles per pulse (every 10 ticks) at full strength
     * @param radius horizontal radius around the player
     * @param minY lowest offset from the player's feet
     * @param maxY highest offset from the player's feet
     * @param water only on water surfaces (night plankton, dawn mist)
     * @param color #RRGGBB for dust particles (null for other particles)
     */
    public record ParticleSpec(
            String type,
            int count,
            double radius,
            double minY,
            double maxY,
            boolean water,
            @Nullable String color) {}

    /**
     * Time-of-day phase (Timeline keyframes without a datapack): active between {@code from} and
     * {@code to} overworld ticks (wraps past 24000), fading over {@code fade} ticks at both ends
     * (smoothstep easing).
     *
     * @param postEffect post effect while the phase is at least half faded in (null for none)
     */
    public record Phase(
            String id,
            int from,
            int to,
            int fade,
            List<ParticleSpec> particles,
            @Nullable String postEffect) {

        public Phase {
            particles = List.copyOf(particles);
        }

        /** Strength 0~1 at this overworld time. */
        public double strength(long time) {
            int t = (int) Math.floorMod(time, 24000L);
            int length = Math.floorMod(to - from, 24000);
            int into = Math.floorMod(t - from, 24000);
            if (length == 0 || into >= length) {
                return 0;
            }
            int edge = Math.min(into, length - into);
            if (fade <= 0 || edge >= fade) {
                return 1;
            }
            double x = (double) edge / fade;
            return x * x * (3 - 2 * x);
        }
    }

    static AmbienceDef parse(YamlNode node, ParseContext ctx) {
        node.allowOnly(
                Set.of("id", "music", "ambient", "post_effect", "weather", "particles", "phases"));
        HarborId rawId =
                node.require("id")
                        .flatMap(n -> n.asId(RegionId.PREFIX))
                        .orElse(HarborId.parse("region/invalid"));
        node.get("id").ifPresent(n -> ctx.ref("region", rawId, n.pos()));
        RegionId id = new RegionId(rawId);
        String weather = node.optString("weather").orElse(null);
        if (weather != null && !Set.of("clear", "downfall").contains(weather)) {
            node.error("weather는 clear·downfall 중 하나입니다: " + weather);
            weather = null;
        }
        List<Phase> phases = new ArrayList<>();
        for (YamlNode p : node.list("phases")) {
            p.allowOnly(Set.of("id", "time", "fade", "particles", "post_effect"));
            List<YamlNode> time = p.list("time");
            int from = 0;
            int to = 0;
            if (time.size() != 2) {
                p.error("time은 [시작틱, 끝틱] 형식입니다 (0~24000, 오버월드 시간)");
            } else {
                from = Math.floorMod(time.get(0).asInt(), 24000);
                to = Math.floorMod(time.get(1).asInt(), 24000);
            }
            String phaseId = p.string("id");
            if (!NAME.matcher(phaseId).matches()) {
                p.error("id는 소문자·숫자·밑줄만 씁니다: " + phaseId);
            }
            phases.add(
                    new Phase(
                            phaseId,
                            from,
                            to,
                            p.integer("fade", 600, 0, 12000),
                            particles(p.list("particles")),
                            key(p, "post_effect")));
        }
        return new AmbienceDef(
                id,
                key(node, "music"),
                key(node, "ambient"),
                key(node, "post_effect"),
                weather,
                particles(node.list("particles")),
                phases);
    }

    private static List<ParticleSpec> particles(List<YamlNode> nodes) {
        List<ParticleSpec> result = new ArrayList<>();
        for (YamlNode n : nodes) {
            n.allowOnly(Set.of("type", "count", "radius", "height", "water", "color"));
            String type = n.string("type");
            if (!NAME.matcher(type).matches()) {
                n.error("type은 파티클 이름입니다 (예: snowflake): " + type);
            }
            String color = n.optString("color").orElse(null);
            if (color != null && !COLOR.matcher(color).matches()) {
                n.error("color는 #RRGGBB 형식이어야 합니다");
                color = null;
            }
            if (type.equals("dust") && color == null) {
                n.error("dust 파티클에는 color가 필요합니다");
            }
            List<YamlNode> height = n.list("height");
            double minY = 0;
            double maxY = 4;
            if (height.size() == 2) {
                minY = height.get(0).asDouble();
                maxY = height.get(1).asDouble();
            } else if (!height.isEmpty()) {
                n.error("height는 [아래, 위] 형식입니다 (발 기준 블록)");
            }
            result.add(
                    new ParticleSpec(
                            type,
                            n.integer("count", 4, 0, 64),
                            n.number("radius", 12),
                            Math.min(minY, maxY),
                            Math.max(minY, maxY),
                            n.bool("water", false),
                            color));
        }
        return result;
    }

    /** Sound or resource key (null if absent). On a bad format, records an error. */
    private static @Nullable String key(YamlNode node, String field) {
        String value = node.optString(field).orElse("");
        if (value.isEmpty()) {
            return null;
        }
        if (!SoundTable.KEY.matcher(value).matches()) {
            node.error(field + " 형식이 아닙니다 (예: harbor:music.fjord): " + value);
            return null;
        }
        return value;
    }
}
