package wiki.creeper.harbor.season.content;

import wiki.creeper.harbor.common.content.type.Balance;

/** balance.yml season 절. 값이 없으면 제안값. */
public record SeasonBalance(Balance balance) {

    /** 주차 변경·초기화 시각 확인 주기 (docs 20 11.4: 1분). */
    public int clockCheckSeconds() {
        return Math.max(5, balance.integer("season.clock.check_s", 60));
    }

    /** season_meta(주차 보정·상태)를 DB에서 다시 읽는 주기. */
    public int metaReloadSeconds() {
        return Math.max(30, balance.integer("season.clock.meta_reload_s", 300));
    }

    /** 랭킹 집계 확인 주기 (보드별 refresh가 지났는지 본다). */
    public int rankingCheckSeconds() {
        return Math.max(10, balance.integer("season.ranking.check_s", 60));
    }

    /** harbor:lock:ranking 유지 시간. 집계 주기보다 조금 짧게 두어 한 주기에 한 서버만 집계한다. */
    public int rankingLockSeconds() {
        return Math.max(30, balance.integer("season.ranking.lock_ttl_s", 50));
    }

    /** GUI 한 페이지 줄 수 (보드 size와 별개). */
    public int rankingPageRows() {
        return Math.clamp(balance.integer("season.ranking.page_rows", 5), 1, 5);
    }

    /** 명예의 전당 갱신 공지 여부. */
    public boolean hallOfFameAnnounce() {
        return balance.bool("season.hall_of_fame.announce", true);
    }

    /** 같은 분류·대상 공지 최소 간격 (초, 제안값). */
    public int hallOfFameAnnounceThrottleSeconds() {
        return Math.max(0, balance.integer("season.hall_of_fame.announce_throttle_s", 30));
    }

    /** 새 시즌 안내 Dialog를 띄울지. */
    public boolean gateNotice() {
        return balance.bool("season.gate.notice", true);
    }
}
