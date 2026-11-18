package wiki.creeper.harbor.fishing.api;

import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.redis.Channel;
import wiki.creeper.harbor.common.redis.Channels;

/**
 * harbor:event 해상 이벤트 시작·종료 메시지 (docs 20 3.2, '11. 데이터 설계' 4장). World의 해상 이벤트 스케줄러가 {@link
 * #CHANNEL}로 발행하고 Fishing이 구독해 fish_effects를 판정에 반영한다. 같은 채널에 다른 형식의 메시지를 보내도 된다 (봉투의 type으로 거른다).
 *
 * <p>Redis가 끊기면 받은 마지막 상태로 계속 판정하고, 각 이벤트는 {@code endsAt}이 지나면 스스로 끝난다.
 *
 * @param eventId 이벤트 실행 ID (예: great_migration). 같은 ID로 다시 START를 보내면 덮어쓴다
 * @param state START 또는 END
 * @param region 이벤트 해역 (null이면 서버 전체). 참가자 = 이 지역에서 낚시하는 플레이어
 * @param endsAt 끝나는 시각 (START에서만 의미)
 * @param fishEffects 판정 효과
 */
public record SeaEventMessage(
        String eventId,
        State state,
        @Nullable String region,
        @Nullable Instant endsAt,
        List<FishEffect> fishEffects) {

    public static final Channel<SeaEventMessage> CHANNEL =
            new Channel<>(Channels.EVENT, SeaEventMessage.class);

    /** 시작·종료. */
    public enum State {
        START,
        END
    }

    public SeaEventMessage {
        fishEffects = fishEffects == null ? List.of() : List.copyOf(fishEffects);
    }

    /**
     * 판정 효과 하나 (추가 제안: {@code [{type: species_weight, fish, mult}, {type: mythic, fish, chance,
     * cap}]}).
     *
     * @param type species_weight(어종 단계 배율, 등급 상한과 별개) 또는 mythic(신화 창)
     * @param fish 어종 ID (예: harbor:fish/migration_leader)
     * @param mult species_weight 배율 (물고기 대이동 ×3)
     * @param chance mythic 캐스팅당 확률 (0이면 balance.yml fishing.mythic.event_chance)
     * @param cap mythic 이벤트당 서버 최대 마릿수
     */
    public record FishEffect(String type, String fish, double mult, double chance, int cap) {

        public static final String SPECIES_WEIGHT = "species_weight";
        public static final String MYTHIC = "mythic";

        public static FishEffect speciesWeight(String fish, double mult) {
            return new FishEffect(SPECIES_WEIGHT, fish, mult, 0, 0);
        }

        public static FishEffect mythic(String fish, double chance, int cap) {
            return new FishEffect(MYTHIC, fish, 1, chance, cap);
        }
    }
}
