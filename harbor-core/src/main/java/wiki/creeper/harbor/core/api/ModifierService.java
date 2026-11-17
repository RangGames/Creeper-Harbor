package wiki.creeper.harbor.core.api;

import java.util.List;
import java.util.UUID;

/**
 * 보정자 파이프라인. 플레이어별로 결과를 캐시하고, 보정자가 바뀌거나 만료될 때만 다시 계산한다. 메인 스레드에서 쓴다.
 *
 * <p>스탯별 기본값·상한·하한은 balance.yml의 {@code stats.<키>.base / min / max}에서 읽는다.
 */
public interface ModifierService {

    void add(UUID player, Modifier modifier);

    /** 출처가 같은 보정자를 모두 지운다. */
    void removeSource(UUID player, String source);

    /** 모든 플레이어에게 적용되는 보정자 (해상 이벤트 등). */
    void addGlobal(Modifier modifier);

    void removeGlobalSource(String source);

    /** balance.yml의 기본값으로 계산한다. */
    double value(UUID player, StatKey stat);

    /** 상황마다 기본값이 다를 때 (어종별 매입가 등). */
    double apply(UUID player, StatKey stat, double base);

    /** 출처별 기여 (/harbor stat). */
    Breakdown explain(UUID player, StatKey stat, double base);

    /** 계산 과정. */
    record Breakdown(double base, double result, List<Modifier> applied, double min, double max) {}
}
