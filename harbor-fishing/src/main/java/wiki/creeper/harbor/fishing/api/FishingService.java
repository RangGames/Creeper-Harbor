package wiki.creeper.harbor.fishing.api;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.bukkit.entity.Player;
import wiki.creeper.harbor.common.id.FishId;
import wiki.creeper.harbor.common.id.RegionId;
import wiki.creeper.harbor.common.model.Grade;

/**
 * Harbor-Fishing 공개 API (docs 20 3.2). ServicesManager에서 찾는다. 메인 스레드에서 쓴다.
 *
 * <p>확장 지점 (Fishing은 등록된 구현만 부르고 그 모듈에 의존하지 않는다):
 *
 * <ul>
 *   <li>{@link CodexProvider}: Collection (도감 등록 여부)
 *   <li>{@link ShadowDriver}: Wildlife (대기 중 그림자 대행, FishingCastEvent에서 claimShadow)
 *   <li>{@link RegionKeyResolver}, {@link TreasureDropHook}: World (지역 키, 보물 지도)
 *   <li>{@link CatchPresentationOverride}: Shop (포획 연출 코스메틱)
 *   <li>{@link #openTension}, {@link #createCatch}: Boss (앵커 텐션, 보스 어종 확정 포획)
 *   <li>{@link SeaEventMessage}: World가 harbor:event로 보내는 fish_effects
 * </ul>
 */
public interface FishingService {

    SessionState session(UUID player);

    boolean isFishing(UUID player);

    /** 전설 천장 카운터 (조건을 만족한 캐스팅 수). */
    int pity(UUID player, FishId fish);

    /** 직업 품질 상한에 걸려 놓친 대어 수 (이번 접속). */
    int missedBigCount(UUID player);

    /** 도감 조회 SPI 등록 (Collection). */
    void registerCodex(CodexProvider provider);

    /** 지역 키 확장 지점 등록 (World). 다시 부르면 바꾼다. */
    void registerRegionResolver(RegionKeyResolver resolver);

    /** 보물 지도 드롭 확장 지점 등록 (World). */
    void registerTreasureHook(TreasureDropHook hook);

    /** 포획 연출 코스메틱 등록 (Shop). */
    void registerPresentation(CatchPresentationOverride override);

    /**
     * 대기 중인 캐스팅의 그림자를 대행한다 (Wildlife). WAITING 상태일 때만 받아들이고, 이미 다른 드라이버가 있으면 false. 보통은 {@code
     * FishingCastEvent#claimShadow}를 쓴다.
     */
    boolean claimShadow(UUID player, ShadowDriver driver);

    /** 통발. */
    TrapService traps();

    /** 몬테카를로 판정 (가상 스레드). 보정 없는 기본 조건으로 count회 판정해 등급별 비율과 전설 첫 포획까지 평균 캐스팅 수를 돌려준다. */
    CompletableFuture<SimulationResult> simulate(RegionId region, int count);

    /**
     * 지역별 기대 출현 비율 (추가 제안, Economy 시세 S₀용). 보정 없는 기본 가중치로 계산하고, 미끼·이벤트가 필요한 어종과 전설·신화는 0이다. 시간·날씨
     * 조건은 무시한다 (하루 평균에 가깝게).
     */
    double expectedShare(RegionId region, FishId fish);

    /** 텐션 미니게임을 연다 (추가 제안, Boss 앵커). 플레이어가 낚시 중이면 그 세션을 중단(ABORTED)하고 연다. 이미 텐션 세션이 있으면 빈 값. */
    Optional<TensionSession> openTension(Player player, TensionSpec spec);

    /**
     * 보스 어종 아이템을 만든다 (추가 제안, Boss 확정 포획 + 품질 보너스). 크기·품질 판정과 직업 상한, QualityRolledEvent, ★5·전설·신화 장부
     * 기록을 일반 포획과 같이 처리한다. EXP·미끼·내구도는 다루지 않고, 아이템 지급은 호출한 쪽이 한다. 없는 어종이면 빈 값.
     */
    Optional<CatchResult> createCatch(Player player, FishId fish, CatchOptions options);

    /** 진행 중인 해상 이벤트 ID (harbor:event로 받은 것). */
    Set<String> activeSeaEvents();

    /** 시뮬레이션 결과. */
    record SimulationResult(
            int casts,
            Map<Grade, Integer> grades,
            Map<FishId, Integer> species,
            double legendAverageCasts) {}
}
