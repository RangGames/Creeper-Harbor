package wiki.creeper.harbor.quest.api;

import java.util.concurrent.CompletableFuture;

/** 서버 진행도 (docs 20 6.4): 장 클리어 인원과 클라이맥스 개방 조건. DB를 읽으므로 future는 메인 스레드에서 완료된다. */
public interface ServerProgressService {

    /**
     * 클라이맥스 현황.
     *
     * @param requiredClears max(최소 인원, 활동 유저 × 비율)
     * @param flagged 서버 플래그 climax_ready가 이미 섰는지
     */
    record ClimaxStatus(
            double donationRate,
            double requiredRate,
            long clears,
            int requiredClears,
            long activePlayers,
            boolean ready,
            boolean flagged) {}

    /** 이번 시즌 그 장의 클리어 인원 (연대기 회상 포함). */
    CompletableFuture<Long> chapterClearCount(int chapter);

    CompletableFuture<ClimaxStatus> climaxStatus();
}
