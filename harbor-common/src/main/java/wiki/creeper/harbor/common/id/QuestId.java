package wiki.creeper.harbor.common.id;

/** 퀘스트 ID. 예: {@code harbor:quest/example}. */
public record QuestId(HarborId id) implements TypedId {

    public static final String PREFIX = "quest/";

    public QuestId {
        TypedId.requirePrefix(id, PREFIX);
    }

    public static QuestId parse(String raw) {
        return new QuestId(HarborId.parse(raw));
    }

    @Override
    public String toString() {
        return id.toString();
    }
}
