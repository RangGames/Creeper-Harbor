package wiki.creeper.harbor.season.ranking;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * boss_records.members(JSON) 읽기. 형식: UUID 문자열 배열 {@code ["uuid", …]} 또는 {@code [{"uuid": "…"}, …]}.
 * 첫 번째가 파티장이다 (Boss가 이 형식으로 쓰도록 맞춘다).
 */
public final class BossMembers {

    private BossMembers() {}

    public static List<UUID> parse(@Nullable String json) {
        List<UUID> result = new ArrayList<>();
        if (json == null || json.isBlank()) {
            return result;
        }
        try {
            JsonElement root = JsonParser.parseString(json);
            if (!root.isJsonArray()) {
                return result;
            }
            for (JsonElement e : root.getAsJsonArray()) {
                String raw =
                        e.isJsonObject() && e.getAsJsonObject().has("uuid")
                                ? e.getAsJsonObject().get("uuid").getAsString()
                                : e.getAsString();
                try {
                    result.add(UUID.fromString(raw));
                } catch (IllegalArgumentException ignored) {
                    // 틀린 항목은 건너뛴다
                }
            }
        } catch (RuntimeException e) {
            // 형식이 틀린 기록은 구성원 없이 다룬다
        }
        return result;
    }

    /** players 테이블에서 이름을 찾는다. */
    public static Map<UUID, String> names(Connection c, Collection<UUID> uuids)
            throws SQLException {
        Map<UUID, String> result = new HashMap<>();
        List<UUID> list = uuids.stream().distinct().toList();
        if (list.isEmpty()) {
            return result;
        }
        String marks = String.join(",", Collections.nCopies(list.size(), "?"));
        try (PreparedStatement ps =
                c.prepareStatement(
                        "SELECT uuid, name FROM players WHERE uuid IN (" + marks + ")")) {
            for (int i = 0; i < list.size(); i++) {
                ps.setString(i + 1, list.get(i).toString());
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.put(UUID.fromString(rs.getString(1)), rs.getString(2));
                }
            }
        }
        return result;
    }
}
