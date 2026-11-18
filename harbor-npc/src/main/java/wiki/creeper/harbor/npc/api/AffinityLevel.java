package wiki.creeper.harbor.npc.api;

import java.util.List;

/**
 * 호감도 단계 (docs 20 5.4, 제안값: 0 낯섦 / 100 아는 사이 / 300 친구 / 600 단짝 / 1,000 신뢰).
 *
 * @param level 단계 번호 (0부터)
 * @param points 이 단계에 필요한 누적 호감도
 * @param discountPercent 상점 할인 % (Economy가 읽음)
 * @param unlocks 해금 태그 (대화 선택지·히든 퀘스트·지역 스토리 후반부 등. 다른 모듈이 의미를 정한다)
 */
public record AffinityLevel(
        int level, int points, String name, int discountPercent, List<String> unlocks) {

    public AffinityLevel {
        unlocks = List.copyOf(unlocks);
    }
}
