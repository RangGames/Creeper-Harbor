package wiki.creeper.harbor.skript.lambda;

/** 람다 식 (글). */
public final class TextExpr extends BaseExpr<String> {

    public TextExpr(Spec spec, Spec.Getter<String> getter) {
        super(spec, String.class, getter);
    }
}
