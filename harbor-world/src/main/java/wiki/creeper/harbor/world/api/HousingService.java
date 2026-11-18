package wiki.creeper.harbor.world.api;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

/**
 * 하우징 (개인 부두, docs 20 8.2 HousingService). 플롯·권한·방문 정책·장식은 World, 개인 어항 전시는 Collection이 맡는다.
 * Collection은 {@link #displayAnchors}로 수조 자리를 받고, {@link
 * wiki.creeper.harbor.world.api.event.DisplayAnchorChangedEvent}로 수조가 놓이거나 치워진 것을 안다. 메인 스레드 전용
 * (CompletableFuture 결과는 메인 스레드에서 완료).
 */
public interface HousingService {

    boolean isHousingWorld(World world);

    /** 위치의 플롯 (적재된 플롯만). */
    Optional<PlotInfo> plotAt(Location location);

    /** 적재된 플롯 (주인이 접속 중이거나 누가 방문 중). */
    Optional<PlotInfo> plotOf(UUID owner);

    /** DB에서 읽는다 (없으면 빈 값, 새로 만들지 않는다). */
    CompletableFuture<Optional<PlotInfo>> loadPlot(UUID owner);

    boolean can(Player player, PlotInfo plot, PlotPermission permission);

    /** 적재된 플롯의 수조 자리 (slot 순서). */
    List<DisplayAnchor> displayAnchors(long plotId);
}
