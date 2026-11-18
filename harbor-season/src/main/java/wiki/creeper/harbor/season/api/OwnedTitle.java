package wiki.creeper.harbor.season.api;

import java.time.Instant;

/**
 * 보유 칭호 한 개 (titles 행).
 *
 * @param season 얻은 시즌 (표시용. 칭호는 시즌 초기화 대상이 아니다)
 */
public record OwnedTitle(TitleId title, int season, Instant obtainedAt) {}
