package wiki.creeper.harbor.world.api;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

/**
 * Housing (personal dock, docs 20 8.2 HousingService). Plots, permissions, visit policy and decor
 * belong to World; personal aquarium displays belong to Collection. Collection gets tank slots from
 * {@link #displayAnchors} and learns about tanks being placed or removed via {@link
 * wiki.creeper.harbor.world.api.event.DisplayAnchorChangedEvent}. Main thread only
 * (CompletableFuture results complete on the main thread).
 */
public interface HousingService {

    boolean isHousingWorld(World world);

    /** Plot at the location (loaded plots only). */
    Optional<PlotInfo> plotAt(Location location);

    /** Loaded plot (the owner is online or someone is visiting). */
    Optional<PlotInfo> plotOf(UUID owner);

    /** Reads from the DB (empty if none; does not create one). */
    CompletableFuture<Optional<PlotInfo>> loadPlot(UUID owner);

    boolean can(Player player, PlotInfo plot, PlotPermission permission);

    /** Tank slots of a loaded plot (slot order). */
    List<DisplayAnchor> displayAnchors(long plotId);

    /**
     * The tank an anchor belongs to. Anchors with equal bounds are one tank holding several fish
     * (proposed). The default is the 1×1 tank of the anchor block.
     */
    default TankBounds tankOf(DisplayAnchor anchor) {
        return TankBounds.of(anchor);
    }

    /**
     * Item model drawn over the placed tank of an anchor (housing_decor.yml tank_model: frame,
     * water and decoration around the glass blocks), empty if the decor has none (proposed).
     */
    default Optional<String> tankModel(DisplayAnchor anchor) {
        return Optional.empty();
    }

    /** Result of {@link #visit}. */
    enum VisitResult {
        /** Sent to the plot entry (a server move if housing runs on another server). */
        SENT,
        /** The owner has no plot (plots are created on the owner's first /부두). */
        NO_PLOT,
        /** The visit policy does not let this player in (notice sent). */
        CLOSED
    }

    /**
     * Sends the player to the owner's plot entry, the same as /부두 방문 (loads the plot if needed).
     *
     * @param bypassPolicy ignore the visit policy (admin)
     */
    CompletableFuture<VisitResult> visit(Player player, UUID owner, boolean bypassPolicy);

    /**
     * Adds or removes a co-builder on the owner's plot, the same as /부두 건축자 추가·제거 (DB row, then
     * other servers are told to reload the plot so a removed builder cannot keep building).
     * Co-builders are the only member role.
     *
     * @return false if the owner has no plot
     */
    CompletableFuture<Boolean> setMember(UUID owner, UUID member, boolean add);

    /**
     * Sets the owner's visit policy, the same as /부두 설정 (cache, DB row, cross-server notice).
     *
     * @return false if the owner has no plot
     */
    CompletableFuture<Boolean> setVisitPolicy(UUID owner, VisitPolicy policy);
}
