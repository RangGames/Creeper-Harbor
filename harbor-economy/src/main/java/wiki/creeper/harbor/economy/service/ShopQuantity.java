package wiki.creeper.harbor.economy.service;

import java.util.OptionalInt;

/**
 * Shop purchase quantity (proposed, no server API): left-click 1 · Shift+left-click one stack ·
 * right-click manual input.
 */
public final class ShopQuantity {

    /** Click kind. */
    public enum Click {
        LEFT,
        SHIFT_LEFT,
        RIGHT,
        OTHER
    }

    private ShopQuantity() {}

    /** Quantity to buy right away. Empty for manual input (right-click) or an unknown click. */
    public static OptionalInt amount(Click click, int maxStack) {
        return switch (click) {
            case LEFT -> OptionalInt.of(1);
            case SHIFT_LEFT -> OptionalInt.of(Math.max(1, maxStack));
            default -> OptionalInt.empty();
        };
    }

    /** Entered quantity (1 to max). Empty if not a number or out of range. */
    public static OptionalInt parse(String input, int max) {
        try {
            int n = Integer.parseInt(input.strip());
            return n >= 1 && n <= max ? OptionalInt.of(n) : OptionalInt.empty();
        } catch (NumberFormatException e) {
            return OptionalInt.empty();
        }
    }
}
