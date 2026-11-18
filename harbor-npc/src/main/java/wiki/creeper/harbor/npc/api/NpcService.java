package wiki.creeper.harbor.npc.api;

import java.util.Collection;
import java.util.Optional;
import java.util.function.Predicate;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import wiki.creeper.harbor.common.id.NpcId;

/**
 * NPC 조회 (docs 20 5.2). Bukkit ServicesManager에 등록된다. 메인 스레드에서 쓴다.
 *
 * <pre>{@code
 * NpcService npcs = Harbor.api().service(NpcService.class).orElseThrow();
 * if (npcs.isNear(player, NpcId.parse("npc/bawi"), 6)) { ... }
 * }</pre>
 */
public interface NpcService {

    Optional<NpcInfo> npc(NpcId id);

    Collection<NpcInfo> all();

    /** 지금 서 있는 위치. 떠돌이는 현재 후보 위치, 등장 조건이 안 맞거나 다른 서버에 있으면 빈 값. */
    Optional<Location> location(NpcId id);

    /** 반경 안에서 가장 가까운 (서 있는) NPC. */
    Optional<NpcInfo> nearest(Player player, double radius);

    boolean isNear(Player player, NpcId id, double radius);

    /** 이 플레이어에게 보이는 이름 (name_variants 반영). */
    Component displayName(Player viewer, NpcId id);

    /**
     * NPC 클릭·컷씬 시작을 막는 상태 검사 (미니게임·보스전 등). NPC는 다른 게임 모듈을 모르므로 그 상태를 아는 모듈이 등록한다. owner가 꺼지면 자동으로
     * 지운다.
     *
     * @param reason 사유 코드. 안내 문구 키는 {@code npc.busy.<reason>} (없으면 npc.busy.default)
     */
    void registerBusyCheck(Plugin owner, String reason, Predicate<Player> busy);

    /** 등록된 검사 중 처음으로 걸린 사유 (컷씬 재생 중이면 cutscene). */
    Optional<String> busyReason(Player player);
}
