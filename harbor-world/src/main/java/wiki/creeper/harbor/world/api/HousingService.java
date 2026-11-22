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
}
