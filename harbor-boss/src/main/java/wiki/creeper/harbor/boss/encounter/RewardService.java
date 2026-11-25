package wiki.creeper.harbor.boss.encounter;

import java.net.InetSocketAddress;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.boss.api.Contribution;
import wiki.creeper.harbor.boss.api.EncounterInfo;
import wiki.creeper.harbor.boss.api.EncounterState;
import wiki.creeper.harbor.boss.content.BossBalance;
import wiki.creeper.harbor.boss.content.BossDef;
import wiki.creeper.harbor.boss.data.BossRepository;
import wiki.creeper.harbor.boss.data.PendingReward;
import wiki.creeper.harbor.boss.logic.LootRoller;
import wiki.creeper.harbor.common.db.SqlConsumer;
import wiki.creeper.harbor.common.id.FishId;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.common.id.ItemId;
import wiki.creeper.harbor.common.ledger.LedgerEntry;
import wiki.creeper.harbor.common.ledger.LedgerError;
import wiki.creeper.harbor.common.ledger.LedgerException;
import wiki.creeper.harbor.common.ledger.LedgerReason;
import wiki.creeper.harbor.common.model.Currency;
import wiki.creeper.harbor.common.util.Result;
import wiki.creeper.harbor.core.api.FlagService;
import wiki.creeper.harbor.core.api.HarborApi;
import wiki.creeper.harbor.npc.api.ReputationService;
import wiki.creeper.harbor.npc.api.ReputationSource;
import wiki.creeper.harbor.season.api.LimitResult;
import wiki.creeper.harbor.season.api.SeasonServices;
import wiki.creeper.harbor.season.api.TitleId;
import wiki.creeper.harbor.season.api.WeeklyLimitService;

/**
 * Loot, unique IDs and delivery (docs 20 10.4).
 *
 * <ol>
 *   <li>On CLEARED, check the weekly count immediately (WeeklyLimitService.tryConsume, key
 *       boss:&lt;id&gt;). Over the limit, only 20% of the clear gold
 *   <li>Roll drops (encounter seed + UUID, raid pity)
 *   <li>One transaction: boss_participation.rewarded 0 → 1, boss_reward_pending, gold (BOSS_REWARD
 *       ledger, reference ID boss:&lt;encounter&gt;), pity counters. The ledger reference ID, the
 *       rewarded condition and the pending PK block a second grant of the same reward. If the
 *       transaction fails, the weekly count is rolled back
 *   <li>On the main thread, put pending rewards into the inventory, write the ID to PDC
 *       harbor:boss_delivered, save immediately, then set delivered_at
 *   <li>On the next join, pending rows with empty delivered_at check the PDC first: if present,
 *       only the row is closed, otherwise the reward is granted again
 * </ol>
 *
 * The ledger reference ID uses boss:&lt;encounter&gt; instead of the documented
 * boss:&lt;encounter&gt;:&lt;uuid&gt; (the ledger unique key already includes the player, and the
 * reference ID is limited to 64 characters, so two UUIDs do not fit).
 */
public final class RewardService {

    private final EncounterManager mgr;
    private final HarborApi api;
    private final ClearRecords records;
    private final RewardDelivery delivery;
    private final BaitRefunds refunds;

    RewardService(EncounterManager mgr) {
        this.mgr = mgr;
        this.api = mgr.api();
        this.records = new ClearRecords(mgr);
        this.delivery = new RewardDelivery(mgr);
        this.refunds = new BaitRefunds(mgr);
    }

    // ------------------------------------------------------------------ Settlement

    void settle(
            Encounter e,
            EncounterInfo info,
            EncounterState result,
            List<Contribution> contributions,
            long durationMs,
            Instant endedAt,
            boolean releaseOnly) {
        BossDef def = e.def;
        BossBalance bb = e.bb;
        if (result == EncounterState.ABORTED) {
            refundIfTaken(e);
            return;
        }
        Set<UUID> blocked = sameIpBlocked(e, contributions);
        List<Contribution> eligible = new ArrayList<>();
        for (Contribution c : contributions) {
            if (!c.eligible()) {
                e.send(c.player(), "boss.reward.not_eligible");
            } else if (blocked.contains(c.player())) {
                e.send(c.player(), "boss.reward.same_ip");
            } else {
                eligible.add(c);
            }
        }
        String name = def.id().id().name();
        if (result == EncounterState.CLEARED) {
            for (Contribution c : eligible) {
                clearFlags(c.player(), name);
            }
            if (def.story()) {
                return;
            }
            for (Contribution c : eligible) {
                if (def.isWorld()) {
                    rewardWorld(e, c);
                } else {
                    rewardClear(e, c, releaseOnly);
                }
            }
            if (def.isWorld()) {
                var _ = api.flags().setServer("climax_done", "1", false);
            } else if (e.allFromStart()) {
                records.record(e, info, durationMs, endedAt);
            }
            if (def.presentation().announce()) {
                records.announce(e, info, durationMs);
            }
        } else if (result == EncounterState.FAILED && !def.story()) {
            for (Contribution c : eligible) {
                rewardFail(e, c, name, bb);
            }
        }
    }

    /**
     * Multiple accounts on the same IP: only the account with the highest contribution is rewarded;
     * the rest are blocked and logged. Uses the IP remembered on entry so alt accounts that left
     * before the kill are caught too, falling back to the current connection IP.
     */
    private Set<UUID> sameIpBlocked(Encounter e, List<Contribution> contributions) {
        Map<String, UUID> best = new HashMap<>();
        Set<UUID> blocked = new HashSet<>();
        for (Contribution c : contributions) {
            Member m = e.members.get(c.player());
            String ip = m == null ? null : m.ip;
            if (ip == null) {
                Player p = Bukkit.getPlayer(c.player());
                InetSocketAddress addr = p == null ? null : p.getAddress();
                java.net.InetAddress host = addr == null ? null : addr.getAddress();
                if (host == null) {
                    continue;
                }
                ip = host.getHostAddress();
            }
            UUID first = best.putIfAbsent(ip, c.player());
            if (first != null) {
                blocked.add(c.player());
                mgr.log().warn("보스 보상: 같은 IP 다계정 {} (상위 {}) 보상 차단", c.player(), first);
            }
        }
        return blocked;
    }

    private void clearFlags(UUID player, String bossName) {
        String flag = "boss_clear/" + bossName;
        boolean first = !api.flags().hasPlayer(player, flag);
        var _ = api.flags().setPlayer(player, FlagService.Scope.SEASON, flag);
        if (first) {
            api.service(ReputationService.class)
                    .ifPresent(
                            rep -> {
                                var _ =
                                        rep.add(
                                                player,
                                                rep.defaultValue(ReputationSource.BOSS_FIRST_KILL),
                                                ReputationSource.BOSS_FIRST_KILL,
                                                "boss1:" + bossName);
                            });
        }
    }

    private long goldRange(BossDef def, BossBalance bb, SplittableRandom random) {
        long min = def.loot().goldMin();
        long max = def.loot().goldMax();
        if (min < 0) {
            long g = bb.clearGold(def.type());
            return g;
        }
        return LootRoller.gold(min, max, random);
    }

    /** Rewards for a CLEARED party boss. */
    private void rewardClear(Encounter e, Contribution c, boolean releaseOnly) {
        BossDef def = e.def;
        BossBalance bb = e.bb;
        UUID uuid = c.player();
        String key = WeeklyLimitService.bossKey(def.id().id().name());
        var _ =
                SeasonServices.limits()
                        .tryConsume(uuid, key, bb.weeklyLimit(def.type()))
                        .thenAccept(
                                limit -> {
                                    if (!limit.consumed() || def.loot().pityAfter() <= 0) {
                                        grantClear(e, c, limit, Map.of(), releaseOnly, key);
                                        return;
                                    }
                                    int season = SeasonServices.clock().current().value();
                                    var _ =
                                            api.database()
                                                    .async(
                                                            con ->
                                                                    BossRepository.pity(
                                                                            con, uuid, season,
                                                                            def.id()))
                                                    .whenCompleteAsync(
                                                            (pity, err) ->
                                                                    grantClear(
                                                                            e,
                                                                            c,
                                                                            limit,
                                                                            pity == null
                                                                                    ? Map.of()
                                                                                    : pity,
                                                                            releaseOnly,
                                                                            key),
                                                            mgr.tasks().main());
                                })
                        .exceptionally(
                                err -> {
                                    mgr.log()
                                            .warn(
                                                    "주간 횟수 확인 실패 {}: {}",
                                                    uuid,
                                                    String.valueOf(err.getMessage()));
                                    return null;
                                });
    }

    private void grantClear(
            Encounter e,
            Contribution c,
            LimitResult limit,
            Map<String, Integer> pityRaw,
            boolean releaseOnly,
            String limitKey) {
        BossDef def = e.def;
        BossBalance bb = e.bb;
        UUID uuid = c.player();
        SplittableRandom random = new SplittableRandom(LootRoller.seed(e.seed, uuid));
        long gold = goldRange(def, bb, random);
        List<PendingReward.Entry> entries = new ArrayList<>();
        Map<String, Integer> pityOut = new HashMap<>();
        if (limit.consumed()) {
            Map<ItemId, Integer> pity = new HashMap<>();
            pityRaw.forEach(
                    (k, v) -> {
                        try {
                            pity.put(new ItemId(HarborId.parse(k)), v);
                        } catch (IllegalArgumentException ex) {
                            // Ignore malformed rows
                        }
                    });
            double bonus = e.hasQuartermaster() ? bb.quartermasterBonus() : 0;
            LootRoller.Result rolled =
                    LootRoller.roll(
                            def.loot().drops(), random, bonus, pity, def.loot().pityAfter());
            for (LootRoller.Rolled r : rolled.items()) {
                entries.add(
                        new PendingReward.Entry(
                                "item", r.item().toString(), r.count(), uidFor(r.item(), uuid)));
            }
            rolled.pity().forEach((k, v) -> pityOut.put(k.toString(), v));
            if (c.top()) {
                for (LootRoller.Rolled r :
                        LootRoller.roll(def.loot().topBonus(), random, 0, Map.of(), 0).items()) {
                    entries.add(
                            new PendingReward.Entry("item", r.item().toString(), r.count(), null));
                }
            }
            FishId fish = def.fish();
            if (fish != null && def.goal() == BossDef.Goal.CAPTURE && !releaseOnly) {
                entries.add(new PendingReward.Entry("fish", fish.toString(), 1, null));
            }
            HarborId title = def.loot().title();
            if (title != null) {
                var _ = SeasonServices.titles().grant(uuid, new TitleId(title), "boss:" + e.id);
            }
        } else {
            gold = Math.round(gold * bb.overLimitGoldRatio());
            e.send(
                    uuid,
                    "boss.reward.over_limit",
                    Placeholder.unparsed("limit", String.valueOf(limit.limit())));
        }
        PendingReward pending =
                new PendingReward(
                        PendingReward.idFor(e.id, uuid, "loot"), e.id, uuid, "loot", entries);
        int season = SeasonServices.clock().current().value();
        SqlConsumer extra =
                con -> {
                    // The participation row is written separately and asynchronously by recordEnd,
                    // so ordering is not guaranteed. Insert it here first so markRewarded does not
                    // fail for a missing row (if it exists, it is overwritten with the same values
                    // and rewarded is left as is)
                    BossRepository.upsertParticipation(con, e.id, season, List.of(c));
                    if (!BossRepository.markRewarded(con, e.id, uuid)) {
                        throw new LedgerException(LedgerError.REJECTED, "이미 보상한 참가자");
                    }
                    if (!entries.isEmpty()) {
                        BossRepository.insertPending(con, pending);
                    }
                    BossRepository.savePity(con, uuid, season, def.id(), pityOut);
                };
        long goldFinal = gold;
        var _ =
                commit(uuid, goldFinal, "boss:" + e.id, extra)
                        .thenAccept(
                                ok -> {
                                    if (!ok) {
                                        if (limit.consumed()) {
                                            // Refund the count of the week it was consumed in (so a
                                            // week change in between does not touch the new week)
                                            var _ =
                                                    SeasonServices.limits()
                                                            .refund(uuid, limitKey, limit.week());
                                        }
                                        return;
                                    }
                                    if (goldFinal > 0) {
                                        e.send(
                                                uuid,
                                                "boss.reward.gold",
                                                Placeholder.unparsed(
                                                        "gold", String.valueOf(goldFinal)));
                                    }
                                    if (limit.consumed()) {
                                        e.send(
                                                uuid,
                                                "boss.reward.weekly",
                                                Placeholder.unparsed(
                                                        "left", String.valueOf(limit.remaining())),
                                                Placeholder.unparsed(
                                                        "limit", String.valueOf(limit.limit())));
                                    }
                                    Player p = Bukkit.getPlayer(uuid);
                                    if (p != null) {
                                        deliverPending(p, false);
                                    }
                                });
    }

    /**
     * Attaches a unique ID to boss gear (lines, harpoons, etc.; gear/line, proposed) and records it
     * in fish_ledger with item_id ('11. Data design' 3.2, docs 19 5.5). The ledger row is written
     * asynchronously like Core's fish rows; the item also keeps the ID in PDC harbor:boss_uid.
     */
    private @Nullable String uidFor(ItemId item, UUID owner) {
        boolean gear =
                mgr.items()
                        .def(item)
                        .map(
                                d ->
                                        d.type()
                                                        == wiki.creeper.harbor.common.content.type
                                                                .ItemDef.Type.GEAR
                                                || d.type()
                                                        == wiki.creeper.harbor.common.content.type
                                                                .ItemDef.Type.LINE)
                        .orElse(false);
        if (!gear) {
            return null;
        }
        UUID uid = UUID.randomUUID();
        wiki.creeper.harbor.common.model.SeasonId season = SeasonServices.clock().current();
        var _ =
                api.database()
                        .asyncVoid(
                                c ->
                                        wiki.creeper.harbor.core.item.FishLedger.insertItem(
                                                c, uid, item, owner, season))
                        .exceptionally(
                                err -> {
                                    mgr.log()
                                            .error(
                                                    "고유 ID 장부 기록 실패: {} {} {}",
                                                    item,
                                                    uid,
                                                    String.valueOf(err.getMessage()));
                                    return null;
                                });
        return uid.toString();
    }

    /**
     * Participation reward on failure: 1 boss scale, weekly count boss_fail:&lt;id&gt; (up to the
     * same number as the per-boss weekly limit).
     */
    private void rewardFail(Encounter e, Contribution c, String name, BossBalance bb) {
        BossDef.Drop drop = e.def.loot().participation();
        if (drop == null) {
            return;
        }
        UUID uuid = c.player();
        String key = WeeklyLimitService.requireKey("boss_fail:" + name);
        var _ =
                SeasonServices.limits()
                        .tryConsume(uuid, key, bb.weeklyLimit(e.def.type()))
                        .thenAccept(
                                limit -> {
                                    if (!limit.consumed()) {
                                        e.send(uuid, "boss.reward.fail_limit");
                                        return;
                                    }
                                    PendingReward pending =
                                            new PendingReward(
                                                    PendingReward.idFor(
                                                            e.id, uuid, "participation"),
                                                    e.id,
                                                    uuid,
                                                    "participation",
                                                    List.of(
                                                            new PendingReward.Entry(
                                                                    "item",
                                                                    drop.item().toString(),
                                                                    drop.countMin(),
                                                                    null)));
                                    int season = SeasonServices.clock().current().value();
                                    var _ =
                                            commit(
                                                            uuid,
                                                            0,
                                                            null,
                                                            con -> {
                                                                // The participation row may not
                                                                // exist yet (see grantClear)
                                                                BossRepository.upsertParticipation(
                                                                        con,
                                                                        e.id,
                                                                        season,
                                                                        List.of(c));
                                                                if (!BossRepository.markRewarded(
                                                                        con, e.id, uuid)) {
                                                                    throw new LedgerException(
                                                                            LedgerError.REJECTED,
                                                                            "이미 보상한 참가자");
                                                                }
                                                                BossRepository.insertPending(
                                                                        con, pending);
                                                            })
                                                    .thenAccept(
                                                            ok -> {
                                                                if (!ok) {
                                                                    var _ =
                                                                            SeasonServices.limits()
                                                                                    .refund(
                                                                                            uuid,
                                                                                            key);
                                                                    return;
                                                                }
                                                                Player p = Bukkit.getPlayer(uuid);
                                                                if (p != null) {
                                                                    deliverPending(p, false);
                                                                }
                                                            });
                                });
    }

    /**
     * World boss rewards: 'Lighthouse Kindler' title (reference ID worldboss:&lt;event&gt;), harbor
     * reputation, gold.
     */
    private void rewardWorld(Encounter e, Contribution c) {
        BossDef def = e.def;
        UUID uuid = c.player();
        String ref = "worldboss:" + e.id;
        HarborId title = def.loot().title();
        if (title != null) {
            var _ = SeasonServices.titles().grant(uuid, new TitleId(title), ref);
        }
        api.service(ReputationService.class)
                .ifPresent(
                        rep -> {
                            var _ =
                                    rep.add(
                                            uuid,
                                            rep.defaultValue(ReputationSource.WORLD_BOSS),
                                            ReputationSource.WORLD_BOSS,
                                            ref);
                        });
        rewardClear(e, c, true);
    }

    /**
     * Gold (if any) and extra SQL in one transaction. The result future completes on the main
     * thread with whether it succeeded.
     *
     * @param refId ledger reference ID (unused if gold is 0)
     */
    private CompletableFuture<Boolean> commit(
            UUID player, long gold, @Nullable String refId, SqlConsumer extra) {
        CompletableFuture<Boolean> done = new CompletableFuture<>();
        if (gold > 0) {
            var _ =
                    api.currency()
                            .atomic(
                                    List.of(
                                            new LedgerEntry(
                                                    player,
                                                    Currency.GOLD,
                                                    gold,
                                                    LedgerReason.BOSS_REWARD,
                                                    refId)),
                                    extra)
                            .thenAccept(
                                    r -> {
                                        if (r
                                                instanceof
                                                Result.Err<List<Long>, LedgerError>(
                                                        LedgerError err)) {
                                            mgr.log().warn("보스 보상 트랜잭션 거부 {}: {}", player, err);
                                            done.complete(false);
                                        } else {
                                            done.complete(true);
                                        }
                                    });
            return done;
        }
        var _ =
                api.database()
                        .asyncVoid(extra)
                        .whenCompleteAsync(
                                (v, err) -> {
                                    if (err != null) {
                                        mgr.log()
                                                .warn(
                                                        "보스 보상 기록 실패 {}: {}",
                                                        player,
                                                        String.valueOf(err.getMessage()));
                                    }
                                    done.complete(err == null);
                                },
                                mgr.tasks().main());
        return done;
    }

    // ------------------------------------------------------------------ Bait refunds (BaitRefunds)

    /**
     * Restart recovery: inside the transaction that closes the encounter as ABORTED, inserts the
     * bait for the summoner as pending (not inserted again if already there). Done separately, a
     * failure after closing but before the refund would remove the basis for a retry (the RUNNING
     * row). Blocking, so call it on a DB thread.
     *
     * @return the summoner if a refund was inserted, otherwise null
     */
    @Nullable UUID refundBaitInTx(Connection c, BossRepository.Aborted a) throws SQLException {
        return refunds.refundBaitInTx(c, a);
    }

    /** Refunds the bait via pending if it was already taken (end during departure, ABORTED). */
    void refundIfTaken(Encounter e) {
        refunds.refundIfTaken(e);
    }

    /** Announces and delivers a refund inserted by the restart recovery transaction (if online). */
    void notifyRefund(UUID leader) {
        refunds.notifyRefund(leader);
    }

    void refundInTx(Connection c, UUID encounter, UUID leader, ItemId item) throws SQLException {
        refunds.refundInTx(c, encounter, leader, item);
    }

    // ------------------------------------------------------------------ Delivery (RewardDelivery)

    /**
     * Delivers unclaimed rewards (steps 4-5). IDs already in the PDC only close their row. If the
     * inventory is short on space, the rest stays to be claimed again with /boss claim.
     *
     * @param notifyEmpty whether to notify even when there is nothing to receive (/boss claim)
     */
    public void deliverPending(Player player, boolean notifyEmpty) {
        delivery.deliverPending(player, notifyEmpty);
    }

    public CompletableFuture<List<PendingReward>> pending(UUID player) {
        return delivery.pending(player);
    }
}
