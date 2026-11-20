package wiki.creeper.harbor.skript;

import java.util.Locale;
import java.util.Optional;
import wiki.creeper.harbor.common.id.HarborId;

/**
 * 스크립트가 적은 ID 글 → Harbor ID (서버 API 없음). {@code quest/main/x} · {@code harbor:quest/main/x} 둘 다 받고,
 * 앞의 종류를 빼먹으면 붙인다 ({@code main/x} + 접두사 quest/ → quest/main/x). 형식이 틀리면 빈 값.
 */
public final class Ids {

    private Ids() {}

    public static Optional<HarborId> parse(String raw, String prefix) {
        String s = raw.strip().toLowerCase(Locale.ROOT);
        if (s.isEmpty()) {
            return Optional.empty();
        }
        String path = s.startsWith("harbor:") ? s.substring("harbor:".length()) : s;
        if (!path.startsWith(prefix)) {
            path = prefix + path;
        }
        return HarborId.tryParse(path).filter(i -> i.hasPrefix(prefix));
    }

    /** 플래그 이름 (소문자·숫자·밑줄, / 구분). */
    public static boolean flag(String raw) {
        return wiki.creeper.harbor.core.api.FlagService.NAME.matcher(raw).matches();
    }
}
