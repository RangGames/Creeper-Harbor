package wiki.creeper.harbor.season.api;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import wiki.creeper.harbor.season.content.HallOfFameCategory;

/**
 * 명예의 전당 (docs 20 11.4). Season은 다른 모듈 이벤트를 구독하지 않으므로 Collection(서버 최대어)·Boss(최단 처치)가 {@link
 * #submit}을 직접 부른다.
 *
 * <ul>
 *   <li>진행 중 시즌: 제출하면 잠정 기록(final=0)을 갱신하고, 1위가 바뀌면 HallOfFameUpdatedEvent와 harbor:announce 공지
 *   <li>시즌 마감(전환 SNAPSHOT 단계): 아카이브 데이터로 다시 계산해 final=1로 확정하고 연결 칭호를 지급 (참조 ID {@code
 *       hof:<season>:<category>:<subject>})
 *   <li>결과 future는 메인 스레드에서 완료된다
 * </ul>
 *
 * <pre>{@code
 * // Collection: 전체 리그 서버 최대어가 바뀌었을 때
 * hof.submit(HallOfFameSubmission.biggestFish(fish.id().id(), uuid, name, size, caughtAt));
 * }</pre>
 */
public interface HallOfFameService {

    /** 제출 결과. */
    enum Outcome {
        /** 새 1위 (또는 선착순 명단에 새로 들어감). */
        NEW_LEADER,
        /** 순위 안에 들었지만 1위는 아님. */
        RANKED,
        /** 기존 기록보다 못하거나 선착순이 찼음. */
        NOT_RANKED,
        /** 모르는 분류, 또는 시즌 전환 중. */
        REJECTED,
        FAILED
    }

    CompletableFuture<Outcome> submit(HallOfFameSubmission submission);

    /** 분류 정의 (hall_of_fame.yml). */
    List<HallOfFameCategory> categories();

    Optional<HallOfFameCategory> category(String category);

    /** 시즌·분류의 기록 (대상별 순위순). 진행 중 시즌이면 잠정 기록. */
    CompletableFuture<List<HallOfFameEntry>> entries(int season, String category);

    /** 기록이 있는 시즌 번호 (최근 순). */
    CompletableFuture<List<Integer>> seasons();
}
