package wiki.creeper.harbor.wildlife.sim;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import wiki.creeper.harbor.common.content.ContentSnapshot;
import wiki.creeper.harbor.common.content.type.FishDef;
import wiki.creeper.harbor.common.id.FishId;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.common.id.RegionId;
import wiki.creeper.harbor.common.id.RigId;
import wiki.creeper.harbor.common.rig.RigDef;
import wiki.creeper.harbor.wildlife.api.Medium;
import wiki.creeper.harbor.wildlife.content.BehaviourBook;
import wiki.creeper.harbor.wildlife.content.WildSpecies;
import wiki.creeper.harbor.wildlife.content.WildlifeBalance;

/**
 * 콘텐츠 스냅샷에서 만든 실행용 어종 표 (species.yml × fish/*.yml × rigs/*.yml × behaviours.yml). 변경 불가. 어종 정의나 리그가
 * 없는 항목은 빼고 problems에 적는다 (리로드 로그).
 */
public record ProfileIndex(
        Map<HarborId, SpeciesProfile> bySpecies,
        Map<FishId, SpeciesProfile> byFish,
        Map<RegionId, List<SpeciesProfile>> byRegion,
        List<String> problems) {

    public static final ProfileIndex EMPTY =
            new ProfileIndex(Map.of(), Map.of(), Map.of(), List.of());

    public ProfileIndex {
        bySpecies = Map.copyOf(bySpecies);
        byFish = Map.copyOf(byFish);
        byRegion = Map.copyOf(byRegion);
        problems = List.copyOf(problems);
    }

    public static ProfileIndex build(ContentSnapshot snapshot, WildlifeBalance bal) {
        BehaviourBook book = book(snapshot);
        Map<HarborId, RigDef> rigs = snapshot.all(RigDef.TYPE);
        Set<RigId> known = new HashSet<>();
        rigs.values().forEach(r -> known.add(r.id()));
        Map<HarborId, SpeciesProfile> bySpecies = new HashMap<>();
        Map<FishId, SpeciesProfile> byFish = new HashMap<>();
        Map<RegionId, List<SpeciesProfile>> byRegion = new HashMap<>();
        List<String> problems = new ArrayList<>();
        snapshot.all(WildSpecies.TYPE).values().stream()
                .sorted((a, b) -> a.id().compareTo(b.id()))
                .forEach(
                        s -> {
                            Optional<FishDef> fish = snapshot.get(FishDef.TYPE, s.fish().id());
                            if (fish.isEmpty()) {
                                problems.add(s.id() + ": 어종 " + s.fish() + " 없음");
                                return;
                            }
                            Optional<RigId> rig = SpeciesProfile.rigFor(s, fish.get(), known);
                            RigDef def = rig.map(r -> rigs.get(r.id())).orElse(null);
                            if (def == null) {
                                problems.add(
                                        s.id()
                                                + ": 리그 없음 (rig/fish/"
                                                + s.fish().id().name()
                                                + " · rig/wild/"
                                                + s.template().key()
                                                + ")");
                                return;
                            }
                            if (byFish.containsKey(s.fish())) {
                                problems.add(s.id() + ": 어종 " + s.fish() + " 중복, 건너뜀");
                                return;
                            }
                            SpeciesProfile p =
                                    SpeciesProfile.resolve(s, fish.get(), def, book, bal);
                            bySpecies.put(s.id(), p);
                            byFish.put(s.fish(), p);
                            byRegion.computeIfAbsent(fish.get().region(), r -> new ArrayList<>())
                                    .add(p);
                        });
        Map<RegionId, List<SpeciesProfile>> frozen = new HashMap<>();
        byRegion.forEach((k, v) -> frozen.put(k, List.copyOf(v)));
        return new ProfileIndex(bySpecies, byFish, frozen, problems);
    }

    /** behaviours.yml (형식이 아직 로드되지 않은 스냅샷이면 빈 표: 모듈 enable 직후). */
    public static BehaviourBook book(ContentSnapshot snapshot) {
        try {
            return snapshot.single(BehaviourBook.TYPE);
        } catch (IllegalStateException e) {
            return new BehaviourBook(Map.of(), Map.of());
        }
    }

    public List<SpeciesProfile> region(RegionId region) {
        return byRegion.getOrDefault(region, List.of());
    }

    /** 지역 어종이 쓰는 매질 (자연 스폰 가능한 어종만). */
    public Set<Medium> mediums(RegionId region) {
        Set<Medium> m = EnumSet.noneOf(Medium.class);
        for (SpeciesProfile p : region(region)) {
            if (p.naturalSpawn()) {
                m.add(p.medium());
            }
        }
        return m;
    }

    /** 매질 격자에 담을 비트 (바닥 개체는 물 칸). */
    public int gridMask(RegionId region) {
        int mask = 0;
        for (SpeciesProfile p : region(region)) {
            mask |= MediumField.bit(p.medium());
        }
        return mask;
    }
}
