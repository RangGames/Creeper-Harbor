package wiki.creeper.harbor.npc.api;

import java.util.Locale;
import java.util.Optional;

/**
 * 대화를 보여주는 방식 (추가 제안). dialogs/*.yml 노드 {@code style:} → npcs.yml {@code dialog_style:} →
 * balance.yml {@code npc.dialog.default_style} 순서로 정한다.
 *
 * <ul>
 *   <li>{@link #DIALOG}: Dialog 화면 (기본, 화면을 막음)
 *   <li>{@link #HOLOGRAM}: NPC 머리 위 말풍선 (그 플레이어에게만 보임). 선택지는 채팅 버튼
 *   <li>{@link #TITLE}: 타이틀 = 화자, 서브타이틀 = 대사. 선택지는 채팅 버튼
 *   <li>{@link #CHAT}: 채팅 대사 + 채팅 버튼
 *   <li>{@link #ACTIONBAR}: 액션바 대사 + 채팅 버튼
 * </ul>
 *
 * DIALOG 밖의 방식은 화면을 막지 않아서, 같은 NPC를 다시 우클릭하면 다음 쪽으로 넘어간다.
 */
public enum DialogStyle {
    DIALOG,
    HOLOGRAM,
    TITLE,
    CHAT,
    ACTIONBAR;

    /** YAML 키 (소문자). */
    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** 화면을 막는 방식인지 (Dialog 화면). */
    public boolean modal() {
        return this == DIALOG;
    }

    public static Optional<DialogStyle> parse(String key) {
        String k = key.strip().toLowerCase(Locale.ROOT);
        for (DialogStyle s : values()) {
            if (s.key().equals(k)) {
                return Optional.of(s);
            }
        }
        return Optional.empty();
    }

    /** 오류 문구용 목록 ("dialog, hologram, …"). */
    public static String keys() {
        StringBuilder b = new StringBuilder();
        for (DialogStyle s : values()) {
            if (!b.isEmpty()) {
                b.append(", ");
            }
            b.append(s.key());
        }
        return b.toString();
    }
}
