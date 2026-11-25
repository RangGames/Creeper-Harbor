package wiki.creeper.harbor.jobs.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import wiki.creeper.harbor.common.id.SkillId;
import wiki.creeper.harbor.common.ledger.LedgerEntry;
import wiki.creeper.harbor.common.ledger.LedgerError;
import wiki.creeper.harbor.common.ledger.LedgerReason;
import wiki.creeper.harbor.common.model.Currency;
import wiki.creeper.harbor.common.model.SeasonId;
import wiki.creeper.harbor.common.util.Result;
import wiki.creeper.harbor.core.api.HarborPlayer;
import wiki.creeper.harbor.jobs.content.SkillNode;
import wiki.creeper.harbor.jobs.data.JobsData;
import wiki.creeper.harbor.jobs.data.JobsRepository;

/**
 * Join-time grants (docs 20 2.4): the season first-join mulbineul and the full refund of points
 * invested in skill nodes removed from content. Main thread only; DB work runs asynchronously.
 */
final class JoinRewards {

    private final JobsManager m;

    JoinRewards(JobsManager m) {
        this.m = m;
    }

    /** Season first-join mulbineul ref: the level-up scheme with level 1 (docs 20 2.4). */
    static String seasonJoinRef(SeasonId season, UUID uuid) {
        return "lvl:" + season.value() + ":" + uuid + ":1";
    }

    /**
     * Ledger ref for refunding a removed node. Docs 20 2.4 uses removed:<node>:<uuid>; the season
     * is added (proposed) so a node removed again in a later season is refunded again.
     */
    static String removedRef(SeasonId season, SkillId node, UUID uuid) {
        return "removed:" + season.value() + ":" + node + ":" + uuid;
    }

    /**
     * One mulbineul on the first join of each season (reason LEVEL_UP, ref lvl:<season>:<uuid>:1).
     * Together with 69 level-ups this makes 70 per season. The ledger reference is unique, so a
     * duplicate (already granted on another server) just sets the flag.
     */
    void grantSeasonJoin(HarborPlayer hp) {
        if (m.data(hp).joinBonusGiven()) {
            return;
        }
        UUID uuid = hp.uuid();
        var _ =
                m.api()
                        .currency()
                        .give(
                                uuid,
                                Currency.MULBINEUL,
                                1,
                                LedgerReason.LEVEL_UP,
                                seasonJoinRef(hp.season(), uuid))
                        .thenAccept(
                                r -> {
                                    if ((r.isOk() || isError(r, LedgerError.DUPLICATE))
                                            && m.api().players().get(uuid).isPresent()) {
                                        hp.set(
                                                JobsManager.COMPONENT,
                                                m.data(hp).withJoinBonusGiven());
                                    }
                                });
    }

    /**
     * Points invested in nodes removed from content are fully refunded and the rows deleted (docs
     * 20 2.4 edge cases, reason SKILL_REFUND). The amount is the invested total recorded per node
     * in skill_alloc; rows from before that column existed (0) are only deleted and logged.
     */
    void refundRemovedNodes(HarborPlayer hp) {
        Map<SkillId, SkillNode> nodes = m.nodes();
        if (nodes.isEmpty()) {
            // No skill tree loaded: do not treat every investment as removed
            return;
        }
        List<SkillId> removed =
                m.data(hp).skills().keySet().stream().filter(id -> !nodes.containsKey(id)).toList();
        if (removed.isEmpty()) {
            return;
        }
        UUID uuid = hp.uuid();
        SeasonId season = hp.season();
        if (!m.busy().add(uuid)) {
            return; // retried on the next join
        }
        var _ =
                m.api()
                        .database()
                        .async(c -> JobsRepository.invested(c, uuid, season, removed))
                        .thenComposeAsync(
                                invested ->
                                        refund(
                                                uuid,
                                                season,
                                                removed,
                                                entries(hp, removed, invested)),
                                m.tasks().main())
                        .whenCompleteAsync(
                                (ok, e) -> {
                                    m.busy().remove(uuid);
                                    if (e != null) {
                                        m.log().error("사라진 스킬 노드 환급 실패: {}", uuid, e);
                                        return;
                                    }
                                    if (Boolean.TRUE.equals(ok)
                                            && m.api().players().get(uuid).isPresent()) {
                                        JobsData data = m.data(hp);
                                        for (SkillId node : removed) {
                                            data = data.withRank(node, 0);
                                        }
                                        hp.set(JobsManager.COMPONENT, data);
                                        m.rebuildModifiers(hp);
                                    }
                                },
                                m.tasks().main());
    }

    private List<LedgerEntry> entries(
            HarborPlayer hp, List<SkillId> removed, Map<SkillId, Long> invested) {
        UUID uuid = hp.uuid();
        List<LedgerEntry> entries = new ArrayList<>();
        for (SkillId node : removed) {
            long amount = invested.getOrDefault(node, 0L);
            if (amount > 0) {
                entries.add(
                        new LedgerEntry(
                                uuid,
                                Currency.MULBINEUL,
                                amount,
                                LedgerReason.SKILL_REFUND,
                                removedRef(hp.season(), node, uuid)));
            } else {
                m.log().warn("사라진 스킬 노드 {}의 투자액 기록이 없습니다 ({}). 관리자 환급 필요", node, hp.name());
            }
        }
        return entries;
    }

    /**
     * Refund entries + row deletion in one transaction. A DUPLICATE ref means the refund already
     * went through, so the rows are only deleted.
     */
    private CompletableFuture<Boolean> refund(
            UUID uuid, SeasonId season, List<SkillId> removed, List<LedgerEntry> entries) {
        if (entries.isEmpty()) {
            return deleteOnly(uuid, season, removed);
        }
        return m.api()
                .currency()
                .atomic(entries, c -> JobsRepository.deleteNodes(c, uuid, season, removed))
                .thenCompose(
                        r -> {
                            if (r.isOk()) {
                                return CompletableFuture.completedFuture(true);
                            }
                            if (isError(r, LedgerError.DUPLICATE)) {
                                return deleteOnly(uuid, season, removed);
                            }
                            m.log().warn("사라진 스킬 노드 환급 거부 ({}): {}", uuid, r);
                            return CompletableFuture.completedFuture(false);
                        });
    }

    private CompletableFuture<Boolean> deleteOnly(
            UUID uuid, SeasonId season, List<SkillId> removed) {
        return m.api()
                .database()
                .asyncVoid(c -> JobsRepository.deleteNodes(c, uuid, season, removed))
                .thenApply(_ -> true);
    }

    private static boolean isError(Result<?, LedgerError> result, LedgerError error) {
        return result instanceof Result.Err<?, LedgerError> err && err.error() == error;
    }
}
