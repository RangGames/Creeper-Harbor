package wiki.creeper.harbor.common.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;

/**
 * Validation error list. Does not stop at the first error; collects them all and shows them at
 * once.
 */
public final class ValidationErrors {

    private final List<ValidationError> errors = new ArrayList<>();

    public void add(@Nullable SourcePos pos, String message) {
        errors.add(new ValidationError(pos, message));
    }

    public void addAll(ValidationErrors other) {
        errors.addAll(other.errors);
    }

    public boolean isEmpty() {
        return errors.isEmpty();
    }

    public int size() {
        return errors.size();
    }

    public List<ValidationError> list() {
        return Collections.unmodifiableList(errors);
    }

    /** Human-readable multi-line string. */
    public String format() {
        return errors.stream().map(ValidationError::toString).collect(Collectors.joining("\n"));
    }

    @Override
    public String toString() {
        return format();
    }
}
