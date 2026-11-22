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
 * Runtime species table built from a content snapshot (species.yml × fish/*.yml × rigs/*.yml ×
 * behaviours.yml). Immutable. Entries without a species definition or rig are dropped and recorded
 * in problems (reload log).
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

    /**
     * behaviours.yml (empty table if the snapshot doesn't have the type loaded yet: right after
     * module enable).
     */
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

    /** Media used by a region's species (only naturally spawnable species). */
    public Set<Medium> mediums(RegionId region) {
        Set<Medium> m = EnumSet.noneOf(Medium.class);
        for (SpeciesProfile p : region(region)) {
            if (p.naturalSpawn()) {
                m.add(p.medium());
            }
        }
        return m;
    }

    /** Bits to store in the medium grid (floor fish use water cells). */
    public int gridMask(RegionId region) {
        int mask = 0;
        for (SpeciesProfile p : region(region)) {
            mask |= MediumField.bit(p.medium());
        }
        return mask;
    }
}
