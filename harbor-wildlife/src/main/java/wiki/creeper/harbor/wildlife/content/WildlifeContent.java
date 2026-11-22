package wiki.creeper.harbor.wildlife.content;

import java.util.List;
import wiki.creeper.harbor.common.content.ContentCheck;
import wiki.creeper.harbor.common.content.ContentType;

/**
 * Wildlife content types. Uses no server API, so harbor-tools (CI) validates with the same code.
 */
public final class WildlifeContent {

    public static final List<ContentType<?>> TYPES = List.of(BehaviourBook.TYPE, WildSpecies.TYPE);

    /**
     * Whether special actions exist in behaviours.yml (a cross-check possible without server API).
     */
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

    /** One species entry per fish species. */
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
