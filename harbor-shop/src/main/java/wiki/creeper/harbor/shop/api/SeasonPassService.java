package wiki.creeper.harbor.shop.api;

import java.util.Set;
import java.util.UUID;
import org.bukkit.plugin.Plugin;

/**
 * 시즌 패스 「항해 일지」 (docs 20 12.2·12.4). 경험치는 평소 플레이로만 오른다. 접속 중인 플레이어만 다루며 메인 스레드 전용.
 *
 * <p><b>경험치 출처 연결 (PassExpSource 등록 API).</b> 양은 shop/pass_s&lt;n&gt;.yml의 exp_sources[출처 키] ×
 * units다 (출처 모듈은 수치를 모른다). Harbor-Quest(QuestCompletedEvent:
 * quest_daily·quest_weekly)·Harbor-World(ChartProgressEvent:
 * chart_percent)·Harbor-Boss(BossEncounterEndEvent: boss_clear)는 Shop이 이벤트 이름으로 직접 구독하므로 그 모듈들은 같은
 * 키를 다시 등록하지 않는다. 그 밖의 새 출처(해상 이벤트 참여 등)는 모듈이 켜질 때 출처를 등록하고 받은 {@link PassExpSink}로 넘긴다.
 *
 * <pre>{@code
 * Harbor.api().service(SeasonPassService.class).ifPresent(pass -> {
 *     PassExpSink sink = pass.registerSource(this, "sea_event");   // pass_s<n>.yml exp_sources.sea_event
 *     // 이벤트 참여 처리 중:  sink.award(player, 1);
 * });
 * }</pre>
 *
 * 기본 제공 출처: {@code catch.<등급>} (Fishing FishCaughtEvent를 Shop이 직접 듣는다).
 */
public interface SeasonPassService {

    /** 현재 단계 (0~최대). 이번 시즌 패스가 없으면 0. */
    int level(UUID player);

    /** 이번 시즌 누적 경험치. */
    long exp(UUID player);

    /** 0 무료 · 1 프리미엄 · 2 프리미엄+. */
    int premium(UUID player);

    /**
     * 출처 키의 경험치 × units를 준다. 출처가 패스 파일에 없거나 플레이어가 접속 중이 아니면 false.
     *
     * @param source exp_sources 키 (예 quest_daily, catch.rare)
     */
    boolean addExp(UUID player, String source, int units);

    /** 경험치 출처를 등록한다. 같은 키를 두 번 등록해도 된다 (같은 싱크). owner가 꺼지면 싱크는 아무 일도 하지 않는다. */
    PassExpSink registerSource(Plugin owner, String source);

    /** 등록된 출처 키 (관리자 화면). */
    Set<String> sources();

    /** 등록한 출처로 경험치를 넘기는 창구. 메인 스레드에서 부른다. */
    interface PassExpSink {

        String source();

        /**
         * @param units 출처 단위 수 (의뢰 1건 = 1, 해도 1% = 1)
         * @return 경험치가 들어갔으면 true
         */
        boolean award(UUID player, int units);
    }
}
