package wiki.creeper.harbor.common.id;

/** 종류가 정해진 ID (어종, 아이템 ...). 경로가 종류별 접두사로 시작해야 한다. */
public interface TypedId {

    HarborId id();

    static HarborId requirePrefix(HarborId id, String prefix) {
        if (!id.hasPrefix(prefix) || id.path().length() == prefix.length()) {
            throw new IllegalArgumentException("ID는 '" + prefix + "'로 시작해야 합니다: " + id);
        }
        return id;
    }
}
