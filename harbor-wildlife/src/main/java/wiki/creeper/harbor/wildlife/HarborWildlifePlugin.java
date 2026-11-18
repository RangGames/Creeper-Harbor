package wiki.creeper.harbor.wildlife;

import java.util.Objects;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Registry;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.ServicesManager;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.core.api.HarborModule;
import wiki.creeper.harbor.core.api.ModuleContext;
import wiki.creeper.harbor.fishing.api.FishingService;
import wiki.creeper.harbor.wildlife.api.WildlifeService;
import wiki.creeper.harbor.wildlife.content.BehaviourBook;
import wiki.creeper.harbor.wildlife.content.WildSpecies;
import wiki.creeper.harbor.wildlife.content.WildlifeContent;
import wiki.creeper.harbor.wildlife.runtime.WildlifeKit;
import wiki.creeper.harbor.wildlife.runtime.WildlifeRuntime;
import wiki.creeper.harbor.world.api.FishingSpotService;
import wiki.creeper.harbor.world.api.RegionService;
import wiki.creeper.harbor.world.api.SeaEventService;

/**
 * Harbor-Wildlife ('17-3' 9장): 바다를 살아 보이게 하는 야생 물고기. 바닐라 몹 뇌 대신 자체 조향(가상 개체, 9.4 비교표)으로 계산 스레드에서
 * 움직이고, 보이는 개체만 Core 리그(RigInstance)로 그린다. 출현 판정에는 영향을 주지 않는다 (판정은 Fishing 단독, 9.1 원칙). Fishing과는
 * Wildlife → Fishing 한 방향 (FishingCastEvent·claimShadow·ShadowDriver). DB·Redis 없음 (개체는 저장하지 않고
 * 서버마다 독립).
 */
public final class HarborWildlifePlugin extends HarborModule {

    private @Nullable WildlifeRuntime runtime;

    @Override
    public String moduleId() {
        return "wildlife";
    }

    @Override
    protected void enable(ModuleContext ctx) {
        WildlifeContent.TYPES.forEach(ctx::contentType);
        ctx.contentCheck(WildSpecies.TYPE, WildlifeContent.SPECIAL_CHECK);
        ctx.contentCheck(WildSpecies.TYPE, WildlifeContent.FISH_UNIQUE);
        ctx.contentCheck(
                BehaviourBook.TYPE,
                (book, check) ->
                        book.actions()
                                .values()
                                .forEach(
                                        a -> {
                                            String key = a.particle();
                                            if (key == null) {
                                                return;
                                            }
                                            Particle p =
                                                    Registry.PARTICLE_TYPE.get(
                                                            NamespacedKey.minecraft(key));
                                            if (p == null) {
                                                check.error(
                                                        "동작 " + a.name() + "의 파티클 '" + key
                                                                + "'가 없습니다");
                                            } else if (p.getDataType() != Void.class) {
                                                check.error(
                                                        "동작 "
                                                                + a.name()
                                                                + "의 파티클 '"
                                                                + key
                                                                + "'는 추가 데이터가 필요해 쓸 수 없습니다");
                                            }
                                        }));

        ServicesManager sm = getServer().getServicesManager();
        Objects.requireNonNull(sm.load(FishingService.class), "Harbor-Fishing이 켜져 있어야 합니다");
        RegionService regions =
                Objects.requireNonNull(sm.load(RegionService.class), "Harbor-World가 켜져 있어야 합니다");
        FishingSpotService spots =
                Objects.requireNonNull(
                        sm.load(FishingSpotService.class), "Harbor-World가 켜져 있어야 합니다");
        SeaEventService sea =
                Objects.requireNonNull(sm.load(SeaEventService.class), "Harbor-World가 켜져 있어야 합니다");

        WildlifeKit kit =
                new WildlifeKit(
                        this, ctx.api(), ctx.tasks(), getSLF4JLogger(), regions, spots, sea);
        WildlifeRuntime rt = new WildlifeRuntime(ctx, kit);
        runtime = rt;
        sm.register(WildlifeService.class, rt.service(), this, ServicePriority.Normal);
    }

    @Override
    protected void disable() {
        WildlifeRuntime rt = runtime;
        if (rt != null) {
            rt.shutdown();
        }
        runtime = null;
        getServer().getServicesManager().unregisterAll(this);
    }
}
