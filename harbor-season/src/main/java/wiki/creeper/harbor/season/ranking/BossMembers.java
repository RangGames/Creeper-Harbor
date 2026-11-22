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
 * Reads boss_records.members (JSON). Format: an array of UUID strings {@code ["uuid", …]} or {@code
 * [{"uuid": "…"}, …]}. The first one is the party leader (Boss writes in this format).
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
                    // Skip malformed entries
                }
            }
        } catch (RuntimeException e) {
            // Malformed records are treated as having no members
        }
        return result;
    }

    /** Looks up names in the players table. */
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
