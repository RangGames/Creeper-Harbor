package wiki.creeper.harbor.proxy;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/** 인스턴스 이동의 순수 규칙 (서버 API 없음, 단위 테스트 대상). */
final class InstanceRules {

    /** Boss 재접속 키 (docs 20 '17-4' 10.2, 만료 120초). 값은 인스턴스 서버 ID. */
    static final String REJOIN_PREFIX = "harbor:boss:rejoin:";

    private static final Pattern SERVER_ID = Pattern.compile("[a-z0-9_-]{1,32}");

    /** 파티원 한 명의 이동 결과. */
    record MemberResult(UUID player, boolean ok, String reason) {}

    private InstanceRules() {}

    static String rejoinKey(UUID player) {
        return REJOIN_PREFIX + player;
    }

    /**
     * 재접속 키 값에서 서버 ID를 읽는다. Boss가 값 형식을 확정하기 전이라 세 가지를 받는다: 서버 ID 그대로({@code raid-1}), 구분자 뒤에 조우
     * 정보가 붙은 형식({@code raid-1|<조우 ID>}), JSON({@code {"server":"raid-1",...}}). (추가 제안: 서버 ID 그대로를
     * 권장)
     */
    static Optional<String> parseRejoin(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String value = raw.strip();
        String server;
        if (value.startsWith("{")) {
            try {
                JsonObject json = JsonParser.parseString(value).getAsJsonObject();
                JsonElement field = json.get("server");
                if (field == null || !field.isJsonPrimitive()) {
                    return Optional.empty();
                }
                server = field.getAsString();
            } catch (JsonParseException | IllegalStateException e) {
                return Optional.empty();
            }
        } else {
            int bar = value.indexOf('|');
            server = bar < 0 ? value : value.substring(0, bar);
        }
        return SERVER_ID.matcher(server).matches() ? Optional.of(server) : Optional.empty();
    }

    /**
     * MoveResult.reason으로 돌려줄 요약. 전원 성공이면 {@code ok}, 아니면 {@code partial:<성공>/<전체>:<uuid>=<사유>;...}
     * (이미 옮긴 인원은 그대로 두고 부분 실패를 알린다. 재시도 판단은 요청 모듈 몫).
     */
    static String summarize(List<MemberResult> results) {
        long ok = results.stream().filter(MemberResult::ok).count();
        if (ok == results.size()) {
            return "ok";
        }
        StringBuilder out =
                new StringBuilder("partial:").append(ok).append('/').append(results.size());
        char sep = ':';
        for (MemberResult result : results) {
            if (!result.ok()) {
                out.append(sep).append(result.player()).append('=').append(result.reason());
                sep = ';';
            }
        }
        return out.toString();
    }
}
