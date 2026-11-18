package wiki.creeper.harbor.wildlife.runtime;

import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.slf4j.Logger;
import wiki.creeper.harbor.common.content.ContentSnapshot;
import wiki.creeper.harbor.common.content.type.Balance;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.core.api.HarborApi;
import wiki.creeper.harbor.core.api.HarborModule;
import wiki.creeper.harbor.core.task.Tasks;
import wiki.creeper.harbor.wildlife.content.WildlifeBalance;
import wiki.creeper.harbor.wildlife.sim.MediumGrid;
import wiki.creeper.harbor.wildlife.sim.ProfileIndex;
import wiki.creeper.harbor.world.api.FishingSpotService;
import wiki.creeper.harbor.world.api.RegionService;
import wiki.creeper.harbor.world.api.SeaEventService;

/** 모듈 안에서 같이 쓰는 것들. 어종 표·밸런스는 콘텐츠 리로드 때 통째로 바꾼다 (변경 불가 값이라 어느 스레드에서 읽어도 됨). */
public final class WildlifeKit {

    /** 야생 개체 리그 루트 표시 (값 = 개체 ID). 주인 잃은 표시 엔티티 정리용 ('17-3' 9.4 harbor:wild). */
    static final NamespacedKey WILD_KEY = new NamespacedKey(HarborId.DEFAULT_NAMESPACE, "wild");

    final HarborModule plugin;
    final HarborApi api;
    final Tasks tasks;
    final Logger log;
    final RegionService regions;
    final FishingSpotService spots;
    final SeaEventService seaEvents;
    final MediumGrid grid = new MediumGrid();
    private volatile ProfileIndex index = ProfileIndex.EMPTY;
    private volatile WildlifeBalance balance;

    public WildlifeKit(
            HarborModule plugin,
            HarborApi api,
            Tasks tasks,
            Logger log,
            RegionService regions,
            FishingSpotService spots,
            SeaEventService seaEvents) {
        this.plugin = plugin;
        this.api = api;
        this.tasks = tasks;
        this.log = log;
        this.regions = regions;
        this.spots = spots;
        this.seaEvents = seaEvents;
        this.balance = new WildlifeBalance(api.content().snapshot().single(Balance.TYPE));
    }

    ProfileIndex index() {
        return index;
    }

    WildlifeBalance balance() {
        return balance;
    }

    /** 새 스냅샷으로 어종 표·밸런스를 만든다 (가상 스레드에서 불러도 된다). */
    ProfileIndex build(ContentSnapshot snapshot) {
        WildlifeBalance b = new WildlifeBalance(snapshot.single(Balance.TYPE));
        return ProfileIndex.build(snapshot, b);
    }

    /** 메인 스레드에서 교체한다. */
    void apply(ContentSnapshot snapshot, ProfileIndex next) {
        this.balance = new WildlifeBalance(snapshot.single(Balance.TYPE));
        this.index = next;
        for (String problem : next.problems()) {
            log.warn("야생 어종 건너뜀: {}", problem);
        }
    }

    /** 콘텐츠 월드 이름 (balance.yml world_alias). */
    String contentWorld(World world) {
        return balance.worldAlias(world.getName());
    }
}
