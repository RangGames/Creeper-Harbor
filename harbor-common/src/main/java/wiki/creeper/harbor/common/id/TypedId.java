package wiki.creeper.harbor.common.id;

/** ID of a fixed kind (fish, item, ...). The path must start with the kind's prefix. */
public interface TypedId {

    HarborId id();

    static HarborId requirePrefix(HarborId id, String prefix) {
        if (!id.hasPrefix(prefix) || id.path().length() == prefix.length()) {
            throw new IllegalArgumentException("ID는 '" + prefix + "'로 시작해야 합니다: " + id);
        }
        return id;
    }
}
