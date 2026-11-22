package wiki.creeper.harbor.wildlife.api;

import java.util.UUID;
import org.bukkit.Location;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.id.FishId;
import wiki.creeper.harbor.common.id.HarborId;

/**
 * Read-only view of one wild fish (as of the last simulation step, up to 100ms stale). Fish are not
 * entities but virtual objects on the simulation thread, drawn with Core rigs only while visible.
 *
 * @param id fish ID (different from the rig/entity UUID)
 * @param species wildlife/species.yml entry ID
 * @param school school ID (null if solitary)
 * @param radius body radius (blocks, for raycast hits)
 * @param visible whether anyone can see it right now
 * @param scripted whether it was created by spawnScripted/spawnSchool
 */
public record WildFish(
        UUID id,
        FishId fish,
        HarborId species,
        Location location,
        WildState state,
        Medium medium,
        @Nullable UUID school,
        double radius,
        boolean visible,
        boolean scripted) {

    public WildFish {
        location = location.clone();
    }

    @Override
    public Location location() {
        return location.clone();
    }
}
