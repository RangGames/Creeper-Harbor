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

/** Pure rules for instance moves (no server API, unit-tested). */
final class InstanceRules {

    /**
     * Boss rejoin key (docs 20 '17-4' 10.2, expires in 120 seconds). The value is the instance
     * server ID.
     */
    static final String REJOIN_PREFIX = "harbor:boss:rejoin:";

    private static final Pattern SERVER_ID = Pattern.compile("[a-z0-9_-]{1,32}");

    /** Move result of one party member. */
    record MemberResult(UUID player, boolean ok, String reason) {}

    private InstanceRules() {}

    static String rejoinKey(UUID player) {
        return REJOIN_PREFIX + player;
    }

    /**
     * Reads the server ID from the rejoin key value. Boss has not finalized the value format yet,
     * so three forms are accepted: the bare server ID ({@code raid-1}), a form with encounter info
     * after a separator ({@code raid-1|<encounter ID>}), and JSON ({@code
     * {"server":"raid-1",...}}). (Proposed: the bare server ID is recommended)
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
     * Summary returned as MoveResult.reason. {@code ok} if all succeeded, otherwise {@code
     * partial:<succeeded>/<total>:<uuid>=<reason>;...} (those already moved stay and a partial
     * failure is reported; retry decisions belong to the requesting module).
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
