package wiki.creeper.harbor.quest.content;

import java.util.Set;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.content.ParseContext;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.common.id.NpcId;
import wiki.creeper.harbor.common.id.RegionId;
import wiki.creeper.harbor.common.model.Quality;
import wiki.creeper.harbor.common.yaml.YamlNode;

/**
 * 퀘스트 목표 ('11. 데이터 설계' 2.6 + 2.9 추가 필드).
 *
 * @param key 목표 고유 키. 리로드 때 진행을 이어 붙이는 기준 (없으면 순서 번호 "o<n>")
 * @param type 목표 유형 (catch · deliver · visit · talk · donate · codex · job_level · job_tier · boss
 *     · chart_percent · use_item · flag · cutscene · affinity · reputation, 다른 모듈이 등록한 유형)
 * @param count 필요한 수. codex·chart_percent는 달성률(%), job_level·job_tier·affinity·reputation은 도달할 값
 * @param qualityMin 품질 하한 (catch·deliver·donate)
 * @param sizeMin 크기 하한 cm (catch·deliver)
 * @param region 포획 지역 조건 (catch)
 * @param npc deliver·talk 대상 (없으면 end_npc)
 * @param source catch에 통발 포함 여부 (기본 rod)
 * @param waypoint 추적 표시 위치
 * @param text 목표 문구 (비우면 유형별 기본 문구)
 * @param scope codex 범위 (season · total)
 * @param node talk 목표: 이 대화 노드에서 quest:talk 액션이 실행돼야 한다 (비우면 NPC 클릭)
 * @param server flag 목표: 서버 플래그인지
 */
public record Objective(
        String key,
        String type,
        Target target,
        int count,
        @Nullable Quality qualityMin,
        double sizeMin,
        @Nullable RegionId region,
        @Nullable NpcId npc,
        CatchFilter source,
        @Nullable Waypoint waypoint,
        @Nullable String text,
        String scope,
        @Nullable String node,
        boolean server) {

    /**
     * 추적 표시 위치. NPC면 NPC의 현재 위치(떠돌이는 이동마다 갱신), 아니면 좌표.
     *
     * @param world 콘텐츠 월드 ID (비우면 플레이어가 있는 월드)
     */
    public record Waypoint(
            @Nullable NpcId npc, @Nullable String world, double x, double y, double z) {}

    /** 기본 목표 유형. 다른 모듈은 ObjectiveTypeRegistry로 더한다. */
    public static final Set<String> BUILTIN =
            Set.of(
                    "catch",
                    "deliver",
                    "visit",
                    "talk",
                    "donate",
                    "codex",
                    "job_level",
                    "job_tier",
                    "boss",
                    "chart_percent",
                    "use_item",
                    "flag",
                    "cutscene",
                    "affinity",
                    "reputation");

    /** 진행 값을 "도달한 값"으로 두는 유형 (세지 않고 현재 값으로 덮어쓴다). */
    public static final Set<String> GAUGES =
            Set.of("codex", "job_level", "job_tier", "chart_percent", "affinity", "reputation");

    private static final Pattern KEY = Pattern.compile("[a-z0-9_]{1,32}");
    private static final Pattern TYPE = Pattern.compile("[a-z0-9_]{1,32}");
    private static final Pattern FLAG = Pattern.compile("[a-z0-9_]+(/[a-z0-9_]+)*");

    private static final Set<String> FIELDS =
            Set.of(
                    "key",
                    "type",
                    "target",
                    "count",
                    "quality_min",
                    "size_min",
                    "region",
                    "npc",
                    "source",
                    "waypoint",
                    "text",
                    "scope",
                    "node",
                    "server");

    /** 지금 수량을 세는 유형인지 (보고할 때 인벤토리를 보는 deliver 제외). */
    public boolean tracked() {
        return !type.equals("deliver");
    }

    public boolean gauge() {
        return GAUGES.contains(type);
    }

    /** 축약형에서 값이 대상이 아니라 수량인 유형 ({@code - job_level: 5}). */
    private static final Set<String> VALUE_IS_COUNT = Set.of("job_level", "job_tier", "reputation");

    /**
     * 목표 한 줄. 두 가지로 쓸 수 있다:
     *
     * <pre>{@code
     * - {key: mackerel, type: catch, target: fish/harbor_mackerel, count: 3}   # 기본형
     * - {catch: fish/harbor_mackerel, count: 3}                                # 축약형 (추가 제안)
     * - {talk: npc/bawi}
     * - {job_level: 5}                                                         # 값이 수량인 유형
     * }</pre>
     *
     * 축약형은 기본 유형 이름을 키로 쓰고 type·target을 생략한다. key를 비우면 유형·대상으로 만든다 (catch_harbor_mackerel). 기본형에서
     * key를 비우면 순서 번호 o&lt;n&gt;이다 (기존 진행과 맞추려고 바꾸지 않는다).
     */
    static Objective parse(YamlNode n, int index, ParseContext ctx) {
        String shorthand = shorthandType(n);
        if (shorthand == null) {
            n.allowOnly(FIELDS);
        } else {
            Set<String> allowed = new java.util.HashSet<>(FIELDS);
            allowed.remove("type");
            allowed.add(shorthand);
            n.allowOnly(allowed);
        }
        boolean valueIsCount = shorthand != null && VALUE_IS_COUNT.contains(shorthand);
        String shortValue =
                shorthand == null
                        ? ""
                        : n.get(shorthand).map(YamlNode::asString).orElse("").strip();
        if (shorthand != null && !valueIsCount && n.has("target")) {
            n.error("축약형(" + shorthand + ": …)에는 target을 따로 쓰지 않습니다");
        }
        if (valueIsCount && n.has("count")) {
            n.error("축약형(" + shorthand + ": 수)에는 count를 따로 쓰지 않습니다");
        }
        String type = shorthand != null ? shorthand : n.string("type");
        if (!TYPE.matcher(type).matches()) {
            n.error("목표 type 형식이 틀렸습니다: " + type);
        }
        String rawTarget =
                shorthand != null && !valueIsCount
                        ? (shortValue.isEmpty() ? "any" : shortValue)
                        : n.string("target", "any");
        String key =
                n.string(
                        "key",
                        shorthand != null
                                ? autoKey(type, valueIsCount ? "" : rawTarget)
                                : "o" + (index + 1));
        if (!KEY.matcher(key).matches()) {
            n.error("목표 key는 소문자·숫자·밑줄 1~32자여야 합니다: " + key);
        }
        Target target;
        if (type.equals("flag")) {
            if (!FLAG.matcher(rawTarget).matches()) {
                n.error("flag 목표의 target은 플래그 이름이어야 합니다: " + rawTarget);
            }
            target = Target.name(rawTarget);
        } else {
            YamlNode targetNode = n.get(shorthand != null ? shorthand : "target").orElse(n);
            target = Target.parse(rawTarget, targetNode.pos(), ctx, targetNode::error);
        }
        checkTarget(n, type, target);
        int count =
                valueIsCount
                        ? n.integer(java.util.Objects.requireNonNull(shorthand), 1, 1, 1_000_000)
                        : n.integer("count", 1, 1, 1_000_000);
        Quality quality = null;
        if (n.has("quality_min")) {
            int stars = n.integer("quality_min", 1, Quality.MIN, Quality.MAX);
            if (stars >= Quality.MIN && stars <= Quality.MAX) {
                quality = Quality.of(stars);
            }
        }
        RegionId region =
                n.get("region")
                        .flatMap(r -> r.asId(RegionId.PREFIX))
                        .map(
                                id -> {
                                    ctx.ref("region", id, n.pos());
                                    return new RegionId(id);
                                })
                        .orElse(null);
        NpcId npc =
                n.get("npc")
                        .flatMap(r -> r.asId(NpcId.PREFIX))
                        .map(
                                id -> {
                                    ctx.ref("npc", id, n.pos());
                                    return new NpcId(id);
                                })
                        .orElse(null);
        CatchFilter source =
                n.has("source")
                        ? n.enumValue("source", CatchFilter.class).orElse(CatchFilter.ROD)
                        : CatchFilter.ROD;
        Waypoint waypoint = n.get("waypoint").map(w -> waypoint(w, ctx)).orElse(null);
        String scope = n.string("scope", "season");
        if (!scope.equals("season") && !scope.equals("total")) {
            n.error("scope는 season 또는 total이어야 합니다: " + scope);
        }
        return new Objective(
                key,
                type,
                target,
                count,
                quality,
                n.number("size_min", 0),
                region,
                npc,
                source,
                waypoint,
                n.optString("text").orElse(null),
                scope,
                n.optString("node").orElse(null),
                n.bool("server", false));
    }

    /** type 없이 기본 유형 이름이 키로 하나 있으면 그 유형 (축약형). */
    static @Nullable String shorthandType(YamlNode n) {
        if (!n.isMap() || n.has("type")) {
            return null;
        }
        String found = null;
        for (String k : n.entries().keySet()) {
            if (BUILTIN.contains(k)) {
                if (found != null) {
                    n.error("목표 하나에 유형을 두 개 쓸 수 없습니다: " + found + ", " + k);
                    return found;
                }
                found = k;
            }
        }
        return found;
    }

    /** 축약형 기본 key: 유형_대상이름 (ID 마지막 부분, 영숫자·밑줄만, 32자까지). */
    public static String autoKey(String type, String rawTarget) {
        String t = rawTarget.strip().toLowerCase(java.util.Locale.ROOT);
        int slash = t.lastIndexOf('/');
        if (slash >= 0) {
            t = t.substring(slash + 1);
        }
        t = t.replaceAll("[^a-z0-9_]+", "_").replaceAll("_+", "_").replaceAll("^_|_$", "");
        String key = t.isEmpty() || t.equals("any") ? type : type + "_" + t;
        return key.length() > 32 ? key.substring(0, 32).replaceAll("_$", "") : key;
    }

    private static void checkTarget(YamlNode n, String type, Target target) {
        switch (type) {
            case "catch", "donate" -> {
                if (target.kind() == Target.Kind.ID && !target.isId("fish")) {
                    n.error(type + " 목표의 target은 어종 ID·grade:·region:·any여야 합니다");
                }
            }
            case "deliver" -> {
                if (target.kind() == Target.Kind.ID
                        && !target.isId("fish")
                        && !target.isId("item")) {
                    n.error("deliver 목표의 target은 어종·아이템 ID·grade:·any여야 합니다");
                }
            }
            case "visit" -> {
                if (target.kind() != Target.Kind.REGION && target.kind() != Target.Kind.POINT) {
                    n.error("visit 목표의 target은 region:<ID> 또는 point:…여야 합니다");
                }
            }
            case "talk", "affinity" -> {
                if (target.kind() != Target.Kind.ANY && !target.isId("npc")) {
                    n.error(type + " 목표의 target은 NPC ID여야 합니다 (비우면 npc 필드·end_npc)");
                }
            }
            case "cutscene" -> {
                if (!target.isId("cutscene")) {
                    n.error("cutscene 목표의 target은 컷씬 ID여야 합니다");
                }
            }
            case "boss" -> {
                if (target.kind() != Target.Kind.ANY && !target.isId("boss")) {
                    n.error("boss 목표의 target은 보스 ID 또는 any여야 합니다");
                }
            }
            case "use_item" -> {
                if (!target.isId("item")) {
                    n.error("use_item 목표의 target은 아이템 ID여야 합니다");
                }
            }
            case "codex", "chart_percent" -> {
                if (target.kind() != Target.Kind.ANY && target.kind() != Target.Kind.REGION) {
                    n.error(type + " 목표의 target은 region:<ID> 또는 any여야 합니다");
                }
            }
            default -> {
                // job_level·job_tier·reputation·flag, 다른 모듈 유형은 대상 형식을 따지지 않는다
            }
        }
    }

    private static Waypoint waypoint(YamlNode w, ParseContext ctx) {
        if (w.isMap()) {
            w.allowOnly(Set.of("npc", "world", "x", "y", "z"));
            NpcId npc =
                    w.get("npc")
                            .flatMap(r -> r.asId(NpcId.PREFIX))
                            .map(
                                    id -> {
                                        ctx.ref("npc", id, w.pos());
                                        return new NpcId(id);
                                    })
                            .orElse(null);
            return new Waypoint(
                    npc,
                    w.optString("world").orElse(null),
                    w.number("x", 0),
                    w.number("y", 0),
                    w.number("z", 0));
        }
        String raw = w.asString().strip();
        if (raw.startsWith("npc/") || raw.startsWith("harbor:npc/")) {
            HarborId id = w.asId(NpcId.PREFIX).orElse(HarborId.parse("npc/invalid"));
            ctx.ref("npc", id, w.pos());
            return new Waypoint(new NpcId(id), null, 0, 0, 0);
        }
        String[] parts = raw.split(",", -1);
        try {
            if (parts.length == 3) {
                return new Waypoint(
                        null,
                        null,
                        Double.parseDouble(parts[0].strip()),
                        Double.parseDouble(parts[1].strip()),
                        Double.parseDouble(parts[2].strip()));
            }
            if (parts.length == 4) {
                return new Waypoint(
                        null,
                        parts[0].strip(),
                        Double.parseDouble(parts[1].strip()),
                        Double.parseDouble(parts[2].strip()),
                        Double.parseDouble(parts[3].strip()));
            }
        } catch (NumberFormatException e) {
            // 아래 오류
        }
        w.error("waypoint는 NPC ID, \"x,y,z\", \"월드,x,y,z\" 또는 {world, x, y, z}여야 합니다: " + raw);
        return new Waypoint(null, null, 0, 0, 0);
    }
}
