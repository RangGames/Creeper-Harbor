package wiki.creeper.harbor.common.util;

import org.jspecify.annotations.Nullable;

/**
 * One validation error. When the position is known, printed as "fish/coral_isles.yml 42행 3열:
 * message" (line 42, column 3).
 */
public record ValidationError(@Nullable SourcePos pos, String message) {

    @Override
    public String toString() {
        return pos == null ? message : pos + ": " + message;
    }
}
