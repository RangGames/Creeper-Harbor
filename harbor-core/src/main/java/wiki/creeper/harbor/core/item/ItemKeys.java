package wiki.creeper.harbor.core.item;

import org.bukkit.NamespacedKey;

/** custom_data 키 ('16. 플러그인 설계' 5.5). 공통: harbor:id, harbor:v / 어종: species, size_cm, quality, catcher, season, caught_at, uid. */
final class ItemKeys {

    static final String NAMESPACE = "harbor";

    static final NamespacedKey ID = key("id");
    static final NamespacedKey VERSION = key("v");
    static final NamespacedKey SPECIES = key("species");
    static final NamespacedKey SIZE = key("size_cm");
    static final NamespacedKey QUALITY = key("quality");
    static final NamespacedKey CATCHER = key("catcher");
    /** 표시용 낚은 사람 이름 (추가 제안: 설명 문구를 다시 만들 때 필요). */
    static final NamespacedKey CATCHER_NAME = key("catcher_name");
    static final NamespacedKey SEASON = key("season");
    static final NamespacedKey CAUGHT_AT = key("caught_at");
    static final NamespacedKey UID = key("uid");

    private ItemKeys() {}

    @SuppressWarnings("deprecation") // 플러그인 인스턴스 없이 고정 네임스페이스를 쓴다
    private static NamespacedKey key(String value) {
        return new NamespacedKey(NAMESPACE, value);
    }
}
