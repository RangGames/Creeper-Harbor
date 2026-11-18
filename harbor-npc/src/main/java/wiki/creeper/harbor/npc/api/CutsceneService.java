package wiki.creeper.harbor.npc.api;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.bukkit.entity.Player;
import wiki.creeper.harbor.common.id.HarborId;

/**
 * 컷씬 재생기 (docs 20 5.4). Quest(스토리 컷씬), Jobs(전직 컷씬), Boss(클라이맥스 연출)가 쓴다. 컷씬은
 * content/cutscenes/*.yml, ID는 {@code harbor:cutscene/<이름>}. 재생 중에는 서버 이동을 거부하고(HandoffBlocker
 * "cutscene") 피해·허기를 막는다. 퇴장·강제 종료에도 관전 모드로 남지 않도록 cutscene_restore에 복구 정보를 둔다.
 *
 * <pre>{@code
 * cutscenes.play(player, HarborId.parse("cutscene/prologue_drift"))
 *         .thenAccept(result -> { if (result.completed()) advance(player); });
 * }</pre>
 *
 * 결과 future는 메인 스레드에서 완료된다.
 */
public interface CutsceneService {

    CompletableFuture<CutsceneResult> play(Player player, HarborId cutscene);

    /** 여러 명에게 같은 틱에 시작한다 (플레이어별로 독립 인스턴스, 클라이맥스 광장 연출용). */
    Map<UUID, CompletableFuture<CutsceneResult>> playGroup(
            Collection<? extends Player> players, HarborId cutscene);

    boolean isPlaying(Player player);

    Optional<HarborId> playing(Player player);

    /** 플레이어 플래그 cutscene/<이름> (영구). */
    boolean hasSeen(Player player, HarborId cutscene);

    /** 재생 중이면 CANCELLED로 끝낸다. */
    boolean stop(Player player);

    boolean exists(HarborId cutscene);
}
