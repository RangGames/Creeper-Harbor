package wiki.creeper.harbor.world.content;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.content.ContentType;
import wiki.creeper.harbor.common.content.ParseContext;
import wiki.creeper.harbor.common.content.type.SoundTable;
import wiki.creeper.harbor.common.id.FishId;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.common.id.RegionId;
import wiki.creeper.harbor.common.model.Grade;
import wiki.creeper.harbor.common.yaml.YamlNode;
import wiki.creeper.harbor.fishing.api.SeaEventMessage.FishEffect;
import wiki.creeper.harbor.world.api.SeaEventType;

/**
 * Sea event and tournament schedule (world/events.yml, docs 20 8.3 events.yml, '11. data design'
 * 2.8). No server API.
 *
 * @param regions candidate regions (one per run, empty = whole server)
 * @param area sub-area within the region (storm waters etc.)
 * @param window time window when it can fire
 * @param chance daily chance of firing in the window
 * @param requiresFlag server flag condition
 * @param maxPerSeason maximum runs per season (0 = unlimited)
 * @param wildlifeBoost Wildlife density multiplier (great fish migration, 1 = none)
 * @param startSound sound played to players in the waters at the start ('15. sound' 4.7, proposed.
 *     null = none)
 * @param loopSound sound looped while inside the storm waters (null = none)
 */
public record SeaEventDef(
        HarborId id,
        String name,
        SeaEventType type,
        List<RegionId> regions,
        @Nullable String area,
        Window window,
        double chance,
        int minGapHours,
        int durationMinutes,
        int warnMinutes,
        @Nullable String requiresFlag,
        int maxPerSeason,
        List<ModifierSpec> modifiers,
        List<FishEffect> fishEffects,
        double wildlifeBoost,
        @Nullable Storm storm,
        @Nullable Ghost ghost,
        @Nullable Tournament tournament,
        @Nullable String startSound,
        @Nullable String loopSound) {

    /** Time window. If end is earlier than start, it crosses midnight. */
    public record Window(Set<DayOfWeek> days, LocalTime start, LocalTime end) {

        /** Window length (minutes). */
        public int minutes() {
            int s = start.toSecondOfDay() / 60;
            int e = end.toSecondOfDay() / 60;
            return e > s ? e - s : e + 24 * 60 - s;
        }
    }

    /** Event modifier (source event:<id>). */
    public record ModifierSpec(String stat, boolean multiply, double value) {}

    /**
     * Storm settings.
     *
     * @param damage ship durability lost per interval
     * @param speedMult ship speed multiplier during the storm
     */
    public record Storm(
            int damage,
            int intervalSeconds,
            double speedMult,
            int lightningIntervalSeconds,
            double lightningDamage) {}

    /** One kind of skeleton pirate (World spawns and manages them itself, docs 20 0.1). */
    public record PirateMob(
            String id,
            String entity,
            String name,
            double health,
            @Nullable String weapon,
            @Nullable String helmet,
            int weight) {}

    /** Ghost ship raid settings. */
    public record Ghost(
            int waveIntervalSeconds,
            int perPlayer,
            int maxAlive,
            double minRadius,
            double maxRadius,
            String treasureTier,
            List<PirateMob> mobs,
            @Nullable PirateMob captain) {}

    /** Rank reward bracket. */
    public record Reward(int from, int to, long gold) {}

    /** Weekly fishing tournament settings. */
    public record Tournament(
            Map<Grade, Integer> points, double qualityBonus, String board, List<Reward> rewards) {

        public int points(Grade grade) {
            return points.getOrDefault(grade, 0);
        }
    }

    public static final String PREFIX = "event/";

    public static final Set<String> ENTITIES =
            Set.of("skeleton", "stray", "wither_skeleton", "drowned");

    public static final ContentType.Entries<SeaEventDef> TYPE =
            new ContentType.Entries<>(
                    "sea_event",
                    List.of("world/events.yml"),
                    PREFIX,
                    SeaEventDef::parse,
                    SeaEventDef::id);

    public static final String STORM_LOOP = "harbor:event.storm_loop";

    private static final Set<String> FIELDS =
            Set.of(
                    "id",
                    "name",
                    "type",
                    "regions",
                    "area",
                    "window",
                    "chance",
                    "min_gap_h",
                    "duration_min",
                    "warn_min",
                    "requires_flag",
                    "max_per_season",
                    "modifiers",
                    "fish_effects",
                    "wildlife_boost",
                    "storm",
                    "ghost",
                    "tournament",
                    "sounds");

    /** Last segment of the event ID (used for run IDs and modifier sources). */
    public String key() {
        return id.name();
    }

    static SeaEventDef parse(YamlNode node, ParseContext ctx) {
        node.allowOnly(FIELDS);
        HarborId id =
                node.require("id")
                        .flatMap(n -> n.asId(PREFIX))
                        .orElse(HarborId.parse("event/invalid"));
        SeaEventType type = node.enumValue("type", SeaEventType.class).orElse(SeaEventType.SPECIAL);

        List<RegionId> regions = new ArrayList<>();
        for (YamlNode r : node.list("regions")) {
            r.asId(RegionId.PREFIX)
                    .ifPresent(
                            h -> {
                                ctx.ref("region", h, r.pos());
                                regions.add(new RegionId(h));
                            });
        }

        Window window = window(node);

        List<ModifierSpec> modifiers = new ArrayList<>();
        for (YamlNode m : node.list("modifiers")) {
            m.allowOnly(Set.of("stat", "op", "value"));
            String stat = m.string("stat");
            if (!stat.matches("[a-z0-9_]+")) {
                m.error("stat은 소문자·숫자·밑줄입니다: '" + stat + "'");
            }
            String op = m.string("op", "add");
            if (!op.equals("add") && !op.equals("multiply")) {
                m.error("op는 add 또는 multiply입니다: '" + op + "'");
            }
            modifiers.add(new ModifierSpec(stat, op.equals("multiply"), m.number("value")));
        }

        List<FishEffect> effects = new ArrayList<>();
        for (YamlNode f : node.list("fish_effects")) {
            f.allowOnly(Set.of("type", "fish", "mult", "chance", "cap"));
            HarborId fish =
                    f.require("fish")
                            .flatMap(n -> n.asId(FishId.PREFIX))
                            .orElse(HarborId.parse("fish/invalid"));
            f.get("fish").ifPresent(n -> ctx.ref("fish", fish, n.pos()));
            String effectType = f.string("type");
            switch (effectType) {
                case FishEffect.SPECIES_WEIGHT -> {
                    double mult = f.number("mult", 1);
                    if (mult <= 0) {
                        f.error("mult는 0보다 커야 합니다");
                    }
                    effects.add(FishEffect.speciesWeight(fish.toString(), mult));
                }
                case FishEffect.MYTHIC -> {
                    double chance = f.number("chance", 0);
                    if (chance < 0 || chance > 1) {
                        f.error("chance는 0~1입니다");
                    }
                    effects.add(
                            FishEffect.mythic(
                                    fish.toString(), chance, f.integer("cap", 1, 1, 10_000)));
                }
                default -> f.error("type은 species_weight 또는 mythic입니다: '" + effectType + "'");
            }
        }

        double chance = node.number("chance", 1);
        if (chance < 0 || chance > 1) {
            node.error("chance는 0~1입니다");
        }

        Storm storm = null;
        if (node.has("storm")) {
            YamlNode s = node.get("storm").orElseThrow();
            s.allowOnly(
                    Set.of(
                            "damage",
                            "interval_s",
                            "speed_mult",
                            "lightning_interval_s",
                            "lightning_damage"));
            storm =
                    new Storm(
                            s.integer("damage", 1, 0, 1000),
                            s.integer("interval_s", 5, 1, 3600),
                            s.number("speed_mult", 0.7),
                            s.integer("lightning_interval_s", 12, 0, 3600),
                            s.number("lightning_damage", 4));
        } else if (type == SeaEventType.STORM) {
            node.error("type storm에는 storm 설정이 필요합니다");
        }

        Ghost ghost = null;
        if (node.has("ghost")) {
            YamlNode g = node.get("ghost").orElseThrow();
            g.allowOnly(
                    Set.of(
                            "wave_interval_s",
                            "per_player",
                            "max_alive",
                            "radius",
                            "treasure_tier",
                            "mobs",
                            "captain"));
            List<PirateMob> mobs = new ArrayList<>();
            for (YamlNode m : g.list("mobs")) {
                mobs.add(mob(m));
            }
            if (mobs.isEmpty()) {
                g.error("mobs가 비었습니다");
            }
            double minR = 10;
            double maxR = 22;
            List<YamlNode> radius = g.list("radius");
            if (radius.size() == 2) {
                minR = radius.get(0).asDouble();
                maxR = radius.get(1).asDouble();
                if (minR <= 0 || maxR < minR) {
                    g.error("radius는 [최소, 최대]이고 0보다 커야 합니다");
                }
            } else if (!radius.isEmpty()) {
                g.error("radius는 [최소, 최대]입니다");
            }
            ghost =
                    new Ghost(
                            g.integer("wave_interval_s", 90, 10, 3600),
                            g.integer("per_player", 2, 1, 20),
                            g.integer("max_alive", 40, 1, 500),
                            minR,
                            maxR,
                            g.string("treasure_tier", "ghost"),
                            List.copyOf(mobs),
                            g.get("captain").map(SeaEventDef::mob).orElse(null));
        } else if (type == SeaEventType.GHOST_SHIP) {
            node.error("type ghost_ship에는 ghost 설정이 필요합니다");
        }

        Tournament tournament = null;
        if (node.has("tournament")) {
            YamlNode t = node.get("tournament").orElseThrow();
            t.allowOnly(Set.of("points", "quality_bonus", "board", "rewards"));
            Map<Grade, Integer> points = new LinkedHashMap<>();
            t.get("points")
                    .ifPresent(
                            p ->
                                    p.entries()
                                            .forEach(
                                                    (k, v) -> {
                                                        Grade grade = grade(k);
                                                        if (grade == null) {
                                                            v.error("알 수 없는 등급: '" + k + "'");
                                                        } else {
                                                            points.put(grade, v.asInt());
                                                        }
                                                    }));
            List<Reward> rewards = new ArrayList<>();
            int lastTo = 0;
            for (YamlNode r : t.list("rewards")) {
                r.allowOnly(Set.of("from", "to", "gold"));
                int from = r.integer("from", 1, 1, 10_000);
                int to = r.integer("to", from, 1, 10_000);
                if (to < from || from <= lastTo) {
                    r.error("순위 구간은 겹치지 않게 오름차순으로 적습니다");
                }
                lastTo = to;
                rewards.add(new Reward(from, to, r.longValue("gold", 0)));
            }
            String board = t.string("board", "tournament");
            if (!board.matches("[a-z0-9_]+")) {
                t.error("board는 소문자·숫자·밑줄입니다");
            }
            tournament =
                    new Tournament(
                            Map.copyOf(points),
                            t.number("quality_bonus", 0),
                            board,
                            List.copyOf(rewards));
        } else if (type == SeaEventType.TOURNAMENT) {
            node.error("type tournament에는 tournament 설정이 필요합니다");
        }

        String flag = node.optString("requires_flag").orElse(null);
        if (flag != null && !flag.matches("[a-z0-9_]+(/[a-z0-9_]+)*")) {
            node.error("requires_flag 형식이 틀렸습니다: '" + flag + "'");
        }
        String area = node.optString("area").orElse(null);
        if (area != null && !area.matches("[a-z0-9_]+")) {
            node.error("area는 소문자·숫자·밑줄입니다");
        }

        int duration = node.integer("duration_min", 60, 1, 7 * 24 * 60);
        if (duration > window.minutes() + 24 * 60) {
            node.error("duration_min이 너무 깁니다");
        }
        return new SeaEventDef(
                id,
                node.string("name"),
                type,
                List.copyOf(regions),
                area,
                window,
                chance,
                node.integer("min_gap_h", 0, 0, 24 * 365),
                duration,
                node.integer("warn_min", 5, 0, 120),
                flag,
                node.integer("max_per_season", 0, 0, 1000),
                List.copyOf(modifiers),
                List.copyOf(effects),
                node.number("wildlife_boost", 1),
                storm,
                ghost,
                tournament,
                sound(node, "start", defaultStartSound(type)),
                sound(node, "loop", type == SeaEventType.STORM ? STORM_LOOP : null));
    }

    private static @Nullable String defaultStartSound(SeaEventType type) {
        return switch (type) {
            case GHOST_SHIP -> "harbor:event.ghost_ship";
            case MIGRATION -> "harbor:event.migration";
            default -> null;
        };
    }

    /**
     * sounds.start / sounds.loop: sound key; an empty string means none, a missing key means the
     * per-type default.
     */
    private static @Nullable String sound(YamlNode node, String key, @Nullable String fallback) {
        Optional<YamlNode> sounds = node.get("sounds");
        if (sounds.isEmpty()) {
            return fallback;
        }
        sounds.get().allowOnly(Set.of("start", "loop"));
        Optional<YamlNode> value = sounds.get().get(key);
        if (value.isEmpty()) {
            return fallback;
        }
        String text = value.get().isNull() ? "" : value.get().asString();
        if (text.isEmpty()) {
            return null;
        }
        if (!SoundTable.KEY.matcher(text).matches()) {
            value.get().error("소리 키 형식이 아닙니다 (예: harbor:event.storm_loop): " + text);
            return fallback;
        }
        return text;
    }

    private static Window window(YamlNode node) {
        Set<DayOfWeek> days = EnumSet.allOf(DayOfWeek.class);
        LocalTime start = LocalTime.of(0, 0);
        LocalTime end = LocalTime.of(23, 59);
        if (node.has("window")) {
            YamlNode w = node.get("window").orElseThrow();
            w.allowOnly(Set.of("days", "start", "end"));
            List<YamlNode> rawDays = w.list("days");
            if (!rawDays.isEmpty()) {
                days = EnumSet.noneOf(DayOfWeek.class);
                for (YamlNode d : rawDays) {
                    DayOfWeek day = day(d.asString());
                    if (day == null) {
                        d.error(
                                "요일은 mon · tue · wed · thu · fri · sat · sun입니다: '"
                                        + d.asString()
                                        + "'");
                    } else {
                        days.add(day);
                    }
                }
            }
            start = time(w, "start", start);
            end = time(w, "end", end);
            if (start.equals(end)) {
                w.error("start와 end가 같습니다");
            }
        } else {
            node.error("window가 필요합니다 ({days, start, end})");
        }
        return new Window(Set.copyOf(days), start, end);
    }

    private static LocalTime time(YamlNode w, String key, LocalTime fallback) {
        return w.optString(key)
                .map(
                        raw -> {
                            try {
                                return LocalTime.parse(raw.length() == 4 ? "0" + raw : raw);
                            } catch (DateTimeParseException e) {
                                w.error(key + "는 HH:MM 형식입니다: '" + raw + "'");
                                return fallback;
                            }
                        })
                .orElse(fallback);
    }

    static @Nullable DayOfWeek day(String raw) {
        return switch (raw.toLowerCase(Locale.ROOT)) {
            case "mon" -> DayOfWeek.MONDAY;
            case "tue" -> DayOfWeek.TUESDAY;
            case "wed" -> DayOfWeek.WEDNESDAY;
            case "thu" -> DayOfWeek.THURSDAY;
            case "fri" -> DayOfWeek.FRIDAY;
            case "sat" -> DayOfWeek.SATURDAY;
            case "sun" -> DayOfWeek.SUNDAY;
            default -> null;
        };
    }

    private static @Nullable Grade grade(String key) {
        for (Grade g : Grade.values()) {
            if (g.key().equals(key)) {
                return g;
            }
        }
        return null;
    }

    private static PirateMob mob(YamlNode m) {
        m.allowOnly(Set.of("id", "entity", "name", "health", "weapon", "helmet", "weight"));
        String entity = m.string("entity", "skeleton");
        if (!ENTITIES.contains(entity)) {
            m.error(
                    "entity는 "
                            + String.join(" · ", ENTITIES.stream().sorted().toList())
                            + " 중 하나입니다");
        }
        return new PirateMob(
                RegionExt.localId(m, "id"),
                entity,
                m.string("name", ""),
                m.number("health", 20),
                m.optString("weapon").orElse(null),
                m.optString("helmet").orElse(null),
                m.integer("weight", 1, 0, 10_000));
    }
}
