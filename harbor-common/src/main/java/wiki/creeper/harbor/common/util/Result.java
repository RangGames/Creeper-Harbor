package wiki.creeper.harbor.common.util;

import java.util.function.Function;

/**
 * A success value or a failure reason. Used instead of exceptions when the caller must handle
 * failure.
 */
public sealed interface Result<T, E> permits Result.Ok, Result.Err {

    record Ok<T, E>(T value) implements Result<T, E> {}

    record Err<T, E>(E error) implements Result<T, E> {}

    static <T, E> Result<T, E> ok(T value) {
        return new Ok<>(value);
    }

    static <T, E> Result<T, E> err(E error) {
        return new Err<>(error);
    }

    default boolean isOk() {
        return this instanceof Ok;
    }

    default <U> Result<U, E> map(Function<? super T, ? extends U> mapper) {
        return switch (this) {
            case Ok<T, E>(T value) -> Result.ok(mapper.apply(value));
            case Err<T, E>(E error) -> Result.err(error);
        };
    }

    /**
     * Extracts the success value. IllegalStateException on failure. Use only in tests or on certain
     * paths.
     */
    default T orThrow() {
        return switch (this) {
            case Ok<T, E>(T value) -> value;
            case Err<T, E>(E error) -> throw new IllegalStateException("Result is Err: " + error);
        };
    }
}
