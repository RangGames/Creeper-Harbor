package wiki.creeper.harbor.season.content;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.id.RegionId;

/**
 * 시즌 정의 한 개 (season/seasons.yml, docs 20 11.3).
 *
 * @param startDate 시작일
 * @param startTime 시작 시각 (null이면 그날 일일 초기화 시각)
 * @param weeks 시즌 길이 (주, 기본 12 = '7. 밸런스 기획' 1장)
 * @param themeRegion 테마 지역 (없으면 null)
 * @param packUrl 시즌 리소스팩 URL (없으면 null)
 * @param packSha1 리소스팩 SHA-1 (40자리 16진수, packUrl이 있으면 필수)
 * @param contentDir 시즌 콘텐츠 폴더 (콘텐츠 루트 기준, 없으면 null)
 */
public record SeasonDef(
        int season,
        String name,
        LocalDate startDate,
        @Nullable LocalTime startTime,
        int weeks,
        @Nullable RegionId themeRegion,
        @Nullable String packUrl,
        @Nullable String packSha1,
        @Nullable String contentDir) {

    /** 시작 시각. startTime이 없으면 dailyReset을 쓴다. */
    public ZonedDateTime startsAt(ZoneId zone, LocalTime dailyReset) {
        LocalTime time = startTime;
        return startDate.atTime(time == null ? dailyReset : time).atZone(zone);
    }
}
