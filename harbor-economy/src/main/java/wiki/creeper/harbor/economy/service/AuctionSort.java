package wiki.creeper.harbor.economy.service;

import java.util.Locale;

/** 경매 목록 정렬 (추가 제안). 버튼을 누르면 다음 정렬로 돈다. SQL ORDER BY는 고정 글이라 입력이 섞이지 않는다. */
public enum AuctionSort {
    LATEST("created_at DESC, id DESC"),
    PRICE_ASC("price ASC, created_at DESC"),
    PRICE_DESC("price DESC, created_at DESC"),
    ENDING("expires_at ASC, id ASC"),
    /** 등급 높은 순, 같으면 싼 순. 모르는 등급은 맨 뒤 (FIELD = 0). */
    GRADE(
            "FIELD(grade, 'common', 'uncommon', 'rare', 'epic', 'legendary', 'mythic') DESC,"
                    + " price ASC");

    private final String orderBy;

    AuctionSort(String orderBy) {
        this.orderBy = orderBy;
    }

    public String orderBy() {
        return orderBy;
    }

    /** messages_ko.yml 키 이름. */
    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }

    public AuctionSort next() {
        return switch (this) {
            case LATEST -> PRICE_ASC;
            case PRICE_ASC -> PRICE_DESC;
            case PRICE_DESC -> ENDING;
            case ENDING -> GRADE;
            case GRADE -> LATEST;
        };
    }
}
