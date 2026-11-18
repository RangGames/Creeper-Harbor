package wiki.creeper.harbor.fishing.sea;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.content.type.FishDef;
import wiki.creeper.harbor.common.id.RegionId;
import wiki.creeper.harbor.fishing.api.SeaEventMessage;
import wiki.creeper.harbor.fishing.api.SeaEventMessage.FishEffect;
import wiki.creeper.harbor.fishing.roll.RollContext.MythicWindow;

/**
 * 진행 중인 해상 이벤트와 fish_effects (docs 20 3.2 harbor:event). harbor:event 메시지로 시작·종료를 받고, 판정 때 이
 * 플레이어(지역)에 적용되는 어종 배율과 신화 창을 돌려준다. Redis가 끊겨도 마지막 상태로 계속하고, 각 이벤트는 endsAt이 지나면 스스로 끝난다. 끝 시각이 없으면
 * 기본 지속 시간(제안값 2시간) 뒤에 끝난다. 메인 스레드 전용, 서버 API 없음 (단위 테스트).
 */
public final class SeaEventBook {

    /** 기본 지속 시간 (endsAt이 없는 START, 제안값). */
    public static final Duration DEFAULT_DURATION = Duration.ofHours(2);

    /** 진행 중인 이벤트. region이 null이면 서버 전체. */
    public record Active(
            String eventId, @Nullable RegionId region, Instant endsAt, List<FishEffect> effects) {

        boolean covers(RegionId at) {
            return region == null || region.equals(at);
        }
    }

    private final Map<String, Active> active = new LinkedHashMap<>();

    /** 메시지를 반영한다. 지역 ID 형식이 틀리면 서버 전체로 보지 않고 무시한다. */
    public void apply(SeaEventMessage message, Instant now) {
        if (message.state() == SeaEventMessage.State.END) {
            active.remove(message.eventId());
            return;
        }
        RegionId region = null;
        String raw = message.region();
        if (raw != null && !raw.isBlank()) {
            try {
                region = RegionId.parse(raw);
            } catch (IllegalArgumentException e) {
                return;
            }
        }
        Instant ends = message.endsAt();
        active.put(
                message.eventId(),
                new Active(
                        message.eventId(),
                        region,
                        ends == null ? now.plus(DEFAULT_DURATION) : ends,
                        message.fishEffects()));
    }

    /** 끝난 이벤트를 지우고 진행 중인 것을 돌려준다. */
    public List<Active> active(Instant now) {
        active.values().removeIf(a -> !now.isBefore(a.endsAt()));
        return List.copyOf(active.values());
    }

    public Set<String> ids(Instant now) {
        active(now);
        return Set.copyOf(active.keySet());
    }

    /** 이 지역의 어종 배율 (이벤트끼리는 곱한다). 키는 어종 ID 문자열. */
    public Map<String, Double> speciesMult(RegionId region, Instant now) {
        Map<String, Double> result = new HashMap<>();
        for (Active a : active(now)) {
            if (!a.covers(region)) {
                continue;
            }
            for (FishEffect e : a.effects()) {
                if (FishEffect.SPECIES_WEIGHT.equals(e.type()) && e.mult() > 0) {
                    result.merge(normalize(e.fish()), e.mult(), (x, y) -> x * y);
                }
            }
        }
        return result;
    }

    /** 이 지역에서 열려 있는 신화 창. 콘텐츠에 없는 어종·신화가 아닌 어종은 건너뛴다. */
    public List<MythicWindow> mythicWindows(
            RegionId region, Instant now, Function<String, Optional<FishDef>> fish) {
        List<MythicWindow> result = new ArrayList<>();
        for (Active a : active(now)) {
            if (!a.covers(region)) {
                continue;
            }
            for (FishEffect e : a.effects()) {
                if (!FishEffect.MYTHIC.equals(e.type()) || e.cap() <= 0) {
                    continue;
                }
                fish.apply(normalize(e.fish()))
                        .ifPresent(
                                def ->
                                        result.add(
                                                new MythicWindow(
                                                        a.eventId(), def, e.chance(), e.cap())));
            }
        }
        return result;
    }

    /** 네임스페이스가 없는 ID(fish/…)에 harbor:를 붙인다. */
    static String normalize(String fish) {
        return fish.contains(":") ? fish : "harbor:" + fish;
    }
}
