package wiki.creeper.harbor.boss.data;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import wiki.creeper.harbor.common.util.Json;

/**
 * One undelivered reward (boss_reward_pending.payload). Delivery puts it into the inventory on the
 * main thread, writes the ID to PDC harbor:boss_delivered, saves immediately, and writes
 * delivered_at once the save finishes (docs 20 10.4 steps 4-5).
 *
 * @param kind kind (loot · top · participation · refund · fish)
 */
public record PendingReward(
        UUID id, UUID encounter, UUID player, String kind, List<Entry> entries) {

    /**
     * One thing to grant.
     *
     * @param type item (item ID) · fish (boss fish ID, created through Fishing on delivery)
     * @param uid unique boss gear ID (proposed: item PDC harbor:boss_uid, null if none)
     */
    public record Entry(
            String type, String id, int count, @org.jspecify.annotations.Nullable String uid) {}

    /** payload JSON (id, encounter and player are separate columns). */
    private record Payload(String kind, List<Entry> entries) {}

    public PendingReward {
        entries = List.copyOf(entries);
    }

    /**
     * ID determined by (encounter, player, kind). Inserting the same reward again is blocked by a
     * PK conflict.
     */
    public static UUID idFor(UUID encounter, UUID player, String kind) {
        return UUID.nameUUIDFromBytes(
                ("boss:" + encounter + ":" + player + ":" + kind).getBytes(StandardCharsets.UTF_8));
    }

    public String payloadJson() {
        return Json.GSON.toJson(new Payload(kind, entries));
    }

    public static PendingReward fromJson(UUID id, UUID encounter, UUID player, String json) {
        Payload p = Json.GSON.fromJson(json, Payload.class);
        if (p == null) {
            return new PendingReward(id, encounter, player, "unknown", List.of());
        }
        String kind = p.kind();
        List<Entry> entries = p.entries();
        return new PendingReward(
                id,
                encounter,
                player,
                kind == null ? "unknown" : kind,
                entries == null ? List.of() : entries);
    }
}
