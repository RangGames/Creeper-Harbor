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

/**
 * Shared pieces within the module. The species table and balance are replaced wholesale on content
 * reload (immutable values, so any thread may read them).
 */
public final class WildlifeKit {

    /**
     * Wild fish rig root tag (value = fish ID). Used to clean up orphaned display entities ('17-3'
     * 9.4 harbor:wild).
     */
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

    /**
     * Builds the species table and balance from a new snapshot (may be called on a virtual thread).
     */
    ProfileIndex build(ContentSnapshot snapshot) {
        WildlifeBalance b = new WildlifeBalance(snapshot.single(Balance.TYPE));
        return ProfileIndex.build(snapshot, b);
    }

    /** Swaps them in on the main thread. */
    void apply(ContentSnapshot snapshot, ProfileIndex next) {
        this.balance = new WildlifeBalance(snapshot.single(Balance.TYPE));
        this.index = next;
        for (String problem : next.problems()) {
            log.warn("야생 어종 건너뜀: {}", problem);
        }
    }

    /** Content world name (balance.yml world_alias). */
    String contentWorld(World world) {
        return balance.worldAlias(world.getName());
    }
}
