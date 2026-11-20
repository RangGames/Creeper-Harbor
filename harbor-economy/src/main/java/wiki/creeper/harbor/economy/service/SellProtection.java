package wiki.creeper.harbor.economy.service;

/**
 * 귀한 물고기 보호 규칙 (추가 제안, 서버 API 없음). "모두 넣기"·/판매 전체 판매는 보호 대상을 건너뛰고, 판매 화면에 직접 넣은 보호 대상은 따로 확인을 받은
 * 뒤에만 판다. 잠근 아이템({@link ItemLock})은 경매 등록도 막는다.
 */
public final class SellProtection {

    /** 보호 이유 (우선순위 순). */
    public enum Reason {
        NONE,
        /** 플레이어가 잠근 아이템. */
        LOCKED,
        /** 고유 번호가 있는 어종 (★5·전설·신화, fish_ledger). */
        UNIQUE,
        /** 최고 품질 (★5). */
        MAX_QUALITY;

        public boolean protects() {
            return this != NONE;
        }
    }

    private SellProtection() {}

    /**
     * @param stars 품질 별 수
     * @param unique 고유 번호가 있는지
     * @param locked 잠금 표시가 있는지
     */
    public static Reason of(int stars, int maxStars, boolean unique, boolean locked) {
        if (locked) {
            return Reason.LOCKED;
        }
        if (unique) {
            return Reason.UNIQUE;
        }
        if (stars >= maxStars) {
            return Reason.MAX_QUALITY;
        }
        return Reason.NONE;
    }
}
