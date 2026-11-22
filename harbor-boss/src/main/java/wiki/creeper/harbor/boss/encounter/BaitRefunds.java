package wiki.creeper.harbor.boss.encounter;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.boss.data.BossRepository;
import wiki.creeper.harbor.boss.data.PendingReward;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.common.id.ItemId;
import wiki.creeper.harbor.core.api.HarborApi;

/**
 * Summon bait refunds: pending refund rows for encounters that took the bait but ended early or
 * were ABORTED, including restart recovery inside the closing transaction.
 */
final class BaitRefunds {

    private final EncounterManager mgr;
    private final HarborApi api;

    BaitRefunds(EncounterManager mgr) {
        this.mgr = mgr;
        this.api = mgr.api();
    }

    /**
     * Restart recovery: inside the transaction that closes the encounter as ABORTED, inserts the
     * bait for the summoner as pending (not inserted again if already there). Done separately, a
     * failure after closing but before the refund would remove the basis for a retry (the RUNNING
     * row). Blocking, so call it on a DB thread.
     *
     * @return the summoner if a refund was inserted, otherwise null
     */
    @Nullable UUID refundBaitInTx(Connection c, BossRepository.Aborted a) throws SQLException {
        UUID leader = a.leader();
        String item = a.summonItem();
        if (leader == null || item == null) {
            return null;
        }
        ItemId id;
        try {
            id = new ItemId(HarborId.parse(item));
        } catch (IllegalArgumentException e) {
            mgr.log().warn("환급할 미끼 ID 형식이 틀렸습니다: {}", item);
            return null;
        }
        refundInTx(c, a.id(), leader, id);
        return leader;
    }

    /** Refunds the bait via pending if it was already taken (end during departure, ABORTED). */
    void refundIfTaken(Encounter e) {
        ItemId item = e.summonItem;
        if (e.baitTaken && item != null) {
            refund(e.id, e.leader, item);
        }
    }

    /** Announces and delivers a refund inserted by the restart recovery transaction (if online). */
    void notifyRefund(UUID leader) {
        Player p = Bukkit.getPlayer(leader);
        if (p != null) {
            api.messages().send(p, "boss.reward.bait_refund");
            mgr.rewards().deliverPending(p, false);
        }
    }

    private void refund(UUID encounter, UUID leader, ItemId item) {
        var _ =
                api.database()
                        .asyncVoid(c -> refundInTx(c, encounter, leader, item))
                        .thenRunAsync(() -> notifyRefund(leader), mgr.tasks().main())
                        .exceptionally(
                                err -> {
                                    mgr.log()
                                            .warn(
                                                    "미끼 환급 기록 실패 {}: {}",
                                                    encounter,
                                                    String.valueOf(err.getMessage()));
                                    return null;
                                });
    }

    void refundInTx(Connection c, UUID encounter, UUID leader, ItemId item) throws SQLException {
        BossRepository.insertPendingIgnore(
                c,
                new PendingReward(
                        PendingReward.idFor(encounter, leader, "refund"),
                        encounter,
                        leader,
                        "refund",
                        List.of(new PendingReward.Entry("item", item.toString(), 1, null))));
    }
}
