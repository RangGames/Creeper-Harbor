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
import java.util.Optional;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
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
import wiki.creeper.harbor.common.redis.Channels;
import wiki.creeper.harbor.common.util.Result;
import wiki.creeper.harbor.core.api.FlagService;
import wiki.creeper.harbor.core.api.HarborApi;
import wiki.creeper.harbor.fishing.api.CatchOptions;
import wiki.creeper.harbor.fishing.api.CatchResult;
import wiki.creeper.harbor.npc.api.ReputationService;
import wiki.creeper.harbor.npc.api.ReputationSource;
import wiki.creeper.harbor.season.api.HallOfFameSubmission;
import wiki.creeper.harbor.season.api.LimitResult;
import wiki.creeper.harbor.season.api.SeasonServices;
import wiki.creeper.harbor.season.api.TitleId;
import wiki.creeper.harbor.season.api.WeeklyLimitService;

/**
 * 전리품·고유 ID·지급 (docs 20 10.4).
 *
 * <ol>
 *   <li>CLEARED 즉시 주간 횟수 확인 (WeeklyLimitService.tryConsume, 키 boss:&lt;id&gt;). 한도를 넘으면 클리어 골드의
 *       20%만
 *   <li>드롭 굴리기 (조우 시드 + UUID, 레이드 천장)
 *   <li>한 트랜잭션: boss_participation.rewarded 0 → 1, boss_reward_pending, 골드(BOSS_REWARD 원장, 참조 ID
 *       boss:&lt;조우&gt;), 천장 카운터. 원장 참조 ID·rewarded 조건·pending PK가 같은 보상의 두 번째 지급을 막는다. 트랜잭션이 실패하면
 *       주간 횟수를 되돌린다
 *   <li>메인 스레드에서 pending을 인벤토리에 넣고 PDC harbor:boss_delivered에 ID를 적은 뒤 즉시 저장, 그 뒤 delivered_at
 *   <li>다음 접속 때 delivered_at이 빈 pending은 PDC를 먼저 보고, 있으면 행만 닫고 없으면 다시 지급
 * </ol>
 *
 * 원장 참조 ID는 문서의 boss:&lt;조우&gt;:&lt;uuid&gt; 대신 boss:&lt;조우&gt;를 쓴다 (원장 유니크 키에 플레이어가 이미 들어 있고, 참조
 * ID는 64자 제한이라 UUID 두 개가 들어가지 않는다).
 */
public final class RewardService {

    private final EncounterManager mgr;
    private final HarborApi api;

    RewardService(EncounterManager mgr) {
        this.mgr = mgr;
        this.api = mgr.api();
    }

    // ------------------------------------------------------------------ 정산

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
            if (e.launched() && e.summonItem != null) {
                refund(e.id, e.leader, e.summonItem);
            }
            return;
        }
        Set<UUID> blocked = sameIpBlocked(contributions);
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
                record(e, info, durationMs, endedAt);
            }
            if (def.presentation().announce()) {
                announce(e, info, durationMs);
            }
        } else if (result == EncounterState.FAILED && !def.story()) {
            for (Contribution c : eligible) {
                rewardFail(e, c, name, bb);
            }
        }
    }

    /** 같은 IP 다계정: 기여도가 가장 높은 계정만 보상, 나머지는 막고 로그. */
    private Set<UUID> sameIpBlocked(List<Contribution> contributions) {
        Map<String, UUID> best = new HashMap<>();
        Set<UUID> blocked = new HashSet<>();
        for (Contribution c : contributions) {
            Player p = Bukkit.getPlayer(c.player());
            InetSocketAddress addr = p == null ? null : p.getAddress();
            if (addr == null || addr.getAddress() == null) {
                continue;
            }
            String ip = addr.getAddress().getHostAddress();
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

    /** CLEARED 파티 보스 보상. */
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
                            // 형식이 틀린 행은 무시
                        }
                    });
            double bonus = e.hasQuartermaster() ? bb.quartermasterBonus() : 0;
            LootRoller.Result rolled =
                    LootRoller.roll(
                            def.loot().drops(), random, bonus, pity, def.loot().pityAfter());
            for (LootRoller.Rolled r : rolled.items()) {
                entries.add(
                        new PendingReward.Entry(
                                "item", r.item().toString(), r.count(), uidFor(r.item())));
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
                                            var _ = SeasonServices.limits().refund(uuid, limitKey);
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

    /** 보스 장비(낚싯줄·작살 등 gear·line)면 고유 ID를 붙인다 (추가 제안). */
    private @Nullable String uidFor(ItemId item) {
        return mgr.items()
                .def(item)
                .filter(
                        d ->
                                d.type()
                                                == wiki.creeper.harbor.common.content.type.ItemDef
                                                        .Type.GEAR
                                        || d.type()
                                                == wiki.creeper.harbor.common.content.type.ItemDef
                                                        .Type.LINE)
                .map(d -> UUID.randomUUID().toString())
                .orElse(null);
    }

    /** 실패 참여 보상: 보스 비늘 1개, 주간 횟수 boss_fail:&lt;id&gt; (보스별 주간 한도와 같은 수까지). */
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
                                    var _ =
                                            commit(
                                                            uuid,
                                                            0,
                                                            null,
                                                            con -> {
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

    /** 월드 보스 보상: '등대를 밝힌 자' 칭호(참조 ID worldboss:&lt;이벤트&gt;), 항구 평판, 골드. */
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
     * 골드(있으면)와 추가 SQL을 한 트랜잭션으로. 결과 future는 메인 스레드에서 성공 여부로 완료된다.
     *
     * @param refId 원장 참조 ID (골드가 0이면 쓰지 않음)
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

    // ------------------------------------------------------------------ 기록·공지

    private void record(Encounter e, EncounterInfo info, long durationMs, Instant clearedAt) {
        int season = SeasonServices.clock().current().value();
        List<UUID> members = info.members();
        var record =
                new wiki.creeper.harbor.boss.api.BossRecord(
                        e.def.id(), season, durationMs, e.id, members, clearedAt);
        String leaderName =
                Optional.ofNullable(Bukkit.getPlayer(e.leader)).map(Player::getName).orElse("?");
        Component name =
                members.size() <= 1
                        ? Component.text(leaderName)
                        : api.messages()
                                .get(
                                        "boss.record.party_name",
                                        Placeholder.unparsed("leader", leaderName),
                                        Placeholder.unparsed(
                                                "others", String.valueOf(members.size() - 1)));
        String plain = PlainTextComponentSerializer.plainText().serialize(name);
        var _ =
                api.database()
                        .async(c -> BossRepository.insertRecord(c, record))
                        .thenAcceptAsync(
                                rank -> {
                                    if (rank == 1) {
                                        e.broadcast(
                                                "boss.record.new_best",
                                                Placeholder.unparsed(
                                                        "time",
                                                        EncounterManager.formatMs(durationMs)));
                                    } else if (rank > 0) {
                                        e.broadcast(
                                                "boss.record.ranked",
                                                Placeholder.unparsed("rank", String.valueOf(rank)),
                                                Placeholder.unparsed(
                                                        "time",
                                                        EncounterManager.formatMs(durationMs)));
                                    }
                                    var _ =
                                            SeasonServices.hallOfFame()
                                                    .submit(
                                                            HallOfFameSubmission.fastestBoss(
                                                                    e.def.id().id(),
                                                                    e.leader,
                                                                    plain,
                                                                    durationMs,
                                                                    Map.of(
                                                                            "members",
                                                                            BossRepository
                                                                                    .membersJson(
                                                                                            members),
                                                                            "encounter",
                                                                            e.id.toString()),
                                                                    clearedAt));
                                },
                                mgr.tasks().main())
                        .exceptionally(
                                err -> {
                                    mgr.log()
                                            .warn(
                                                    "보스 기록 저장 실패 {}: {}",
                                                    e.id,
                                                    String.valueOf(err.getMessage()));
                                    return null;
                                });
    }

    private void announce(Encounter e, EncounterInfo info, long durationMs) {
        String leaderName =
                Optional.ofNullable(Bukkit.getPlayer(e.leader)).map(Player::getName).orElse("?");
        Component msg =
                api.messages()
                        .get(
                                e.def.isWorld()
                                        ? "boss.announce.world_cleared"
                                        : "boss.announce.cleared",
                                Placeholder.component("boss", e.bossName()),
                                Placeholder.unparsed("leader", leaderName),
                                Placeholder.unparsed(
                                        "count", String.valueOf(info.members().size())),
                                Placeholder.unparsed(
                                        "time", EncounterManager.formatMs(durationMs)));
        api.bus()
                .publish(
                        Channels.ANNOUNCEMENTS,
                        new Channels.Announcement(MiniMessage.miniMessage().serialize(msg)));
    }

    // ------------------------------------------------------------------ 미끼 환급

    /** 재시작 복구: ABORTED 조우 소환자에게 미끼를 pending으로 (이미 넣었으면 다시 넣지 않음). */
    void refundBait(BossRepository.Aborted a) {
        UUID leader = a.leader();
        String item = a.summonItem();
        if (leader == null || item == null) {
            return;
        }
        try {
            refund(a.id(), leader, new ItemId(HarborId.parse(item)));
        } catch (IllegalArgumentException e) {
            mgr.log().warn("환급할 미끼 ID 형식이 틀렸습니다: {}", item);
        }
    }

    private void refund(UUID encounter, UUID leader, ItemId item) {
        var _ =
                api.database()
                        .asyncVoid(c -> refundInTx(c, encounter, leader, item))
                        .thenRunAsync(
                                () -> {
                                    Player p = Bukkit.getPlayer(leader);
                                    if (p != null) {
                                        api.messages().send(p, "boss.reward.bait_refund");
                                        deliverPending(p, false);
                                    }
                                },
                                mgr.tasks().main());
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

    // ------------------------------------------------------------------ 지급

    /**
     * 미지급 보상 지급 (4·5단계). PDC에 이미 있는 ID는 행만 닫는다. 인벤토리가 모자라면 남겨 두고 /boss claim으로 다시 받게 한다.
     *
     * @param notifyEmpty 받을 것이 없을 때도 알릴지 (/boss claim)
     */
    public void deliverPending(Player player, boolean notifyEmpty) {
        UUID uuid = player.getUniqueId();
        var _ =
                api.database()
                        .async(c -> BossRepository.undelivered(c, uuid))
                        .thenAcceptAsync(
                                list -> deliver(player, list, notifyEmpty), mgr.tasks().main())
                        .exceptionally(
                                err -> {
                                    mgr.log()
                                            .warn(
                                                    "미지급 보상 조회 실패 {}: {}",
                                                    uuid,
                                                    String.valueOf(err.getMessage()));
                                    return null;
                                });
    }

    public CompletableFuture<List<PendingReward>> pending(UUID player) {
        CompletableFuture<List<PendingReward>> out = new CompletableFuture<>();
        var _ =
                api.database()
                        .async(c -> BossRepository.undelivered(c, player))
                        .whenCompleteAsync(
                                (list, err) -> {
                                    if (err != null) {
                                        out.completeExceptionally(err);
                                    } else {
                                        out.complete(list);
                                    }
                                },
                                mgr.tasks().main());
        return out;
    }

    private void deliver(Player player, List<PendingReward> list, boolean notifyEmpty) {
        if (!player.isOnline()) {
            return;
        }
        if (list.isEmpty()) {
            if (notifyEmpty) {
                api.messages().send(player, "boss.reward.nothing");
            }
            return;
        }
        Set<String> done = BossItems.delivered(player);
        List<UUID> close = new ArrayList<>();
        List<String> marked = new ArrayList<>();
        boolean full = false;
        for (PendingReward r : list) {
            if (done.contains(r.id().toString())) {
                close.add(r.id());
                continue;
            }
            List<ItemStack> stacks = build(player, r);
            if (!fits(player, stacks)) {
                full = true;
                continue;
            }
            for (ItemStack s : stacks) {
                var _ = player.getInventory().addItem(s);
                api.messages()
                        .send(
                                player,
                                "boss.reward.item",
                                Placeholder.component("item", s.effectiveName()),
                                Placeholder.unparsed("count", String.valueOf(s.getAmount())));
            }
            marked.add(r.id().toString());
            close.add(r.id());
        }
        if (!marked.isEmpty()) {
            BossItems.markDelivered(player, marked);
        }
        if (full) {
            api.messages().send(player, "boss.reward.inventory_full");
        }
        if (!close.isEmpty()) {
            // 지급한 아이템과 PDC 표시를 먼저 저장한 뒤 DB 보상 행을 닫는다. 반대 순서면 닫은 뒤 서버가 죽었을 때 인벤토리가 지급 전으로
            // 돌아가 보상이 사라진다. 저장이 실패하면 행을 열어 두고, 다음 지급 때 PDC 표시(메모리에 있으면)나 재지급으로 처리된다.
            // 바닐라 saveData()는 Harbor 저장본이 원본이라 소용없다
            UUID uuid = player.getUniqueId();
            var _ =
                    api.players()
                            .saveNow(uuid)
                            .thenCompose(
                                    v ->
                                            api.database()
                                                    .asyncVoid(
                                                            c ->
                                                                    BossRepository.markDelivered(
                                                                            c, close)))
                            .exceptionally(
                                    e -> {
                                        mgr.log()
                                                .warn(
                                                        "보스 보상 지급 기록 실패 {}: {}",
                                                        uuid,
                                                        String.valueOf(e.getMessage()));
                                        return null;
                                    });
        }
    }

    private List<ItemStack> build(Player player, PendingReward r) {
        List<ItemStack> out = new ArrayList<>();
        for (PendingReward.Entry entry : r.entries()) {
            try {
                if ("fish".equals(entry.type())) {
                    BossBalance bb = mgr.balance();
                    Optional<CatchResult> fish =
                            mgr.fishing()
                                    .createCatch(
                                            player,
                                            new FishId(HarborId.parse(entry.id())),
                                            new CatchOptions(
                                                    bb.captureQualityStars(),
                                                    bb.captureLuck(),
                                                    true));
                    fish.map(CatchResult::stack)
                            .ifPresent(
                                    s -> {
                                        if (s != null) {
                                            out.add(s);
                                        }
                                    });
                    continue;
                }
                Optional<ItemStack> stack =
                        mgr.items().create(new ItemId(HarborId.parse(entry.id())), entry.count());
                if (stack.isEmpty()) {
                    mgr.log().warn("보상 아이템이 콘텐츠에 없습니다: {}", entry.id());
                    continue;
                }
                ItemStack s = stack.get();
                String uid = entry.uid();
                if (uid != null) {
                    s.editPersistentDataContainer(
                            pdc ->
                                    pdc.set(
                                            BossItems.BOSS_UID,
                                            org.bukkit.persistence.PersistentDataType.STRING,
                                            uid));
                }
                out.add(s);
            } catch (IllegalArgumentException e) {
                mgr.log().warn("보상 항목 형식이 틀렸습니다: {}", entry);
            }
        }
        return out;
    }

    /** 인벤토리에 다 들어가는지 (빈 칸 수로 보수적으로 본다). */
    private static boolean fits(Player player, List<ItemStack> stacks) {
        int empty = 0;
        for (ItemStack s : player.getInventory().getStorageContents()) {
            if (s == null || s.isEmpty()) {
                empty++;
            }
        }
        return empty >= stacks.size();
    }
}
