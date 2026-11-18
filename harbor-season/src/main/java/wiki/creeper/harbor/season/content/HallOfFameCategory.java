package wiki.creeper.harbor.season.content;

import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.model.Keyed;
import wiki.creeper.harbor.season.api.TitleId;

/**
 * 명예의 전당 분류 (season/hall_of_fame.yml, docs 20 11.3·11.4).
 *
 * @param category 분류 ID (소문자·숫자·밑줄)
 * @param name 표시 이름 (MiniMessage)
 * @param source 확정 때 다시 계산할 원천
 * @param rule 순위 규칙
 * @param ascending 낮은 값이 앞서는지 (처치 시간)
 * @param count first_n의 인원 (top1이면 1, all이면 0 = 제한 없음)
 * @param level level_first의 기준 레벨
 * @param title 확정 때 지급할 칭호 (없으면 null). {@code <subject>}가 들어 있으면 대상 이름으로 바꾼다
 * @param unit 값 단위 표시 (cm, 초 …)
 */
public record HallOfFameCategory(
        String category,
        String name,
        Source source,
        Rule rule,
        boolean ascending,
        int count,
        int level,
        @Nullable String title,
        String unit) {

    /** 원천. */
    public enum Source implements Keyed {
        /** Collection records (전체 리그 어종별 1위). Collection이 submit, 확정은 archive_s<n>.records. */
        RECORDS,
        /** Boss boss_records (보스별 최단 처치). Boss가 submit, 확정은 boss_records의 시즌 n 행. */
        BOSS_RECORDS,
        /** 시즌 레벨 선착순. 원장 LEVEL_UP 기록(참조 ID lvl:<시즌>:<uuid>:<레벨>) 시각으로 계산. */
        LEVEL_FIRST,
        /** 시즌 도감 100% 선착순. codex_season의 마지막 첫 포획 시각으로 계산. */
        CODEX_FIRST,
        /** 제출된 잠정 기록을 그대로 확정 (클라이맥스 참여 등). */
        SUBMITTED;

        @Override
        public String key() {
            return Keyed.keyOf(this);
        }
    }

    /** 순위 규칙. */
    public enum Rule implements Keyed {
        /** 대상별 1위 한 명. */
        TOP1,
        /** 먼저 달성한 n명. */
        FIRST_N,
        /** 제출한 전원 (추가 제안: 클라이맥스 참여자 명단). */
        ALL;

        @Override
        public String key() {
            return Keyed.keyOf(this);
        }
    }

    /** 대상별 칭호 ID. title이 없으면 null. */
    public @Nullable TitleId titleFor(String subjectName) {
        String t = title;
        if (t == null) {
            return null;
        }
        return TitleId.tryOf(t.replace("<subject>", subjectName)).orElse(null);
    }
}
