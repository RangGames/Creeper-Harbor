package wiki.creeper.harbor.skript.lambda;

/** 람다 식 (숫자). */
public final class NumberExpr extends BaseExpr<Number> {

    public NumberExpr(Spec spec, Spec.Getter<Number> getter) {
        super(spec, Number.class, getter);
    }
}
