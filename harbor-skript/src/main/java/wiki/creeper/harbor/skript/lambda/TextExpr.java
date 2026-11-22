package wiki.creeper.harbor.skript.lambda;

/** Lambda expression (text). */
public final class TextExpr extends BaseExpr<String> {

    public TextExpr(Spec spec, Spec.Getter<String> getter) {
        super(spec, String.class, getter);
    }
}
