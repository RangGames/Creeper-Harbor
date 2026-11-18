package wiki.creeper.harbor.fishing.roll;

import java.util.List;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.content.type.FishDef;
import wiki.creeper.harbor.common.id.FishId;
import wiki.creeper.harbor.common.id.ItemId;
import wiki.creeper.harbor.common.id.RegionId;

/**
 * 출현 판정 컨텍스트 (docs 20 3.4 1단계).
 *
 * @param time dawn · day · dusk · night
 * @param weather clear · rain · thunder
 * @param moon full · new · (그 밖은 빈 문자열)
 * @param serverFlags 서버 진행 플래그 확인 (unlock_flag)
 * @param caught 도감 등록 여부 (미등록 가중치)
 * @param areas 찌 위치의 세부 구역 (RegionKeyResolver)
 * @param areasKnown 세부 구역을 알 수 있는지. false면(World 미등록) 어종 area 조건을 검사하지 않는다
 * @param mythic 열려 있는 신화 창 (이 플레이어가 참가자인 해상 이벤트)
 */
public record RollContext(
        RegionId region,
        String time,
        String weather,
        String moon,
        @Nullable ItemId bait,
        int jobTier,
        Predicate<String> serverFlags,
        Predicate<FishId> caught,
        List<String> areas,
        boolean areasKnown,
        List<MythicWindow> mythic) {

    public RollContext {
        areas = List.copyOf(areas);
        mythic = List.copyOf(mythic);
    }

    /**
     * 신화 창 하나 (해상 이벤트 fish_effects의 mythic).
     *
     * @param chance 캐스팅당 확률 (신화의 예감 mythic_chance를 더하기 전)
     * @param cap 이벤트당 서버 최대 마릿수 (예약은 FishingManager가 한다)
     */
    public record MythicWindow(String eventId, FishDef fish, double chance, int cap) {}
}
