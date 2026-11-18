package wiki.creeper.harbor.fishing.trap;

/**
 * 통발 안의 미수확 어획 한 마리 (traps.contents JSON 항목, 추가 제안).
 *
 * @param uid 항목 ID (수확·우편 회수 때 중복 지급 방지)
 * @param fish 어종 ID 문자열
 * @param caughtAt 산출 시각 (epoch ms)
 */
public record TrapCatch(String uid, String fish, double size, int quality, long caughtAt) {}
