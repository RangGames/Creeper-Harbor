package wiki.creeper.harbor.common.rig;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static wiki.creeper.harbor.common.rig.RigMathTest.assertVec;

import java.io.StringReader;
import java.util.List;
import org.junit.jupiter.api.Test;
import wiki.creeper.harbor.common.content.ParseContext;
import wiki.creeper.harbor.common.util.ValidationErrors;
import wiki.creeper.harbor.common.yaml.YamlNode;

class RigDefTest {

    private static final String MACKEREL =
            """
            id: rig/fish/test_mackerel
            length: 0.6
            default_animation: swim
            bones:
              - name: body
                part: {item_model: "harbor:rig/fish/test/body"}
              - name: tail
                parent: body
                pivot: [0, 0, 0.25]
                part: {item_model: "harbor:rig/fish/test/tail", offset: [0, 0, 0.1]}
                hitbox: {width: 0.4, height: 0.3}
            animations:
              swim:
                length: 20
                mode: loop
                bones:
                  tail:
                    rotation:
                      - {t: 0, v: [0, -30, 0], ease: ease_in_out}
                      - {t: 10, v: [0, 30, 0], ease: ease_in_out}
                      - {t: 20, v: [0, -30, 0]}
              flop:
                length: 10
                mode: once
                bones:
                  body:
                    position:
                      - {t: 0, v: [0, 0, 0]}
                      - {t: 5, v: [0, 0.5, 0]}
                      - {t: 10, v: [0, 0, 0]}
            """;

    static RigDef parse(String yaml, ValidationErrors errors) {
        YamlNode node =
                YamlNode.parse("rigs/test.yml", new StringReader(yaml), errors).orElseThrow();
        return RigDef.parse(node, new ParseContext());
    }

    static RigDef parseOk(String yaml) {
        ValidationErrors errors = new ValidationErrors();
        RigDef rig = parse(yaml, errors);
        assertTrue(errors.isEmpty(), errors::format);
        return rig;
    }

    @Test
    void parsesBonesPartsAndAnimations() {
        RigDef rig = parseOk(MACKEREL);
        assertEquals("harbor:rig/fish/test_mackerel", rig.id().toString());
        assertTrue(rig.forwardNegZ());
        assertEquals(2, rig.partCount());
        assertEquals(1, rig.hitboxCount());
        assertEquals(0, rig.bones().get(1).parent());
        RigDef.Part tail = rig.bones().get(1).part();
        assertNotNull(tail);
        assertEquals(RigDef.PartKind.ITEM, tail.kind());
        assertTrue(tail.flip(), "item 부품은 기본으로 뒤집힘 보정");
        assertEquals(RigDef.LoopMode.ONCE, rig.animation("flop").orElseThrow().mode());
        assertEquals("swim", rig.defaultAnimation());
    }

    @Test
    void hierarchyAndAnimationCompose() {
        RigDef rig = parseOk(MACKEREL);
        RigDef.Animation swim = rig.animation("swim").orElseThrow();

        // t=0: tail bone at Y −30°. Part offset (0,0,0.1) rotates with the bone
        Xform[] pose = RigSampler.pose(rig, swim, 0);
        Xform tailPart = RigSampler.part(pose[1], rig.bones().get(1).part());
        Vec3 expected = new Vec3(0, 0, 0.25).add(Quat.rotY(-30).rotate(new Vec3(0, 0, 0.1)));
        assertVec(expected, tailPart.translation(), 1e-9);

        // t=5: midpoint of ease_in_out = 0°
        Xform[] mid = RigSampler.pose(rig, swim, 5);
        assertEquals(0, mid[1].rotation().angleTo(Quat.IDENTITY), 1e-4);

        // Hitbox is at the bone origin
        assertVec(
                new Vec3(0, 0, 0.25),
                RigSampler.hitboxCenter(pose[1], rig.bones().get(1).hitbox()),
                1e-9);
    }

    @Test
    void parentMovesChildren() {
        RigDef rig = parseOk(MACKEREL);
        RigDef.Animation flop = rig.animation("flop").orElseThrow();
        Xform[] pose = RigSampler.pose(rig, flop, 5);
        assertVec(new Vec3(0, 0.5, 0), pose[0].translation(), 1e-9);
        assertVec(new Vec3(0, 0.5, 0.25), pose[1].translation(), 1e-9);
    }

    @Test
    void bakesAllAnimationsWithinTolerance() {
        RigDef rig = parseOk(MACKEREL);
        Xform.Tolerance tol = new Xform.Tolerance(0.005, 1.2, 0.005);
        BakedRig baked = RigBaker.bake(rig, new RigBaker.Settings(10, tol, 8));
        assertEquals(2, baked.parts().size());
        assertEquals(1, baked.hitboxes().size());
        BakedRig.BakedAnimation swim = baked.animation("swim").orElseThrow();
        assertEquals(11, swim.frameCount());
        // The body does not move, so the span between first and last is split only by the max gap
        assertEquals(3, swim.part(0).size());
        assertTrue(swim.keyCount() < 2 * swim.frameCount());
        RigDef.Animation def = rig.animation("swim").orElseThrow();
        for (int t = 0; t <= 20; t += 2) {
            Xform[] pose = RigSampler.pose(rig, def, t);
            Xform exact = RigSampler.part(pose[1], rig.bones().get(1).part());
            assertTrue(swim.part(1).sample(t).near(exact, tol), "t=" + t);
        }
        // The rest pose is a single key
        assertEquals(1, baked.rest().part(0).size());
        assertVec(new Vec3(0, 0, 0.25), baked.rest().hitbox(0, 0), 1e-9);
    }

    /**
     * All repository rigs (content/rigs, including converted rigs/fish) load, and baked keys match
     * the original poses within tolerance.
     */
    @Test
    void sampleContentRigsBakeWithinTolerance() throws java.io.IOException {
        java.nio.file.Path dir = java.nio.file.Path.of("..", "content", "rigs");
        org.junit.jupiter.api.Assumptions.assumeTrue(java.nio.file.Files.isDirectory(dir));
        Xform.Tolerance tol = new Xform.Tolerance(0.005, 1.2, 0.005);
        RigBaker.Settings settings = new RigBaker.Settings(10, tol, 8);
        int rigs = 0;
        try (var files = java.nio.file.Files.walk(dir)) {
            for (java.nio.file.Path file :
                    files.filter(f -> f.getFileName().toString().endsWith(".yml")).toList()) {
                ValidationErrors errors = new ValidationErrors();
                YamlNode root =
                        YamlNode.read(file, file.getFileName().toString(), errors).orElseThrow();
                for (YamlNode entry : root.asList()) {
                    RigDef rig = RigDef.parse(entry, new ParseContext());
                    assertTrue(errors.isEmpty(), errors::format);
                    BakedRig baked = RigBaker.bake(rig, settings);
                    for (RigDef.Animation def : rig.animations().values()) {
                        BakedRig.BakedAnimation anim = baked.animation(def.name()).orElseThrow();
                        for (int t = 0; t <= def.length(); t += 2) {
                            Xform[] pose = RigSampler.pose(rig, def, t);
                            for (int p = 0; p < baked.parts().size(); p++) {
                                BakedRig.PartRef ref = baked.parts().get(p);
                                Xform exact = RigSampler.part(pose[ref.bone()], ref.part());
                                assertTrue(
                                        anim.part(p).sample(t).near(exact, tol),
                                        rig.id() + " " + def.name() + " " + ref.name() + " t=" + t);
                            }
                        }
                    }
                    rigs++;
                }
            }
        }
        assertTrue(rigs > 0);
    }

    @Test
    void reportsErrorsWithoutThrowing() {
        ValidationErrors errors = new ValidationErrors();
        parse(
                """
                id: rig/fish/broken
                forward: up
                default_animation: missing
                bones:
                  - name: body
                    parent: nobody
                    part: {item_model: "a:b", block: "minecraft:stone"}
                  - name: body
                animations:
                  swim:
                    length: 0
                    bones:
                      ghost:
                        rotation: [{t: 0, v: [0, 0, 0]}]
                      body:
                        rotation:
                          - {t: 5, v: [0, 0, 0]}
                          - {t: 3, v: [0, 0]}
                """,
                errors);
        assertFalse(errors.isEmpty());
        String all = errors.format();
        List<String> expected =
                List.of(
                        "forward",
                        "default_animation",
                        "parent 'nobody'",
                        "item_model과 block",
                        "중복",
                        "length는 1틱",
                        "ghost",
                        "앞 키보다",
                        "세 값");
        for (String part : expected) {
            assertTrue(all.contains(part), () -> "'" + part + "' 오류가 없습니다:\n" + all);
        }
    }
}
