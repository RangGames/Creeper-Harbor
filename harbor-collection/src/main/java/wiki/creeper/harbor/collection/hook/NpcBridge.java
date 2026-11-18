package wiki.creeper.harbor.collection.hook;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * NPC 선택 의존을 감춘 창구. Harbor-Npc가 없으면 {@link #NONE} (최고 평판 1등급, 평판 지급 없음). NPC api 타입이 시그니처에 없어서 NPC가
 * 없어도 이 인터페이스는 불러올 수 있다.
 */
public interface NpcBridge {

    /** 접속 중인 플레이어의 시즌 간 최고 도달 평판 등급 (D11). */
    int bestTier(UUID player);

    /** 오프라인 플레이어 포함 (DB). 결과는 메인 스레드에서 완료된다. */
    CompletableFuture<Integer> loadBestTier(UUID player);

    /**
     * 방류 평판 (출처 RELEASE, CONTRIBUTION · 하루 상한은 NPC가 적용).
     *
     * @param amount 0이면 NPC 기본값 (balance.yml reputation.sources.release)
     * @return 실제로 더한 값 (오프라인·상한이면 0)
     */
    CompletableFuture<Integer> addReleaseReputation(UUID player, int amount, String refId);

    /** 평판 등급이 바뀔 때 부를 함수 (개인 어항 칸 다시 계산). */
    default void onTierChanged(Consumer<UUID> listener) {}

    NpcBridge NONE =
            new NpcBridge() {
                @Override
                public int bestTier(UUID player) {
                    return 1;
                }

                @Override
                public CompletableFuture<Integer> loadBestTier(UUID player) {
                    return CompletableFuture.completedFuture(1);
                }

                @Override
                public CompletableFuture<Integer> addReleaseReputation(
                        UUID player, int amount, String refId) {
                    return CompletableFuture.completedFuture(0);
                }
            };
}
