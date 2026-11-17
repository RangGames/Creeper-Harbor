package wiki.creeper.harbor.common.id;

/** NPC ID. 예: {@code harbor:npc/example}. */
public record NpcId(HarborId id) implements TypedId {

    public static final String PREFIX = "npc/";

    public NpcId {
        TypedId.requirePrefix(id, PREFIX);
    }

    public static NpcId parse(String raw) {
        return new NpcId(HarborId.parse(raw));
    }

    @Override
    public String toString() {
        return id.toString();
    }
}
