package wiki.creeper.harbor.collection.api;

import java.time.Instant;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.id.FishId;
import wiki.creeper.harbor.common.id.HarborId;

/**
 * 개인 어항 전시 한 칸 (housing_display). 아이템이 아니라 누적 도감 기록을 전시하므로 시즌이 바뀌어도 유지된다.
 *
 * @param slot 수조 자리 번호 (World DisplayAnchor.slot)
 * @param size 전시할 때의 누적 최대 크기
 * @param quality 전시할 때의 누적 최고 품질 (추가 제안 열)
 * @param firstCaught 처음 낚은 시각 (추가 제안 열)
 * @param theme 이 칸의 어항 테마 (null이면 기본 테마, D14: 테마 칸 수 안에서만)
 */
public record DisplaySlot(
        int slot,
        FishId fish,
        double size,
        int quality,
        Instant firstCaught,
        @Nullable HarborId theme) {}
