package wiki.creeper.harbor.core.item;

import org.bukkit.NamespacedKey;

/**
 * custom_data keys ('16. Plugin design' 5.5). Common: harbor:id, harbor:v / species: species,
 * size_cm, quality, catcher, season, caught_at, uid.
 */
final class ItemKeys {

    static final String NAMESPACE = "harbor";

    static final NamespacedKey ID = key("id");
    static final NamespacedKey VERSION = key("v");
    static final NamespacedKey SPECIES = key("species");
    static final NamespacedKey SIZE = key("size_cm");
    static final NamespacedKey QUALITY = key("quality");
    static final NamespacedKey CATCHER = key("catcher");

    /** Display name of the catcher (proposed: needed when rebuilding the lore). */
    static final NamespacedKey CATCHER_NAME = key("catcher_name");

    static final NamespacedKey SEASON = key("season");
    static final NamespacedKey CAUGHT_AT = key("caught_at");
    static final NamespacedKey UID = key("uid");

    private ItemKeys() {}

    @SuppressWarnings("deprecation") // uses a fixed namespace without a plugin instance
    private static NamespacedKey key(String value) {
        return new NamespacedKey(NAMESPACE, value);
    }
}
