package wiki.creeper.harbor.boss.api;

import java.util.Optional;
import wiki.creeper.harbor.common.model.JobLine;
import wiki.creeper.harbor.common.model.Keyed;
import wiki.creeper.harbor.core.api.PartyService;

/**
 * 보스전 역할 (docs 20 10.4 역할 · 기여도, '4. 낚시 연출 & 보스전' 2.1). 보스 전용 정보(추천 계열 등)는 여기에 두고, 파티 역할로는 {@link
 * #partyRole()}로 옮겨 적는다.
 */
public enum BossRole implements Keyed {
    /** 낚싯대로 보스를 붙잡는다 (텐션). 추천: 수집 계열. */
    ANCHOR,
    /** 작살 투척 (피해). 추천: 탐험 계열. */
    HARPOONER,
    /** 조타륜에서 돌진 회피 (회피). 추천: 탐험 계열. */
    CAPTAIN,
    /** 파손 지점 수리 (수리). 추천: 생산 계열. */
    MECHANIC,
    /** 버프 요리 배포·전리품 보너스 (수리 항목에 지원량 합산). 추천: 경제 계열. */
    QUARTERMASTER;

    /** 직업 계열 추천 역할 (고르지 않은 사람에게 배정). 계열이 없으면 작살수. */
    public static BossRole recommended(Optional<JobLine> line) {
        return line.map(
                        l ->
                                switch (l) {
                                    case COLLECT -> ANCHOR;
                                    case EXPLORE -> HARPOONER;
                                    case PRODUCE -> MECHANIC;
                                    case ECONOMY -> QUARTERMASTER;
                                })
                .orElse(HARPOONER);
    }

    /** Core 파티 역할로 옮길 때의 값. */
    public PartyService.Role partyRole() {
        return switch (this) {
            case ANCHOR -> PartyService.Role.REELER;
            case HARPOONER -> PartyService.Role.HARPOONER;
            case CAPTAIN -> PartyService.Role.CAPTAIN;
            case MECHANIC -> PartyService.Role.MECHANIC;
            case QUARTERMASTER -> PartyService.Role.QUARTERMASTER;
        };
    }

    @Override
    public String key() {
        return Keyed.keyOf(this);
    }
}
