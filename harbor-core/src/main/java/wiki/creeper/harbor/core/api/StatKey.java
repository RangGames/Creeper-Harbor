package wiki.creeper.harbor.core.api;

import java.util.regex.Pattern;

/**
 * Name of a value changed by modifiers. E.g. rare_weight, bite_wait, sell_price, reel_speed,
 * line_strength, exp_gain.
 */
public record StatKey(String key) {

    private static final Pattern KEY = Pattern.compile("[a-z0-9_]+");

    public StatKey {
        if (!KEY.matcher(key).matches()) {
            throw new IllegalArgumentException("스탯 키 형식이 틀렸습니다: " + key);
        }
    }

    public static StatKey of(String key) {
        return new StatKey(key);
    }

    @Override
    public String toString() {
        return key;
    }
}
