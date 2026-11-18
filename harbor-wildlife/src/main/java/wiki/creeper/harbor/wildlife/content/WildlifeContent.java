package wiki.creeper.harbor.wildlife.content;

import java.util.List;
import wiki.creeper.harbor.common.content.ContentCheck;
import wiki.creeper.harbor.common.content.ContentType;

/** Wildlife 콘텐츠 형식. 서버 API를 쓰지 않아 harbor-tools(CI)도 같은 코드로 검사한다. */
public final class WildlifeContent {

    public static final List<ContentType<?>> TYPES = List.of(BehaviourBook.TYPE, WildSpecies.TYPE);

    /** 고유 동작이 behaviours.yml에 있는지 (서버 API 없이 할 수 있는 교차 검사). */
    public static final ContentCheck<WildSpecies> SPECIAL_CHECK =
            (s, check) -> {
                String special = s.special();
                if (special != null
                        && !check.snapshot()
                                .single(BehaviourBook.TYPE)
                                .actions()
                                .containsKey(special)) {
                    check.error("special '" + special + "'가 wildlife/behaviours.yml actions에 없습니다");
                }
            };

    /** 어종 하나에 species 항목 하나. */
    public static final ContentCheck<WildSpecies> FISH_UNIQUE =
            (s, check) -> {
                for (WildSpecies other : check.snapshot().all(WildSpecies.TYPE).values()) {
                    if (other.fish().equals(s.fish()) && other.id().compareTo(s.id()) < 0) {
                        check.error(
                                "fish_id " + s.fish() + "가 " + other.id() + "에도 있습니다 (어종당 항목 하나)");
                        return;
                    }
                }
            };

    private WildlifeContent() {}
}
