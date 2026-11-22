package wiki.creeper.harbor.skript.lambda;

/** Lambda expression (number). */
public final class NumberExpr extends BaseExpr<Number> {

    public NumberExpr(Spec spec, Spec.Getter<Number> getter) {
        super(spec, Number.class, getter);
    }
}
