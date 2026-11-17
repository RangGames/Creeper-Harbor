package wiki.creeper.harbor.fishing.session;

import java.time.Instant;
import java.util.List;
import net.kyori.adventure.bossbar.BossBar;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.content.type.FishDef;
import wiki.creeper.harbor.common.id.ItemId;
import wiki.creeper.harbor.common.id.RegionId;
import wiki.creeper.harbor.common.model.Quality;
import wiki.creeper.harbor.fishing.api.SessionState;

/** 플레이어 한 명의 낚시 세션 (메인 스레드 전용). */
final class FishingSession {

    /** 결과가 나오기 전에 끝난 캐스팅의 판정 (60초 보관, 같은 지역·미끼로 다시 던지면 그대로 씀). */
    record PendingRoll(
            RegionId region,
            @Nullable ItemId bait,
            FishDef fish,
            List<FishDef> legends,
            Instant expiresAt) {}

    SessionState state = SessionState.IDLE;
    @Nullable FishDef fish;
    @Nullable RegionId region;
    @Nullable ItemId bait;
    List<FishDef> legends = List.of();
    boolean pityCounted;
    long biteAtNanos;
    @Nullable PendingRoll pending;
    @Nullable Minigame minigame;
    @Nullable BossBar bar;
    int missedBig;

    // 최신 입력 (PlayerInputEvent)
    boolean inputSneak;
    boolean inputLeft;
    boolean inputRight;

    // 관리자 강제 판정 (/harbor fishing force)
    @Nullable FishDef forcedFish;
    @Nullable Quality forcedQuality;

    void clearCast() {
        state = SessionState.IDLE;
        fish = null;
        region = null;
        bait = null;
        legends = List.of();
        pityCounted = false;
        minigame = null;
    }
}
