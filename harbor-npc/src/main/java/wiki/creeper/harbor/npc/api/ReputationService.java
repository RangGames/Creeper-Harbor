package wiki.creeper.harbor.npc.api;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.jspecify.annotations.Nullable;

/**
 * 항구 평판 (docs 20 5.4). 값은 Core player_season.reputation이고 쓰기는 이 서비스만 한다 (Quest, Collection 방류, Boss
 * 월드 보스 보상은 이 API를 부른다). 시즌마다 초기화되고, 시즌을 넘어 유지되는 최고 도달 등급은 {@link #bestTier}다 (D11: 하우징 크기·어항 칸은 최고
 * 등급, 경매 슬롯은 현재 등급).
 *
 * <p>메인 스레드에서 부른다. 같은 refId는 시즌에 한 번만 들어간다 (npc_reputation_log 유니크). 예: {@code
 * q:<uuid>:<quest>:<cycle>}, {@code boss1:<boss>}.
 */
public interface ReputationService {

    /** 접속 중이 아니면 0. */
    int value(UUID player);

    /** 현재 등급 (1부터). */
    int tier(UUID player);

    /** 시즌을 넘어 유지되는 최고 도달 등급 (접속 중일 때). 접속 중이 아니면 {@link #loadBestTier}. */
    int bestTier(UUID player);

    /** DB에서 최고 도달 등급을 읽는다 (오프라인 플레이어의 하우징 크기 판정 등). */
    CompletableFuture<Integer> loadBestTier(UUID player);

    List<ReputationTier> tiers();

    ReputationTier tierInfo(int tier);

    /** 다음 등급 (최고 등급이면 빈 값). */
    Optional<ReputationTier> nextTier(UUID player);

    /** 출처별 기본값 (balance.yml reputation.sources). Quest는 보상 표에 값이 없으면 이 값을 쓴다. */
    int defaultValue(ReputationSource source);

    /**
     * 평판을 더한다. CONTRIBUTION 출처는 reputation_gain 보정자를 곱하고, 하루 상한이 있는 출처는 상한까지만 더한다. 등급이 바뀌면
     * ReputationTierChangedEvent를 내고 최고 등급을 즉시 저장한다.
     *
     * @param refId 중복 방지 참조 ID (null이면 중복 검사 안 함)
     */
    CompletableFuture<ReputationResult> add(
            UUID player, int delta, ReputationSource source, @Nullable String refId);

    /** 최근 획득 내역 (최신순, 접속 중일 때). */
    List<ReputationLogEntry> recent(UUID player);
}
