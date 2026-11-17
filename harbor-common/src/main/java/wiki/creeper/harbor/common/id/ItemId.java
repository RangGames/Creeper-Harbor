package wiki.creeper.harbor.common.id;

/** 아이템 ID. 예: {@code harbor:item/example}. */
public record ItemId(HarborId id) implements TypedId {

    public static final String PREFIX = "item/";

    public ItemId {
        TypedId.requirePrefix(id, PREFIX);
    }

    public static ItemId parse(String raw) {
        return new ItemId(HarborId.parse(raw));
    }

    @Override
    public String toString() {
        return id.toString();
    }
}
