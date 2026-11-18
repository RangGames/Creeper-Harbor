package wiki.creeper.harbor.wildlife.sim;

import java.util.List;
import java.util.Map;
import wiki.creeper.harbor.common.content.type.Balance;
import wiki.creeper.harbor.common.content.type.FishDef;
import wiki.creeper.harbor.common.id.FishId;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.common.id.RegionId;
import wiki.creeper.harbor.common.id.RigId;
import wiki.creeper.harbor.common.model.Grade;
import wiki.creeper.harbor.common.rig.Vec3;
import wiki.creeper.harbor.wildlife.api.Medium;
import wiki.creeper.harbor.wildlife.content.BehaviourBook;
import wiki.creeper.harbor.wildlife.content.BehaviourBook.ActionDef;
import wiki.creeper.harbor.wildlife.content.WildGroup;
import wiki.creeper.harbor.wildlife.content.WildTemplate;
import wiki.creeper.harbor.wildlife.content.WildlifeBalance;

/** 테스트용 어종·매질. */
final class Fixtures {

    static final RegionId HARBOR = RegionId.parse("region/harbor");
    static final WildlifeBalance BAL = new WildlifeBalance(Balance.of(Map.of()));

    static final ActionDef DART =
            new ActionDef("dart", WildGroup.ACTIVE, 1.6, 1, Vec3.ZERO, "bubble", 0, 0, true);
    static final ActionDef LEAP =
            new ActionDef(
                    "leap", WildGroup.ACTIVE, 0.3, 1, new Vec3(0, 0.65, 0), "splash", 0, 0, false);
    static final ActionDef TELEPORT =
            new ActionDef("teleport", WildGroup.ACTIVE, 2.0, 1, Vec3.ZERO, "portal", 4, 6, false);

    private Fixtures() {}

    static FishDef fish(
            String name, Grade grade, List<String> time, List<String> weather, List<String> area) {
        return new FishDef(
                FishId.parse("fish/" + name),
                1,
                name,
                List.of(),
                grade,
                HARBOR,
                area,
                time,
                weather,
                List.of(),
                List.of(),
                0,
                null,
                20,
                40,
                5,
                100,
                false,
                "harbor:fish/" + name,
                1);
    }

    static FishDef fish(String name) {
        return fish(name, Grade.COMMON, List.of(), List.of(), List.of());
    }

    static SpeciesProfile profile(FishDef fish, Medium medium, WildTemplate template) {
        WildGroup group = WildGroup.ACTIVE;
        return new SpeciesProfile(
                HarborId.parse("wild/" + fish.id().id().name()),
                fish,
                template,
                medium,
                group,
                BehaviourBook.DEFAULT_GROUPS.get(group),
                LEAP,
                DART,
                Map.of("dart", DART, "leap", LEAP, "teleport", TELEPORT),
                Map.of("swim", 20, "idle", 40, "dart", 10, "leap", 16),
                "swim",
                "idle",
                "dart",
                1.0,
                BAL.turnRate(template),
                BAL.pitchLimit(template),
                0,
                0,
                3,
                6,
                10,
                1.0,
                RigId.parse("rig/wild/fish"),
                2,
                0.4,
                0.25,
                1.0,
                -1,
                true);
    }

    static SpeciesProfile water(String name) {
        return profile(fish(name), Medium.WATER, WildTemplate.FISH);
    }

    /** y 범위 [lo, hi]가 매질인 무한 수평 판 (나머지는 막힘). */
    static MediumField slab(int bit, int lo, int hi) {
        return (x, y, z) -> y >= lo && y <= hi ? bit : 0;
    }

    /** 상자 [x0,x1]×[y0,y1]×[z0,z1] 안만 매질. */
    static MediumField box(int bit, int x0, int x1, int y0, int y1, int z0, int z1) {
        return (x, y, z) ->
                x >= x0 && x <= x1 && y >= y0 && y <= y1 && z >= z0 && z <= z1 ? bit : 0;
    }
}
