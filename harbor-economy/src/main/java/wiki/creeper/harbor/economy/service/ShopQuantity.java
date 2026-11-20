package wiki.creeper.harbor.economy.service;

import java.util.OptionalInt;

/** 상점 구매 수량 (추가 제안, 서버 API 없음): 좌클릭 1개 · Shift+좌클릭 한 묶음 · 우클릭 직접 입력. */
public final class ShopQuantity {

    /** 클릭 종류. */
    public enum Click {
        LEFT,
        SHIFT_LEFT,
        RIGHT,
        OTHER
    }

    private ShopQuantity() {}

    /** 바로 살 수량. 직접 입력(우클릭)·모르는 클릭이면 비어 있음. */
    public static OptionalInt amount(Click click, int maxStack) {
        return switch (click) {
            case LEFT -> OptionalInt.of(1);
            case SHIFT_LEFT -> OptionalInt.of(Math.max(1, maxStack));
            default -> OptionalInt.empty();
        };
    }

    /** 입력한 수량 (1~max). 숫자가 아니거나 범위 밖이면 비어 있음. */
    public static OptionalInt parse(String input, int max) {
        try {
            int n = Integer.parseInt(input.strip());
            return n >= 1 && n <= max ? OptionalInt.of(n) : OptionalInt.empty();
        } catch (NumberFormatException e) {
            return OptionalInt.empty();
        }
    }
}
