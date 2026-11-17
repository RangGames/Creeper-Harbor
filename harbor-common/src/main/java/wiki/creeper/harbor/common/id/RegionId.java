package wiki.creeper.harbor.common.id;

/** 지역 ID. 예: {@code harbor:region/example}. */
public record RegionId(HarborId id) implements TypedId {

    public static final String PREFIX = "region/";

    public RegionId {
        TypedId.requirePrefix(id, PREFIX);
    }

    public static RegionId parse(String raw) {
        return new RegionId(HarborId.parse(raw));
    }

    @Override
    public String toString() {
        return id.toString();
    }
}
