package wiki.creeper.harbor.fishing.session;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.kyori.adventure.bossbar.BossBar;
import org.bukkit.Location;
import org.bukkit.entity.FishHook;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.content.type.FishDef;
import wiki.creeper.harbor.common.id.ItemId;
import wiki.creeper.harbor.common.id.RegionId;
import wiki.creeper.harbor.common.model.Quality;
import wiki.creeper.harbor.core.api.HarborSounds;
import wiki.creeper.harbor.fishing.api.SessionState;

/** 플레이어 한 명의 낚시 세션 (메인 스레드 전용). */
final class FishingSession {

    /** 신화 서버 마릿수 예약 (DB 카운터, confirmed가 될 때까지 기다린다). */
    static final class MythicHold {
        final String eventId;
        final String fishId;
        boolean confirmed;
        boolean released;

        MythicHold(String eventId, String fishId) {
            this.eventId = eventId;
            this.fishId = fishId;
        }
    }

    /**
     * 결과가 나오기 전에 끝난 캐스팅의 판정 (60초 보관, 같은 지역·미끼로 다시 던지면 그대로 씀). 지역·미끼 조합마다 따로 보관한다: 하나만 두면 미끼를 번갈아 바꿔
     * 던질 때마다 앞 판정이 버려지고 새로 굴려져 재판정 방지가 무력해졌다.
     */
    record PendingRoll(
            RegionId region,
            @Nullable ItemId bait,
            FishDef fish,
            List<FishDef> legends,
            @Nullable MythicHold mythic,
            Instant expiresAt) {}

    SessionState state = SessionState.IDLE;
    @Nullable FishDef fish;
    @Nullable RegionId region;
    @Nullable ItemId bait;
    List<FishDef> legends = List.of();
    boolean pityCounted;
    long biteAtNanos;
    final Map<String, PendingRoll> pendings = new HashMap<>();

    static String pendingKey(RegionId region, @Nullable ItemId bait) {
        return region + "|" + (bait == null ? "-" : bait.toString());
    }

    @Nullable Minigame minigame;
    @Nullable BossBar bar;
    int missedBig;

    // CAST → WAITING
    @Nullable FishHook hook;
    @Nullable Location hookLoc;
    int waitTicks;
    int castTicks;
    boolean school;
    @Nullable MythicHold mythic;
    @Nullable ShadowControl shadow;

    // 미니게임
    boolean legendBait;
    float baseYaw;
    int lastPhase = 1;
    boolean borderWarn;

    /** 릴 감기 반복 소리 (Shift 감는 동안, '15. 사운드' fishing.reel_loop). */
    HarborSounds.Loop reel = HarborSounds.Loop.NONE;

    Minigame.@Nullable Pattern lastActive;
    FishingSettings settings = FishingSettings.DEFAULT;

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
        hook = null;
        hookLoc = null;
        waitTicks = 0;
        castTicks = 0;
        school = false;
        mythic = null;
        shadow = null;
        legendBait = false;
        lastPhase = 1;
        borderWarn = false;
        lastActive = null;
    }
}
