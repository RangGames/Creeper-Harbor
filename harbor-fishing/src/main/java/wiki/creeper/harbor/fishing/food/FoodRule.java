package wiki.creeper.harbor.fishing.food;

import java.time.Duration;
import java.time.Instant;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.id.ItemId;
import wiki.creeper.harbor.fishing.content.FoodBuffs;

/**
 * 요리 버프 교체 규칙 (docs 20 0.1, '7. 밸런스 기획' 4.2 "요리 버프는 1종만"). 새 요리를 먹으면 앞의 버프 출처를 지우고 새 버프로 바꾼다. 같은
 * 요리를 다시 먹으면 시간을 새로 시작한다 (더하지 않음). 지속 시간 = 기본 × (1 + food_buff_duration). 서버 API 없음 (단위 테스트).
 */
public final class FoodRule {

    /**
     * 교체 결과.
     *
     * @param removeSource 지워야 할 앞 버프의 출처 (없으면 null)
     * @param next 새로 걸 버프
     */
    public record Replacement(@Nullable String removeSource, ActiveFood next) {}

    private FoodRule() {}

    public static String source(ItemId item) {
        return "food:" + item.id().name();
    }

    public static Duration duration(FoodBuffs.Buff buff, double durationBonus) {
        double mult = Math.max(0, 1 + durationBonus);
        return Duration.ofMillis(Math.round(buff.duration().toMillis() * mult));
    }

    public static Replacement replace(
            @Nullable ActiveFood current, FoodBuffs.Buff buff, Instant now, double durationBonus) {
        // 앞 버프는 끝났든 아니든 출처를 지운다 (남은 보정자 정리)
        String remove = current == null ? null : current.source();
        return new Replacement(
                remove, new ActiveFood(buff.item(), now.plus(duration(buff, durationBonus))));
    }
}
