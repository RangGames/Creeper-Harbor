package wiki.creeper.harbor.npc;

import org.bukkit.NamespacedKey;
import org.bukkit.entity.Entity;
import org.bukkit.persistence.PersistentDataType;
import org.jspecify.annotations.Nullable;

/** NPC·컷씬 엔티티의 PDC 태그. 모든 엔티티는 저장하지 않으므로(persistent = false) 태그는 비정상 종료 잔여물 정리에만 쓴다. */
public final class NpcKeys {

    /** harbor:npc = NPC ID (모습·클릭 판정·이름·표시 모두). */
    public static final NamespacedKey NPC = new NamespacedKey("harbor", "npc");

    /** harbor:cutscene = 컷씬 ID (카메라·배우·대사). */
    public static final NamespacedKey CUTSCENE = new NamespacedKey("harbor", "cutscene");

    /** harbor:npc_speech = 말풍선을 보는 플레이어 UUID (대화 방식 hologram, 추가 제안). */
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
