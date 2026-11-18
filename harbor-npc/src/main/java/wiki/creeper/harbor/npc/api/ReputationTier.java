package wiki.creeper.harbor.npc.api;

/**
 * 평판 등급 (docs 20 5.4, 제안값: 1 이방인 0 / 2 이웃 300 / 3 단골 1,000 / 4 항구의 일원 2,000 / 5 항구의 기둥 3,500).
 *
 * @param tier 1부터
 * @param points 필요한 누적 평판
 */
public record ReputationTier(int tier, int points, String name) {}
