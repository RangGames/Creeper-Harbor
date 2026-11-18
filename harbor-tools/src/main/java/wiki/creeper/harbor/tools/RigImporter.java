package wiki.creeper.harbor.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.Reader;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.rig.Quat;
import wiki.creeper.harbor.common.rig.Vec3;
import wiki.creeper.harbor.common.rig.Xform;

/**
 * 리소스팩 어종 리그 JSON(harbor_rig_v1)을 플러그인 리그 형식(content/rigs/fish/&lt;이름&gt;.yml)으로 바꾼다 ({@code
 * harbor-tools rig-import}, 추가 제안).
 *
 * <p>원본 키프레임(Blockbench 오일러·catmullrom)을 다시 계산하지 않고 원본의 {@code baked.frames}(20tps, 엔티티 기준 본별
 * translation·left_rotation·scale)를 그대로 옮긴다. 프레임의 월드 변환을 부모 본 기준 지역 변환으로 풀고, 회전은 기본 자세 오일러 각에 더할
 * 값(X → Y → Z 순서, 프레임 사이 각이 튀지 않게 이어 붙임)으로 바꿔 고른 간격 샘플 채널({@code {step, v}})에 넣는다. 서버는 샘플 시각에서 원본
 * 프레임과 같은 자세를 다시 만든다 (반올림 오차만 남는다).
 *
 * <ul>
 *   <li>모델이 없는 본(root 등)은 베이크 프레임에 값이 없어 빼고, 그 자식은 모델이 있는 가장 가까운 조상(없으면 리그 원점)에 붙인다.
 *   <li>기본 자세는 원본 {@code bones[].rest}(월드)를 같은 방식으로 지역 변환으로 푼다.
 *   <li>샘플 간격 step은 서버 굽기 간격(balance.yml rig.fps → 20/fps틱)과 맞춘다. 반복 동작의 마지막 샘플(length틱)은 첫 프레임, 한
 *       번 재생 동작은 마지막 프레임을 쓴다.
 *   <li>item_display 뒤집힘은 플러그인 기본(flip: true, 오른쪽 회전 Y 180°)을 그대로 둔다. 본 모델은 머리가 −Z인 Blockbench 좌표
 *       그대로라 그 보정이 있어야 원본과 같은 방향으로 그려진다.
 * </ul>
 */
final class RigImporter {

    /** 회전 채널을 뺄 기준 (도). 원본이 소수 4자리라 그보다 작은 흔들림은 반올림 잡음이다. */
    private static final double ROT_EPS = 0.05;

    /** 이동 채널을 뺄 기준 (블록). */
    private static final double POS_EPS = 0.0005;

    /** 크기 채널을 뺄 기준. */
    private static final double SCALE_EPS = 0.001;

    /** 채널 값 소수 자리: 회전 0.1°, 위치·크기 0.001 (서버 압축 허용 오차 1.2°·0.005블록보다 충분히 작다). */
    private static final int ROT_DIGITS = 1;

    private static final int POS_DIGITS = 3;

    private static final String HEADER =
            """
            # 리소스팩 어종 리그(harbor_rig_v1)에서 harbor-tools rig-import로 만든 파일. 직접 고치지 말고 다시 변환한다 (./gradlew rigImport -Prigs=<리그 폴더>).
            # 동작은 원본 베이크 프레임(20tps)을 고른 틱 간격(step) 샘플로 옮긴 것이다. 회전은 기본 자세 오일러 각(X → Y → Z)에 더하는 값(도), 위치는 pivot에 더하는 값(블록).
            """;

    /** 변환 결과 하나. */
    record Converted(String name, String yaml, int bones, int animations, List<String> events) {}

    private RigImporter() {}

    /** rigsDir의 *.json을 모두 바꿔 contentDir/rigs/fish/&lt;이름&gt;.yml로 쓴다. 그 폴더의 예전 yml은 지운다. */
    static List<Converted> run(Path rigsDir, Path contentDir, int step) throws IOException {
        List<Path> sources;
        try (Stream<Path> files = Files.list(rigsDir)) {
            sources =
                    files.filter(p -> p.getFileName().toString().endsWith(".json"))
                            .sorted()
                            .toList();
        }
        if (sources.isEmpty()) {
            throw new IOException("리그 JSON이 없습니다: " + rigsDir);
        }
        List<Converted> out = new ArrayList<>();
        for (Path source : sources) {
            JsonObject json;
            try (Reader reader = Files.newBufferedReader(source, StandardCharsets.UTF_8)) {
                json = JsonParser.parseReader(reader).getAsJsonObject();
            }
            try {
                out.add(convert(json, step));
            } catch (RuntimeException e) {
                throw new IOException(
                        source.getFileName() + ": " + String.valueOf(e.getMessage()), e);
            }
        }
        Path target = contentDir.resolve("rigs").resolve("fish");
        Files.createDirectories(target);
        try (Stream<Path> old = Files.list(target)) {
            for (Path p : old.filter(p -> p.getFileName().toString().endsWith(".yml")).toList()) {
                Files.delete(p);
            }
        }
        for (Converted c : out) {
            Files.writeString(target.resolve(c.name() + ".yml"), c.yaml(), StandardCharsets.UTF_8);
        }
        return out;
    }

    // ------------------------------------------------------------------ 변환

    private record SrcBone(
            String name, @Nullable String parent, @Nullable String model, Xform rest) {}

    /** 출력 본: 모델 있는 본만, 부모는 모델 있는 가장 가까운 조상. */
    private record OutBone(
            String name,
            @Nullable String parent,
            String model,
            Vec3 pivot,
            Vec3 rotation,
            Vec3 scale) {}

    static Converted convert(JsonObject json, int step) {
        if (step < 1 || step > 20) {
            throw new IllegalArgumentException("step은 1~20이어야 합니다: " + step);
        }
        String format = string(json, "format");
        if (!format.equals("harbor_rig_v1")) {
            throw new IllegalArgumentException("모르는 형식: " + format);
        }
        String id = string(json, "id");
        String prefix = "harbor:fish/";
        if (!id.startsWith(prefix)) {
            throw new IllegalArgumentException("id가 harbor:fish/로 시작하지 않습니다: " + id);
        }
        String name = id.substring(prefix.length());

        Map<String, SrcBone> src = new LinkedHashMap<>();
        for (JsonElement e : json.getAsJsonArray("bones")) {
            JsonObject b = e.getAsJsonObject();
            String boneName = string(b, "name");
            String parent = optString(b, "parent");
            if (parent != null && !src.containsKey(parent)) {
                throw new IllegalArgumentException(
                        "본 '" + boneName + "'의 부모 '" + parent + "'가 앞에 없습니다");
            }
            src.put(
                    boneName,
                    new SrcBone(
                            boneName,
                            parent,
                            optString(b, "item_model"),
                            xform(b.getAsJsonObject("rest"))));
        }

        // 출력 본과 기본 자세 (지역 변환, 반올림한 값을 기준으로 채널을 계산한다)
        List<OutBone> bones = new ArrayList<>();
        for (SrcBone b : src.values()) {
            String model = b.model();
            if (model == null) {
                continue;
            }
            String parent = modelAncestor(src, b);
            SrcBone parentBone = parent == null ? null : src.get(parent);
            Xform local = relative(parentBone == null ? null : parentBone.rest(), b.rest());
            OutBone out =
                    new OutBone(
                            b.name(),
                            parent,
                            model,
                            round(local.translation(), 4),
                            round(local.rotation().toEuler(), 2),
                            round(local.scale(), 4));
            bones.add(out);
        }
        if (bones.isEmpty()) {
            throw new IllegalArgumentException("item_model이 있는 본이 없습니다");
        }

        StringBuilder y = new StringBuilder();
        y.append(HEADER);
        y.append("- id: rig/fish/").append(name).append('\n');
        y.append("  forward: -z\n");
        double length =
                json.has("model_length_blocks") ? json.get("model_length_blocks").getAsDouble() : 0;
        if (length > 0) {
            y.append("  length: ").append(num(length, 4)).append('\n');
        }
        JsonObject animations = json.getAsJsonObject("animations");
        if (animations.has("swim")) {
            y.append("  default_animation: swim\n");
        }
        y.append("  bones:\n");
        for (OutBone b : bones) {
            y.append("    - {name: ").append(b.name());
            if (b.parent() != null) {
                y.append(", parent: ").append(b.parent());
            }
            if (!isZero(b.pivot(), 0)) {
                y.append(", pivot: ").append(vec(b.pivot(), 4));
            }
            if (!isZero(b.rotation(), 0)) {
                y.append(", rotation: ").append(vec(b.rotation(), 2));
            }
            if (!isOne(b.scale(), 0)) {
                y.append(", scale: ").append(vec(b.scale(), 4));
            }
            y.append(", part: {item_model: \"").append(b.model()).append("\"}}\n");
        }

        List<String> events = new ArrayList<>();
        if (!animations.isEmpty()) {
            y.append("  animations:\n");
        }
        for (Map.Entry<String, JsonElement> entry : animations.entrySet()) {
            String anim = entry.getKey();
            JsonObject a = entry.getValue().getAsJsonObject();
            animation(y, anim, a, bones, step, events);
        }
        return new Converted(name, y.toString(), bones.size(), animations.size(), events);
    }

    private static void animation(
            StringBuilder y,
            String anim,
            JsonObject a,
            List<OutBone> bones,
            int step,
            List<String> events) {
        int length = (int) Math.round(a.get("length").getAsDouble() * 20);
        if (length < 1) {
            throw new IllegalArgumentException("동작 '" + anim + "'의 길이가 1틱보다 짧습니다");
        }
        // 서버가 굽는 간격과 샘플 간격이 맞도록, 길이가 step으로 안 나뉘면 1틱 간격으로
        int s = length % step == 0 ? step : 1;
        boolean loop = !"once".equals(optString(a, "loop"));
        JsonObject baked = a.getAsJsonObject("baked");
        if (baked.get("tps").getAsInt() != 20) {
            throw new IllegalArgumentException("동작 '" + anim + "'의 베이크 tps가 20이 아닙니다");
        }
        JsonArray frames = baked.getAsJsonArray("frames");
        if (frames.isEmpty()) {
            throw new IllegalArgumentException("동작 '" + anim + "'에 베이크 프레임이 없습니다");
        }
        int samples = length / s + 1;

        // [본][샘플] 채널 값
        int n = bones.size();
        Vec3[][] rot = new Vec3[n][samples];
        Vec3[][] pos = new Vec3[n][samples];
        Vec3[][] scl = new Vec3[n][samples];
        Vec3[] lastEuler = new Vec3[n];
        for (int k = 0; k < n; k++) {
            lastEuler[k] = bones.get(k).rotation();
        }
        for (int i = 0; i < samples; i++) {
            int tick = i * s;
            int index =
                    tick < frames.size() ? tick : loop ? tick % frames.size() : frames.size() - 1;
            JsonObject frame = frames.get(index).getAsJsonObject();
            for (int k = 0; k < n; k++) {
                OutBone b = bones.get(k);
                Xform world = frameXform(frame, b.name(), anim, index);
                String parent = b.parent();
                Xform local =
                        relative(
                                parent == null ? null : frameXform(frame, parent, anim, index),
                                world);
                Vec3 euler = local.rotation().eulerNear(lastEuler[k]);
                lastEuler[k] = euler;
                rot[k][i] = euler.sub(b.rotation());
                pos[k][i] = local.translation().sub(b.pivot());
                Vec3 ls = local.scale();
                Vec3 rs = b.scale();
                scl[k][i] = new Vec3(ls.x() / rs.x(), ls.y() / rs.y(), ls.z() / rs.z());
            }
        }

        y.append("    ").append(anim).append(":\n");
        y.append("      length: ").append(length).append('\n');
        y.append("      mode: ").append(loop ? "loop" : "once").append('\n');
        JsonArray ev = a.has("events") ? a.getAsJsonArray("events") : new JsonArray();
        if (!ev.isEmpty()) {
            y.append("      events:\n");
            for (JsonElement e : ev) {
                JsonObject o = e.getAsJsonObject();
                int t = (int) Math.round(o.get("t").getAsDouble() * 20);
                String eventName = string(o, "name");
                y.append("        - {t: ")
                        .append(Math.max(0, Math.min(length, t)))
                        .append(", name: ")
                        .append(eventName)
                        .append("}\n");
                events.add(anim + "@" + t + ":" + eventName);
            }
        }
        StringBuilder channels = new StringBuilder();
        for (int k = 0; k < n; k++) {
            StringBuilder one = new StringBuilder();
            channel(one, "rotation", rot[k], s, ROT_DIGITS, Vec3.ZERO, ROT_EPS);
            channel(one, "position", pos[k], s, POS_DIGITS, Vec3.ZERO, POS_EPS);
            channel(one, "scale", scl[k], s, POS_DIGITS, Vec3.ONE, SCALE_EPS);
            if (!one.isEmpty()) {
                channels.append("        ").append(bones.get(k).name()).append(":\n").append(one);
            }
        }
        if (!channels.isEmpty()) {
            y.append("      bones:\n").append(channels);
        }
    }

    private static void channel(
            StringBuilder y,
            String key,
            Vec3[] values,
            int step,
            int digits,
            Vec3 identity,
            double eps) {
        boolean moves = false;
        for (Vec3 v : values) {
            if (v.maxDiff(identity) > eps) {
                moves = true;
                break;
            }
        }
        if (!moves) {
            return;
        }
        y.append("          ").append(key).append(": {step: ").append(step).append(", v: [");
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                y.append(", ");
            }
            Vec3 v = values[i];
            y.append(num(v.x(), digits))
                    .append(", ")
                    .append(num(v.y(), digits))
                    .append(", ")
                    .append(num(v.z(), digits));
        }
        y.append("]}\n");
    }

    // ------------------------------------------------------------------ 변환 수학

    /** child(월드) = parent(월드) ∘ 결과. parent가 null이면 리그 원점. */
    static Xform relative(@Nullable Xform parent, Xform child) {
        if (parent == null) {
            return child;
        }
        Quat inv = parent.rotation().conjugate();
        Vec3 d = inv.rotate(child.translation().sub(parent.translation()));
        Vec3 ps = parent.scale();
        Vec3 cs = child.scale();
        return new Xform(
                new Vec3(d.x() / ps.x(), d.y() / ps.y(), d.z() / ps.z()),
                inv.mul(child.rotation()).normalize(),
                new Vec3(cs.x() / ps.x(), cs.y() / ps.y(), cs.z() / ps.z()));
    }

    private static @Nullable String modelAncestor(Map<String, SrcBone> src, SrcBone bone) {
        String p = bone.parent();
        while (p != null) {
            SrcBone parent = src.get(p);
            if (parent == null) {
                return null;
            }
            if (parent.model() != null) {
                return p;
            }
            p = parent.parent();
        }
        return null;
    }

    private static Xform frameXform(JsonObject frame, String bone, String anim, int index) {
        JsonObject o = frame.getAsJsonObject(bone);
        if (o == null) {
            throw new IllegalArgumentException(
                    "동작 '" + anim + "' 프레임 " + index + "에 본 '" + bone + "'가 없습니다");
        }
        return xform(o);
    }

    private static Xform xform(JsonObject o) {
        JsonArray t = o.getAsJsonArray("translation");
        JsonArray q = o.getAsJsonArray("left_rotation");
        @Nullable JsonArray s = o.has("scale") ? o.getAsJsonArray("scale") : null;
        return new Xform(
                new Vec3(t.get(0).getAsDouble(), t.get(1).getAsDouble(), t.get(2).getAsDouble()),
                new Quat(
                                q.get(0).getAsDouble(),
                                q.get(1).getAsDouble(),
                                q.get(2).getAsDouble(),
                                q.get(3).getAsDouble())
                        .normalize(),
                s == null
                        ? Vec3.ONE
                        : new Vec3(
                                s.get(0).getAsDouble(),
                                s.get(1).getAsDouble(),
                                s.get(2).getAsDouble()));
    }

    // ------------------------------------------------------------------ 출력 도우미

    private static String string(JsonObject o, String key) {
        String v = optString(o, key);
        if (v == null) {
            throw new IllegalArgumentException("필드 '" + key + "'가 없습니다");
        }
        return v;
    }

    private static @Nullable String optString(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e == null || e.isJsonNull() ? null : e.getAsString();
    }

    private static Vec3 round(Vec3 v, int digits) {
        return new Vec3(roundNum(v.x(), digits), roundNum(v.y(), digits), roundNum(v.z(), digits));
    }

    private static double roundNum(double v, int digits) {
        return new BigDecimal(v).setScale(digits, RoundingMode.HALF_UP).doubleValue();
    }

    private static boolean isZero(Vec3 v, double eps) {
        return v.maxDiff(Vec3.ZERO) <= eps;
    }

    private static boolean isOne(Vec3 v, double eps) {
        return v.maxDiff(Vec3.ONE) <= eps;
    }

    private static String vec(Vec3 v, int digits) {
        return "["
                + num(v.x(), digits)
                + ", "
                + num(v.y(), digits)
                + ", "
                + num(v.z(), digits)
                + "]";
    }

    /** 소수 digits자리, 뒤 0과 −0 정리. */
    static String num(double v, int digits) {
        BigDecimal d =
                new BigDecimal(v).setScale(digits, RoundingMode.HALF_UP).stripTrailingZeros();
        if (d.signum() == 0) {
            return "0";
        }
        return d.toPlainString();
    }
}
