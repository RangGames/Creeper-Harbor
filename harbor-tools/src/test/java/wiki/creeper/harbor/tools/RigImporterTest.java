package wiki.creeper.harbor.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import wiki.creeper.harbor.common.content.ParseContext;
import wiki.creeper.harbor.common.rig.BakedRig;
import wiki.creeper.harbor.common.rig.Quat;
import wiki.creeper.harbor.common.rig.RigBaker;
import wiki.creeper.harbor.common.rig.RigDef;
import wiki.creeper.harbor.common.rig.RigSampler;
import wiki.creeper.harbor.common.rig.Vec3;
import wiki.creeper.harbor.common.rig.Xform;
import wiki.creeper.harbor.common.util.ValidationErrors;
import wiki.creeper.harbor.common.yaml.YamlNode;

/** 리소스팩 리그 변환: 변환한 리그를 서버와 같은 코드로 다시 계산하면 원본 기본 자세·베이크 프레임과 같아야 한다. */
class RigImporterTest {

    private static final double POS_TOL = 0.002;
    private static final double ROT_TOL = 0.2;

    private static JsonObject sample() throws IOException {
        try (InputStream in =
                Objects.requireNonNull(
                        RigImporterTest.class.getResourceAsStream("/rig/sample_mackerel.json"))) {
            return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8))
                    .getAsJsonObject();
        }
    }

    private static RigDef parse(String yaml) {
        ValidationErrors errors = new ValidationErrors();
        YamlNode root =
                YamlNode.parse("rigs/fish/test.yml", new StringReader(yaml), errors).orElseThrow();
        RigDef rig = RigDef.TYPE.parser().parse(root.asList().getFirst(), new ParseContext());
        assertTrue(errors.isEmpty(), errors::format);
        return rig;
    }

    private static Xform xform(JsonObject o) {
        JsonArray t = o.getAsJsonArray("translation");
        JsonArray q = o.getAsJsonArray("left_rotation");
        JsonArray s = o.getAsJsonArray("scale");
        return new Xform(
                new Vec3(t.get(0).getAsDouble(), t.get(1).getAsDouble(), t.get(2).getAsDouble()),
                new Quat(
                                q.get(0).getAsDouble(),
                                q.get(1).getAsDouble(),
                                q.get(2).getAsDouble(),
                                q.get(3).getAsDouble())
                        .normalize(), // 원본은 소수 4자리라 길이가 1에서 조금 벗어난다
                new Vec3(s.get(0).getAsDouble(), s.get(1).getAsDouble(), s.get(2).getAsDouble()));
    }

    private static void assertPose(String what, Xform expected, Xform actual) {
        assertTrue(
                expected.translation().distance(actual.translation()) <= POS_TOL,
                () -> what + " 위치 " + expected.translation() + " ≠ " + actual.translation());
        assertTrue(
                expected.rotation().angleTo(actual.rotation()) <= ROT_TOL,
                () -> what + " 회전 " + expected.rotation() + " ≠ " + actual.rotation());
        assertTrue(
                expected.scale().maxDiff(actual.scale()) <= POS_TOL,
                () -> what + " 크기 " + expected.scale() + " ≠ " + actual.scale());
    }

    @Test
    void restPoseMatchesPackRest() throws IOException {
        JsonObject json = sample();
        RigDef rig = parse(RigImporter.convert(json, 1).yaml());
        assertEquals("harbor:rig/fish/harbor_mackerel", rig.id().toString());
        assertEquals(1.672, rig.length(), 1e-9);
        assertTrue(rig.forwardNegZ());
        Xform[] pose = RigSampler.pose(rig, null, 0);
        for (JsonElement e : json.getAsJsonArray("bones")) {
            JsonObject b = e.getAsJsonObject();
            if (b.get("item_model").isJsonNull()) {
                continue; // 모델 없는 본(root)은 빠진다
            }
            String name = b.get("name").getAsString();
            int index = boneIndex(rig, name);
            assertEquals(
                    b.get("item_model").getAsString(),
                    Objects.requireNonNull(rig.bones().get(index).part()).model());
            assertPose(name, xform(b.getAsJsonObject("rest")), pose[index]);
        }
        // 지느러미 기본 회전 [0, 25, −10]이 그대로 들어간다
        Vec3 fin = rig.bones().get(boneIndex(rig, "fin_pectoral_right")).rotation();
        assertEquals(25, fin.y(), 0.01);
        assertEquals(-10, fin.z(), 0.01);
    }

    @Test
    void sampledFramesMatchBakedFrames() throws IOException {
        JsonObject json = sample();
        RigImporter.Converted converted = RigImporter.convert(json, 1);
        RigDef rig = parse(converted.yaml());
        RigDef.Animation swim = rig.animation("swim").orElseThrow();
        assertEquals(2, swim.length());
        assertEquals(RigDef.LoopMode.LOOP, swim.mode());
        assertEquals(1, swim.events().size());
        assertEquals(new RigDef.Event(1, "ink"), swim.events().getFirst());
        assertEquals(1, converted.events().size());

        JsonArray frames =
                json.getAsJsonObject("animations")
                        .getAsJsonObject("swim")
                        .getAsJsonObject("baked")
                        .getAsJsonArray("frames");
        // 틱 0·1은 원본 프레임, 틱 2(반복 끝)는 첫 프레임
        int[][] checks = {{0, 0}, {1, 1}, {2, 0}};
        for (int[] check : checks) {
            Xform[] pose = RigSampler.pose(rig, swim, check[0]);
            JsonObject frame = frames.get(check[1]).getAsJsonObject();
            for (String bone : frame.keySet()) {
                assertPose(
                        bone + " 틱 " + check[0],
                        xform(frame.getAsJsonObject(bone)),
                        pose[boneIndex(rig, bone)]);
            }
        }

        // 서버 굽기(20fps, 압축 없음)도 같은 값을 낸다. 부품 Display 변환 = 뼈 월드 변환 (offset 없음)
        BakedRig baked =
                RigBaker.bake(rig, new RigBaker.Settings(20, new Xform.Tolerance(0, 0, 0), 1));
        BakedRig.BakedAnimation b = baked.animation("swim").orElseThrow();
        for (int p = 0; p < baked.parts().size(); p++) {
            String bone = baked.parts().get(p).name();
            JsonObject frame1 = frames.get(1).getAsJsonObject().getAsJsonObject(bone);
            assertPose(bone + " 구운 틱 1", xform(frame1), b.part(p).sample(1));
        }
    }

    @Test
    void stepMustDivideLengthOrFallsBackToOneTick() throws IOException {
        JsonObject json = sample();
        // 길이 2틱 → 간격 2 그대로, 간격 4는 나뉘지 않아 1틱 간격으로
        assertTrue(RigImporter.convert(json, 2).yaml().contains("{step: 2, v: ["));
        assertTrue(RigImporter.convert(json, 4).yaml().contains("{step: 1, v: ["));
        parse(RigImporter.convert(json, 2).yaml());
    }

    @Test
    void eulerRoundTrip() {
        Vec3[] angles = {
            new Vec3(0, 25, -10),
            new Vec3(30, -60, 120),
            new Vec3(-170, 10, 95),
            new Vec3(5, 89.9, 3)
        };
        for (Vec3 a : angles) {
            Quat q = Quat.euler(a);
            Quat back = Quat.euler(q.toEuler());
            assertTrue(q.angleTo(back) < 1e-3, () -> a + " → " + q.toEuler());
            // 이어 붙이기: 360° 넘어간 앞 값에 가까운 해를 고른다
            Vec3 near = q.eulerNear(a.add(new Vec3(360, 0, 0)));
            assertTrue(q.angleTo(Quat.euler(near)) < 1e-3);
            assertTrue(near.x() > 180 - 1e-6 || Math.abs(near.y()) > 89);
        }
    }

    @Test
    void numberFormatting() {
        assertEquals("0", RigImporter.num(-0.00001, 4));
        assertEquals("0.375", RigImporter.num(0.375, 4));
        assertEquals("-12.8", RigImporter.num(-12.799999, 2));
        assertEquals("3", RigImporter.num(3.0, 2));
    }

    private static int boneIndex(RigDef rig, String name) {
        for (int i = 0; i < rig.bones().size(); i++) {
            if (rig.bones().get(i).name().equals(name)) {
                return i;
            }
        }
        throw new AssertionError("뼈 없음: " + name);
    }
}
