package wiki.creeper.harbor.boss.logic;

import java.util.Map;
import java.util.UUID;

/** HP formulas ('7. Balance design' chapter 9, docs 20 10.4). */
public final class HpScaling {

    private HpScaling() {}

    /**
     * Party boss max HP = hp.base × (1 + per-player rate × (players − 1)). The player count is
     * fixed to the party size at start.
     *
     * @param players 1 or more (0 or less is treated as 1)
     */
    public static double partyMaxHp(double base, double perPlayerRate, int players) {
        int n = Math.max(1, players);
        return base * (1 + perPlayerRate * (n - 1));
    }

    /**
     * World boss max gauge = single-player base × max(valid participants, minimum players) (D13,
     * minimum 10 confirmed).
     */
    public static double worldMaxHp(double base, int effectiveParticipants, int minParticipants) {
        return base * Math.max(Math.max(1, minParticipants), effectiveParticipants);
    }

    /**
     * Valid participants = players with contribution activity within the last window ticks.
     *
     * @param lastActivity player → last contribution tick
     */
    public static int effectiveParticipants(Map<UUID, Long> lastActivity, long now, long window) {
        int n = 0;
        for (long at : lastActivity.values()) {
            if (now - at <= window) {
                n++;
            }
        }
        return n;
    }
}
