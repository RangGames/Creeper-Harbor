package wiki.creeper.harbor.world.ship;

import org.bukkit.entity.Boat;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.core.api.HarborSounds;
import wiki.creeper.harbor.core.api.RigInstance;

/** 띄운 배. 메인 스레드 전용. */
final class Afloat {
    ShipRepository.Row row;
    final Boat boat;
    @Nullable RigInstance rig;
    final boolean follow;
    double damage;
    int ticks;

    /** 항해 반복 소리 (운전 중 움직일 때만, '15. 사운드' 4.4). */
    HarborSounds.Loop engine = HarborSounds.Loop.NONE;

    Afloat(ShipRepository.Row row, Boat boat, @Nullable RigInstance rig, boolean follow) {
        this.row = row;
        this.boat = boat;
        this.rig = rig;
        this.follow = follow;
    }
}
