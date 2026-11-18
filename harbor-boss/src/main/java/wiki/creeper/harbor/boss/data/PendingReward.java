package wiki.creeper.harbor.boss.data;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import wiki.creeper.harbor.common.util.Json;

/**
 * 미지급 보상 한 건 (boss_reward_pending.payload). 지급은 메인 스레드에서 인벤토리에 넣고 PDC harbor:boss_delivered에 ID를 적은
 * 뒤 즉시 저장하고, 저장이 끝나면 delivered_at을 적는다 (docs 20 10.4 4~5단계).
 *
 * @param kind 종류 (loot · top · participation · refund · fish)
 */
public record PendingReward(
        UUID id, UUID encounter, UUID player, String kind, List<Entry> entries) {

    /**
     * 지급할 것 하나.
     *
     * @param type item (아이템 ID) · fish (보스 어종 ID, 지급할 때 Fishing으로 만든다)
     * @param uid 보스 장비 고유 ID (추가 제안: 아이템 PDC harbor:boss_uid, 없으면 null)
     */
    public record Entry(
            String type, String id, int count, @org.jspecify.annotations.Nullable String uid) {}

    /** payload JSON (id·encounter·player는 열에 따로 있다). */
    private record Payload(String kind, List<Entry> entries) {}

    public PendingReward {
        entries = List.copyOf(entries);
    }

    /** (조우, 플레이어, 종류)로 정해지는 ID. 같은 보상을 다시 넣으면 PK 충돌로 막힌다. */
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
