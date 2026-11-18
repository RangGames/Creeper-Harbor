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
 * sounds.yml: 리소스팩 소리 이벤트마다 분류·음량·반복 간격·바닐라 대체 소리 ('15. 사운드' 4·5장, 추가 제안). Core {@code
 * HarborSounds}가 이 표로 소리를 고른다. 파일이 없으면 빈 표 (모든 이벤트가 기본값 master·1·1, 대체 소리 없음).
 *
 * @param events 이벤트 이름(harbor: 뺀 sounds.json 키, 예 {@code fishing.cast}) → 설정
 * @param currency 재화가 바뀔 때 본인에게 낼 소리 (재화 → 신호)
 */
public record SoundTable(Map<String, Event> events, Map<Currency, CurrencyCue> currency) {

    public static final ContentType.Single<SoundTable> TYPE =
            new ContentType.Single<>("sounds", "sounds.yml", false, SoundTable::parse);

    /** 소리 분류 이름 (Adventure Sound.Source와 같은 이름, 소문자). */
    public static final Set<String> SOURCES =
            Set.of(
                    "master", "music", "record", "weather", "block", "hostile", "neutral", "player",
                    "ambient", "voice", "ui");

    /** 리소스 위치 형식 ({@code minecraft:entity.player.splash}). */
    public static final Pattern KEY = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");

    private static final Pattern EVENT = Pattern.compile("[a-z0-9_]+(\\.[a-z0-9_]+)*");

    public static final SoundTable EMPTY = new SoundTable(Map.of(), Map.of());

    public SoundTable {
        events = Map.copyOf(events);
        currency = Map.copyOf(currency);
    }

    /**
     * 이벤트 하나의 설정.
     *
     * @param source 소리 분류 ({@link #SOURCES})
     * @param volume 재생 음량 (위치 소리는 16 × volume 블록까지 들린다)
     * @param pitch 재생 피치
     * @param loopTicks 반복 소리면 다시 재생하는 간격(틱, 소리 길이). 0이면 반복 소리가 아니다
     * @param fallback 팩 소리를 쓸 수 없을 때 낼 바닐라 소리 (null = 소리 없음)
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
     * 재화 변경 소리.
     *
     * @param sound 낼 소리 키 ({@code harbor:ui.coin})
     * @param reasons 이 사유일 때만 (비면 모든 사유)
     * @param except 이 사유면 내지 않음 (다른 연출이 이미 소리를 내는 경우)
     * @param gainOnly 늘어날 때만
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
        int loop = node.integer("loop", 0, 0, 20 * 60);
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

    /** 마인크래프트 피치는 0.5~2.0. */
    private static double pitch(YamlNode node, String key, double fallback) {
        double value = node.number(key, fallback);
        if (value < 0.5 || value > 2.0) {
            node.get(key).orElse(node).error(key + "는 0.5~2.0이어야 합니다");
            return fallback;
        }
        return value;
    }
}
