package wiki.creeper.harbor.quest.content;

import java.util.Locale;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.content.ParseContext;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.common.model.Grade;
import wiki.creeper.harbor.common.model.Keyed;
import wiki.creeper.harbor.common.util.SourcePos;

/**
 * Objective target notation (docs 20 6.4 inverted index): fish, item, NPC, boss or cutscene ID,
 * {@code grade:rare+} (that grade or higher), {@code grade:rare} (that grade only), {@code
 * region:<region ID>}, {@code point:<x>,<y>,<z>,<r>} (proposed, may be prefixed with a world),
 * {@code any}. Flag objectives use the flag name as is.
 *
 * @param raw as written in the YAML (inverted index key, shown in admin commands)
 */
public record Target(
        Kind kind,
        String raw,
        @Nullable HarborId id,
        @Nullable Grade grade,
        boolean orAbove,
        @Nullable Point point) {

    public enum Kind {
        ANY,
        /** Content ID (fish/, item/, npc/, boss/, cutscene/, region/ …). */
        ID,
        GRADE,
        REGION,
        POINT,
        /** A name that is not an ID (flags etc.). */
        NAME
    }

    /**
     * Point objective.
     *
     * @param world content world ID (empty means any world)
     */
    public record Point(@Nullable String world, double x, double y, double z, double radius) {

        public boolean contains(String worldId, double px, double py, double pz) {
            if (world != null && !world.equals(worldId)) {
                return false;
            }
            double dx = px - x;
            double dy = py - y;
            double dz = pz - z;
            return dx * dx + dy * dy + dz * dz <= radius * radius;
        }
    }

    public static final Target ANY = new Target(Kind.ANY, "any", null, null, false, null);

    public static Target id(HarborId id) {
        return new Target(Kind.ID, id.toString(), id, null, false, null);
    }

    public static Target region(HarborId region) {
        return new Target(Kind.REGION, "region:" + region, region, null, false, null);
    }

    public static Target name(String name) {
        return new Target(Kind.NAME, name, null, null, false, null);
    }

    /** Whether this is the category of this ID (first path segment). */
    public boolean isId(String category) {
        HarborId value = id;
        return kind == Kind.ID && value != null && value.category().equals(category);
    }

    /**
     * Reads a target notation. Format errors are reported through errors and ANY is returned.
     *
     * @param ctx reference check registration (ID category → type name: fish, item, npc, boss,
     *     cutscene, region)
     */
    public static Target parse(
            String raw,
            SourcePos pos,
            ParseContext ctx,
            java.util.function.Consumer<String> error) {
        String value = raw.strip();
        if (value.isEmpty() || value.equals("any")) {
            return ANY;
        }
        if (value.startsWith("grade:")) {
            String g = value.substring("grade:".length());
            boolean above = g.endsWith("+");
            String key = (above ? g.substring(0, g.length() - 1) : g).toLowerCase(Locale.ROOT);
            Optional<Grade> grade = Keyed.fromKey(Grade.class, key);
            if (grade.isEmpty()) {
                error.accept("등급 대상 형식이 틀렸습니다 (예: grade:rare+): " + raw);
                return ANY;
            }
            return new Target(Kind.GRADE, value, null, grade.get(), above, null);
        }
        if (value.startsWith("point:")) {
            return parsePoint(value, error);
        }
        if (value.startsWith("region:")) {
            value = value.substring("region:".length());
        }
        Optional<HarborId> id = HarborId.tryParse(value);
        if (id.isEmpty()) {
            error.accept("대상 형식이 틀렸습니다: " + raw);
            return ANY;
        }
        HarborId parsed = id.get();
        String type =
                switch (parsed.category()) {
                    case "fish" -> "fish";
                    case "item" -> "item";
                    case "npc" -> "npc";
                    case "boss" -> "boss";
                    case "cutscene" -> "cutscene";
                    case "region" -> "region";
                    default -> "";
                };
        if (!type.isEmpty()) {
            ctx.ref(type, parsed, pos);
        }
        if (parsed.category().equals("region")) {
            return region(parsed);
        }
        return id(parsed);
    }

    private static Target parsePoint(String value, java.util.function.Consumer<String> error) {
        String body = value.substring("point:".length());
        String[] parts = body.split(",", -1);
        try {
            if (parts.length == 4) {
                return new Target(
                        Kind.POINT,
                        value,
                        null,
                        null,
                        false,
                        new Point(
                                null,
                                Double.parseDouble(parts[0].strip()),
                                Double.parseDouble(parts[1].strip()),
                                Double.parseDouble(parts[2].strip()),
                                Double.parseDouble(parts[3].strip())));
            }
            if (parts.length == 5) {
                return new Target(
                        Kind.POINT,
                        value,
                        null,
                        null,
                        false,
                        new Point(
                                parts[0].strip(),
                                Double.parseDouble(parts[1].strip()),
                                Double.parseDouble(parts[2].strip()),
                                Double.parseDouble(parts[3].strip()),
                                Double.parseDouble(parts[4].strip())));
            }
        } catch (NumberFormatException e) {
            // reported as an error below
        }
        error.accept(
                "지점 형식이 틀렸습니다 (point:<x>,<y>,<z>,<r> 또는 point:<월드>,<x>,<y>,<z>,<r>): " + value);
        return ANY;
    }
}
