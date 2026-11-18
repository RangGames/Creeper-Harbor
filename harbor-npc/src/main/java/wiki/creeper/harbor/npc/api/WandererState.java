package wiki.creeper.harbor.npc.api;

import java.time.Instant;
import org.bukkit.Location;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.id.NpcId;

/**
 * 떠돌이 NPC의 현재 일정 (docs 20 5.4).
 *
 * @param candidate 이번 칸의 후보 ID (후보가 없으면 null)
 * @param location 후보 위치 (월드가 이 서버에 없으면 null)
 * @param visible 출현 조건(시간·날씨)이 맞아 지금 서 있는지
 * @param nextMoveAt 다음 칸이 시작되는 시각
 * @param forced 관리자 강제 이동 중 (서버 플래그 wanderer/<이름>)
 */
public record WandererState(
        NpcId npc,
        @Nullable String candidate,
        @Nullable Location location,
        boolean visible,
        Instant nextMoveAt,
        boolean forced) {}
