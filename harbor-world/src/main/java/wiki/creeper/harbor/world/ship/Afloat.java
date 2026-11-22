package wiki.creeper.harbor.world.ship;

import org.bukkit.entity.Boat;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.core.api.HarborSounds;
import wiki.creeper.harbor.core.api.RigInstance;

/** A launched boat. Main thread only. */
final class Afloat {
    ShipRepository.Row row;
    final Boat boat;
    @Nullable RigInstance rig;
    final boolean follow;
    double damage;
    int ticks;

    /** Looping sailing sound (only while moving under control, '15. sound' 4.4). */
    HarborSounds.Loop engine = HarborSounds.Loop.NONE;

    Afloat(ShipRepository.Row row, Boat boat, @Nullable RigInstance rig, boolean follow) {
        this.row = row;
        this.boat = boat;
        this.rig = rig;
        this.follow = follow;
    }
}
