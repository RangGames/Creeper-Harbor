package wiki.creeper.harbor.common.id;

/** 디스플레이 리그 ID. 예: {@code harbor:rig/fish/harbor_mackerel}, {@code harbor:rig/boss/gnome}. */
public record RigId(HarborId id) implements TypedId {

    public static final String PREFIX = "rig/";

    public RigId {
        TypedId.requirePrefix(id, PREFIX);
    }

    public static RigId parse(String raw) {
        return new RigId(HarborId.parse(raw));
    }

    @Override
    public String toString() {
        return id.toString();
    }
}
