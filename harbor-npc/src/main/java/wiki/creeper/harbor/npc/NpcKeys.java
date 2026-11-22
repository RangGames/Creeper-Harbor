package wiki.creeper.harbor.npc;

import org.bukkit.NamespacedKey;
import org.bukkit.entity.Entity;
import org.bukkit.persistence.PersistentDataType;
import org.jspecify.annotations.Nullable;

/**
 * PDC tags for NPC and cutscene entities. No entity is saved (persistent = false), so tags are only
 * used to clean up leftovers after a crash.
 */
public final class NpcKeys {

    /** harbor:npc = NPC ID (body, click detection, name and marker alike). */
    public static final NamespacedKey NPC = new NamespacedKey("harbor", "npc");

    /** harbor:cutscene = cutscene ID (camera, actors, lines). */
    public static final NamespacedKey CUTSCENE = new NamespacedKey("harbor", "cutscene");

    /**
     * harbor:npc_speech = UUID of the player who sees the speech bubble (hologram dialog style,
     * proposed).
     */
    public static final NamespacedKey SPEECH = new NamespacedKey("harbor", "npc_speech");

    private NpcKeys() {}

    public static void tag(Entity entity, NamespacedKey key, String value) {
        entity.getPersistentDataContainer().set(key, PersistentDataType.STRING, value);
    }

    public static @Nullable String get(Entity entity, NamespacedKey key) {
        return entity.getPersistentDataContainer().get(key, PersistentDataType.STRING);
    }

    public static boolean tagged(Entity entity) {
        var pdc = entity.getPersistentDataContainer();
        return pdc.has(NPC, PersistentDataType.STRING)
                || pdc.has(CUTSCENE, PersistentDataType.STRING)
                || pdc.has(SPEECH, PersistentDataType.STRING);
    }
}
