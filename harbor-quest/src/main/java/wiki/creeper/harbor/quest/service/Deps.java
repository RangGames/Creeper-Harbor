package wiki.creeper.harbor.quest.service;

import org.bukkit.Bukkit;
import wiki.creeper.harbor.collection.api.AquariumService;
import wiki.creeper.harbor.collection.api.CodexService;
import wiki.creeper.harbor.collection.api.HintService;
import wiki.creeper.harbor.fishing.api.FishingService;
import wiki.creeper.harbor.jobs.api.JobsService;
import wiki.creeper.harbor.npc.api.AffinityService;
import wiki.creeper.harbor.npc.api.CutsceneService;
import wiki.creeper.harbor.npc.api.DialogService;
import wiki.creeper.harbor.npc.api.NpcMarkerRegistry;
import wiki.creeper.harbor.npc.api.NpcService;
import wiki.creeper.harbor.npc.api.ReputationService;
import wiki.creeper.harbor.season.api.SeasonClock;
import wiki.creeper.harbor.season.api.SeasonServices;
import wiki.creeper.harbor.season.api.TitleService;

/**
 * Services of required dependency modules (docs 20 6.2). All are enabled before Quest, so they are
 * looked up once in enable.
 */
public record Deps(
        NpcService npcs,
        DialogService dialogs,
        NpcMarkerRegistry markers,
        CutsceneService cutscenes,
        AffinityService affinity,
        ReputationService reputation,
        FishingService fishing,
        CodexService codex,
        HintService hints,
        AquariumService aquarium,
        JobsService jobs,
        SeasonClock clock,
        TitleService titles) {

    public static Deps resolve() {
        return new Deps(
                require(NpcService.class),
                require(DialogService.class),
                require(NpcMarkerRegistry.class),
                require(CutsceneService.class),
                require(AffinityService.class),
                require(ReputationService.class),
                require(FishingService.class),
                require(CodexService.class),
                require(HintService.class),
                require(AquariumService.class),
                require(JobsService.class),
                SeasonServices.clock(),
                SeasonServices.titles());
    }

    private static <T> T require(Class<T> type) {
        T service = Bukkit.getServicesManager().load(type);
        if (service == null) {
            throw new IllegalStateException("필수 의존 서비스가 없습니다: " + type.getSimpleName());
        }
        return service;
    }
}
