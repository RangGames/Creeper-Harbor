package wiki.creeper.harbor.common.id;

/** Boss ID. E.g. {@code harbor:boss/example}. */
public record BossId(HarborId id) implements TypedId {

    public static final String PREFIX = "boss/";

    public BossId {
        TypedId.requirePrefix(id, PREFIX);
    }

    public static BossId parse(String raw) {
        return new BossId(HarborId.parse(raw));
    }

    @Override
    public String toString() {
        return id.toString();
    }
}
