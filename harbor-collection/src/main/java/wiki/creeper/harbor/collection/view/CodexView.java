package wiki.creeper.harbor.collection.view;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.id.FishId;
import wiki.creeper.harbor.common.id.RegionId;
import wiki.creeper.harbor.common.model.Grade;

/**
 * 도감 화면 목록 계산 (추가 제안, 서버 API 없음). 지역 탭·등록 여부·등급으로 거르고 번호·등급·최근 등록 순으로 정렬한다. 화면은 service.CodexMenu.
 */
public final class CodexView {

    /** 등록 여부 필터. */
    public enum Filter {
        ALL,
        UNREGISTERED,
        REGISTERED;

        public String key() {
            return name().toLowerCase(Locale.ROOT);
        }

        public Filter next() {
            return switch (this) {
                case ALL -> UNREGISTERED;
                case UNREGISTERED -> REGISTERED;
                case REGISTERED -> ALL;
            };
        }
    }

    /** 정렬. */
    public enum Sort {
        NUMBER,
        GRADE,
        RECENT;

        public String key() {
            return name().toLowerCase(Locale.ROOT);
        }

        public Sort next() {
            return switch (this) {
                case NUMBER -> GRADE;
                case GRADE -> RECENT;
                case RECENT -> NUMBER;
            };
        }
    }

    /**
     * 어종 한 칸.
     *
     * @param firstCaught 첫 포획 시각 (미등록이면 null)
     */
    public record Item(
            FishId id, int no, Grade grade, RegionId region, @Nullable Instant firstCaught) {

        public boolean registered() {
            return firstCaught != null;
        }
    }

    /**
     * 화면 상태.
     *
     * @param region 지역 탭 (null이면 전체)
     * @param grade 등급 필터 (null이면 전체)
     */
    public record State(
            @Nullable RegionId region, Filter filter, @Nullable Grade grade, Sort sort, int page) {

        public static final State DEFAULT = new State(null, Filter.ALL, null, Sort.NUMBER, 0);

        /** 최근 등록 보기 (등록 어종, 최근 순). */
        public static final State RECENT = new State(null, Filter.REGISTERED, null, Sort.RECENT, 0);

        public State withRegion(@Nullable RegionId r) {
            return new State(r, filter, grade, sort, 0);
        }

        public State withFilter(Filter f) {
            return new State(region, f, grade, sort, 0);
        }

        public State withGrade(@Nullable Grade g) {
            return new State(region, filter, g, sort, 0);
        }

        public State withSort(Sort s) {
            return new State(region, filter, grade, s, 0);
        }

        public State withPage(int p) {
            return new State(region, filter, grade, sort, p);
        }
    }

    private CodexView() {}

    /** 거르고 정렬한다. */
    public static List<Item> apply(List<Item> all, State state) {
        List<Item> out = new ArrayList<>();
        for (Item item : all) {
            if (state.region() != null && !state.region().equals(item.region())) {
                continue;
            }
            if (state.grade() != null && state.grade() != item.grade()) {
                continue;
            }
            boolean keep =
                    switch (state.filter()) {
                        case ALL -> true;
                        case REGISTERED -> item.registered();
                        case UNREGISTERED -> !item.registered();
                    };
            if (keep) {
                out.add(item);
            }
        }
        out.sort(comparator(state.sort()));
        return out;
    }

    static Comparator<Item> comparator(Sort sort) {
        Comparator<Item> byNo = Comparator.comparingInt(Item::no);
        return switch (sort) {
            case NUMBER -> byNo;
            case GRADE ->
                    Comparator.comparing(Item::grade, Comparator.reverseOrder())
                            .thenComparing(byNo);
            case RECENT ->
                    // 등록 어종은 최근 순, 미등록은 뒤에 번호 순
                    Comparator.comparing(
                                    (Item i) ->
                                            i.firstCaught() == null ? Instant.MIN : i.firstCaught(),
                                    Comparator.reverseOrder())
                            .thenComparing(byNo);
        };
    }

    /** 다음 등급 필터: 전체 → 일반 → … → 신화 → 전체. */
    public static @Nullable Grade nextGrade(@Nullable Grade grade) {
        Grade[] all = Grade.values();
        if (grade == null) {
            return all[0];
        }
        for (int i = 0; i < all.length - 1; i++) {
            if (all[i] == grade) {
                return all[i + 1];
            }
        }
        return null;
    }

    /** 페이지 수 (항목이 없어도 1). */
    public static int pages(int count, int perPage) {
        return Math.max(1, (count + perPage - 1) / perPage);
    }

    /** 등록 수 / 전체 수 → 0~100 (전체가 0이면 0). */
    public static double percent(long registered, long total) {
        return total <= 0 ? 0 : 100.0 * registered / total;
    }

    /** 진행 막대 칸 수 (0~width). */
    public static int filled(double ratio, int width) {
        if (!(ratio > 0)) {
            return 0;
        }
        return (int) Math.min(width, Math.floor(Math.min(1.0, ratio) * width + 1e-9));
    }
}
