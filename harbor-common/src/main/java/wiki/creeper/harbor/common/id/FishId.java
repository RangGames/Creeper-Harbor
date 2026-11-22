package wiki.creeper.harbor.common.id;

/** Fish species ID. E.g. {@code harbor:fish/example}. */
public record FishId(HarborId id) implements TypedId {

    public static final String PREFIX = "fish/";

    public FishId {
        TypedId.requirePrefix(id, PREFIX);
    }

    public static FishId parse(String raw) {
        return new FishId(HarborId.parse(raw));
    }

    @Override
    public String toString() {
        return id.toString();
    }
}
