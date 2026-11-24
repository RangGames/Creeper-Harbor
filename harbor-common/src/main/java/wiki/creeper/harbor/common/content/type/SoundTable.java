package wiki.creeper.harbor.common.content.type;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.content.ContentType;
import wiki.creeper.harbor.common.content.ParseContext;
import wiki.creeper.harbor.common.ledger.LedgerReason;
import wiki.creeper.harbor.common.model.Currency;
import wiki.creeper.harbor.common.yaml.YamlNode;

/**
 * sounds.yml: category, volume, loop interval and vanilla fallback sound per resource pack sound
 * event ('15. Sound' chapters 4-5, proposed). Core {@code HarborSounds} picks sounds from this
 * table. If the file is missing, the table is empty (every event defaults to master, 1, 1 with no
 * fallback).
 *
 * @param events event name (sounds.json key without harbor:, e.g. {@code fishing.cast}) → settings
 * @param currency sounds played to the player when a currency changes (currency → cue)
 */
public record SoundTable(Map<String, Event> events, Map<Currency, CurrencyCue> currency) {

    public static final ContentType.Single<SoundTable> TYPE =
            new ContentType.Single<>("sounds", "sounds.yml", false, SoundTable::parse);

    /** Sound category names (same names as Adventure Sound.Source, lowercase). */
    public static final Set<String> SOURCES =
            Set.of(
                    "master", "music", "record", "weather", "block", "hostile", "neutral", "player",
                    "ambient", "voice", "ui");

    /** Resource location format ({@code minecraft:entity.player.splash}). */
    public static final Pattern KEY = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");

    private static final Pattern EVENT = Pattern.compile("[a-z0-9_]+(\\.[a-z0-9_]+)*");

    public static final SoundTable EMPTY = new SoundTable(Map.of(), Map.of());

    public SoundTable {
        events = Map.copyOf(events);
        currency = Map.copyOf(currency);
    }

    /**
     * Settings of one event.
     *
     * @param source sound category ({@link #SOURCES})
     * @param volume playback volume (positional sounds are audible up to 16 × volume blocks)
     * @param pitch playback pitch
     * @param loopTicks for looping sounds, the replay interval (ticks, the sound length). 0 means
     *     not a looping sound
     * @param fallback vanilla sound played when the pack sound cannot be used (null = no sound)
     */
    public record Event(
            String source,
            float volume,
            float pitch,
            int loopTicks,
            @Nullable String fallback,
            float fallbackVolume,
            float fallbackPitch) {

        public static final Event DEFAULT = new Event("master", 1f, 1f, 0, null, 1f, 1f);
    }

    /**
     * Currency change sound.
     *
     * @param sound sound key to play ({@code harbor:ui.coin})
     * @param reasons only for these reasons (empty = all reasons)
     * @param except not played for these reasons (when another effect already plays a sound)
     * @param gainOnly only on increase
     */
    public record CurrencyCue(
            String sound, Set<LedgerReason> reasons, Set<LedgerReason> except, boolean gainOnly) {

        public CurrencyCue {
            reasons = Set.copyOf(reasons);
            except = Set.copyOf(except);
        }

        public boolean matches(LedgerReason reason, long delta) {
            if (delta == 0 || (gainOnly && delta < 0) || except.contains(reason)) {
                return false;
            }
            return reasons.isEmpty() || reasons.contains(reason);
        }
    }

    public Optional<Event> event(String name) {
        return Optional.ofNullable(events.get(name));
    }

    public Optional<CurrencyCue> cue(Currency currency) {
        return Optional.ofNullable(this.currency.get(currency));
    }

    static SoundTable parse(YamlNode node, ParseContext ctx) {
        node.allowOnly(Set.of("events", "currency"));
        Map<String, Event> events = new LinkedHashMap<>();
        node.get("events")
                .ifPresent(
                        list ->
                                list.entries()
                                        .forEach(
                                                (name, value) -> {
                                                    if (!EVENT.matcher(name).matches()) {
                                                        value.error(
                                                                "이벤트 이름은 소문자·숫자·밑줄을 점으로 이은 형식이어야 합니다: "
                                                                        + name);
                                                        return;
                                                    }
                                                    events.put(name, event(value));
                                                }));
        Map<Currency, CurrencyCue> currency = new LinkedHashMap<>();
        node.get("currency")
                .ifPresent(
                        map ->
                                map.entries()
                                        .forEach(
                                                (name, value) -> {
                                                    Currency c = currencyOf(name);
                                                    if (c == null) {
                                                        value.error("알 수 없는 재화: " + name);
                                                        return;
                                                    }
                                                    currency.put(c, cue(value));
                                                }));
        return new SoundTable(events, currency);
    }

    private static Event event(YamlNode node) {
        node.allowOnly(
                Set.of(
                        "source",
                        "volume",
                        "pitch",
                        "loop",
                        "fallback",
                        "fallback_volume",
                        "fallback_pitch"));
        String source = node.string("source", "master").toLowerCase(Locale.ROOT);
        if (!SOURCES.contains(source)) {
            node.get("source").orElse(node).error("알 수 없는 소리 분류: " + source + " (" + SOURCES + ")");
            source = "master";
        }
        float volume = (float) positive(node, "volume", 1);
        float pitch = (float) pitch(node, "pitch", 1);
        int loop = node.integer("loop", 0, 0, 20 * 600); // Up to 10 minutes (background music)
        String fallback = node.optString("fallback").orElse(null);
        if (fallback != null && !KEY.matcher(fallback).matches()) {
            node.get("fallback").orElse(node).error("소리 키 형식이 아닙니다: " + fallback);
            fallback = null;
        }
        float fallbackVolume = (float) positive(node, "fallback_volume", volume);
        float fallbackPitch = (float) pitch(node, "fallback_pitch", pitch);
        return new Event(source, volume, pitch, loop, fallback, fallbackVolume, fallbackPitch);
    }

    private static CurrencyCue cue(YamlNode node) {
        node.allowOnly(Set.of("sound", "reasons", "except", "gain_only"));
        String sound = node.string("sound");
        if (!sound.isEmpty() && !KEY.matcher(sound).matches()) {
            node.get("sound").orElse(node).error("소리 키 형식이 아닙니다: " + sound);
        }
        return new CurrencyCue(
                sound,
                reasons(node, "reasons"),
                reasons(node, "except"),
                node.bool("gain_only", false));
    }

    private static Set<LedgerReason> reasons(YamlNode node, String key) {
        Set<LedgerReason> out = new LinkedHashSet<>();
        for (YamlNode item : node.list(key)) {
            String name = item.asString().toUpperCase(Locale.ROOT);
            try {
                out.add(LedgerReason.valueOf(name));
            } catch (IllegalArgumentException e) {
                item.error("알 수 없는 원장 사유: " + item.asString());
            }
        }
        return out;
    }

    private static @Nullable Currency currencyOf(String name) {
        try {
            return Currency.valueOf(name.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static double positive(YamlNode node, String key, double fallback) {
        double value = node.number(key, fallback);
        if (value <= 0) {
            node.get(key).orElse(node).error(key + "는 0보다 커야 합니다");
            return fallback;
        }
        return value;
    }

    /** Minecraft pitch is 0.5~2.0. */
    private static double pitch(YamlNode node, String key, double fallback) {
        double value = node.number(key, fallback);
        if (value < 0.5 || value > 2.0) {
            node.get(key).orElse(node).error(key + "는 0.5~2.0이어야 합니다");
            return fallback;
        }
        return value;
    }
}
