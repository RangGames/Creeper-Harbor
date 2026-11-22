package wiki.creeper.harbor.quest.logic;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import wiki.creeper.harbor.common.id.QuestId;
import wiki.creeper.harbor.common.ledger.LedgerEntry;

/**
 * Reward reference IDs (docs 20 6.4 step 3). The ledger unique key is (uuid, currency, reason,
 * ref_id), so uuid and currency are not included. Ledger reference IDs are at most 64 characters;
 * longer ones are shortened to a hash that is always the same for the same input.
 */
public final class RefIds {

    private RefIds() {}

    /** Quest reward: {@code q:<quest path>:<season>:<cycle>}. */
    public static String reward(QuestId quest, int season, String cycle) {
        String path = quest.id().path();
        if (path.startsWith("quest/")) {
            path = path.substring("quest/".length());
        }
        return fit("q:" + path + ":" + season + ":" + cycle);
    }

    /** Title (Season TitleService, idempotent): {@code q:<quest path>}. */
    public static String title(QuestId quest) {
        String path = quest.id().path();
        return fit("q:" + (path.startsWith("quest/") ? path.substring(6) : path));
    }

    /** Chronicle recap (D9): {@code recap:<season>:<uuid>:<chapter>}. */
    public static String recap(int season, java.util.UUID player, int chapter) {
        return fit("recap:" + season + ":" + player + ":" + chapter);
    }

    /** Over 64 characters: prefix + first 40 characters of SHA-256. */
    public static String fit(String raw) {
        if (raw.length() <= LedgerEntry.MAX_REF_LENGTH) {
            return raw;
        }
        int colon = raw.indexOf(':');
        String prefix = colon > 0 && colon < 8 ? raw.substring(0, colon + 1) : "h:";
        try {
            byte[] digest =
                    MessageDigest.getInstance("SHA-256")
                            .digest(raw.getBytes(StandardCharsets.UTF_8));
            return prefix + HexFormat.of().formatHex(digest).substring(0, 40);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256을 쓸 수 없습니다", e);
        }
    }
}
