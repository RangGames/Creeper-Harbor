package wiki.creeper.harbor.common.id;

/** 스킬 노드 ID. 예: {@code harbor:skill/example}. */
public record SkillId(HarborId id) implements TypedId {

    public static final String PREFIX = "skill/";

    public SkillId {
        TypedId.requirePrefix(id, PREFIX);
    }

    public static SkillId parse(String raw) {
        return new SkillId(HarborId.parse(raw));
    }

    @Override
    public String toString() {
        return id.toString();
    }
}
