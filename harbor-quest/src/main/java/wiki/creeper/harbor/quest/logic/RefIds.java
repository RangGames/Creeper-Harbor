package wiki.creeper.harbor.quest.logic;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import wiki.creeper.harbor.common.id.QuestId;
import wiki.creeper.harbor.common.ledger.LedgerEntry;

/**
 * 보상 참조 ID (docs 20 6.4 3단계). 원장 유니크가 (uuid, currency, reason, ref_id)라 uuid·재화는 넣지 않는다. 원장 참조 ID는
 * 64자 이하라, 넘으면 같은 입력에서 늘 같은 해시로 줄인다.
 */
public final class RefIds {

    private RefIds() {}

    /** 퀘스트 보상: {@code q:<퀘스트 경로>:<시즌>:<cycle>}. */
    public static String reward(QuestId quest, int season, String cycle) {
        String path = quest.id().path();
        if (path.startsWith("quest/")) {
            path = path.substring("quest/".length());
        }
        return fit("q:" + path + ":" + season + ":" + cycle);
    }

    /** 칭호 (Season TitleService, 멱등): {@code q:<퀘스트 경로>}. */
    public static String title(QuestId quest) {
        String path = quest.id().path();
        return fit("q:" + (path.startsWith("quest/") ? path.substring(6) : path));
    }

    /** 연대기 회상 (D9): {@code recap:<시즌>:<uuid>:<장>}. */
    public static String recap(int season, java.util.UUID player, int chapter) {
        return fit("recap:" + season + ":" + player + ":" + chapter);
    }

    /** 64자를 넘으면 접두사 + SHA-256 앞 40자. */
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
