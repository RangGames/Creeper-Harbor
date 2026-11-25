package wiki.creeper.harbor.jobs.service;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import wiki.creeper.harbor.common.id.SkillId;
import wiki.creeper.harbor.common.ledger.LedgerEntry;
import wiki.creeper.harbor.common.ledger.LedgerError;
import wiki.creeper.harbor.common.ledger.LedgerReason;
import wiki.creeper.harbor.common.model.Currency;
import wiki.creeper.harbor.common.model.JobLine;
import wiki.creeper.harbor.common.model.SeasonId;
import wiki.creeper.harbor.common.util.Result;
import wiki.creeper.harbor.core.api.HarborPlayer;
import wiki.creeper.harbor.jobs.api.InvestResult;
import wiki.creeper.harbor.jobs.api.event.SkillAllocatedEvent;
import wiki.creeper.harbor.jobs.content.JobsBalance;
import wiki.creeper.harbor.jobs.content.SkillNode;
import wiki.creeper.harbor.jobs.data.JobsData;
import wiki.creeper.harbor.jobs.data.JobsRepository;

/**
 * Skill investment (docs 20 2.4 SkillAllocator): the check order and the one-rank investment
 * transaction. Main thread only.
 */
final class SkillAllocator {

    private final JobsManager m;

    SkillAllocator(JobsManager m) {
        this.m = m;
    }

    /** Whether investment is possible (docs 20 2.4 check order). OK means it is. */
    InvestResult check(HarborPlayer hp, SkillId nodeId) {
        SkillNode node = m.nodes().get(nodeId);
        if (node == null) {
            return InvestResult.UNKNOWN_NODE;
        }
        JobsBalance b = m.balance();
        JobsData data = m.data(hp);
        JobLine myLine = hp.jobLine().orElse(null);
        int tier = hp.jobTier().level();
        switch (node.kind()) {
            case COMMON -> {}
            case LINE_SECOND -> {
                if (tier < 2) {
                    return InvestResult.JOB_LOCKED;
                }
                if (node.line() != myLine) {
                    // Other lines' 2nd tier trees allow up to 40 total after the 3rd advancement
                    long other =
                            m.pointsIn(
                                    data,
                                    n ->
                                            n.kind() == SkillNode.Kind.LINE_SECOND
                                                    && n.line() != myLine);
                    if (tier < 3 || other + node.cost() > b.otherLineLimit()) {
                        return InvestResult.JOB_LOCKED;
                    }
                }
            }
            case LINE_THIRD -> {
                if (tier < 3 || node.line() != myLine) {
                    return InvestResult.JOB_LOCKED;
                }
            }
            case CAPSTONE -> {
                long third =
                        m.pointsIn(
                                data,
                                n -> n.kind() == SkillNode.Kind.LINE_THIRD && n.line() == myLine);
                if (tier < 3 || node.line() != myLine || third < b.capstoneRequires()) {
                    return InvestResult.JOB_LOCKED;
                }
            }
        }
        long inTree =
                m.pointsIn(data, n -> n.tree().equals(node.tree()) && n.line() == node.line());
        if (inTree < b.tierPoints(node.tier()) || inTree < node.pointsInTree()) {
            return InvestResult.TIER_LOCKED;
        }
        for (SkillId required : node.requiredNodes()) {
            if (data.rank(required) <= 0) {
                return InvestResult.REQUIRES_NODES;
            }
        }
        if (data.rank(nodeId) >= node.maxRank()) {
            return InvestResult.MAX_RANK;
        }
        if (hp.balance(Currency.MULBINEUL) < node.cost()) {
            return InvestResult.NOT_ENOUGH_MULBINEUL;
        }
        return InvestResult.OK;
    }

    /**
     * Invests one rank. Mulbineul deduction (SKILL_INVEST) and the skill_alloc update (rank and
     * invested total) in one transaction.
     */
    CompletableFuture<InvestResult> invest(Player player, SkillId nodeId) {
        HarborPlayer hp = m.api().players().get(player);
        UUID uuid = hp.uuid();
        InvestResult result = check(hp, nodeId);
        if (result != InvestResult.OK) {
            return CompletableFuture.completedFuture(result);
        }
        if (!m.busy().add(uuid)) {
            return CompletableFuture.completedFuture(InvestResult.BUSY);
        }
        SkillNode node = Objects.requireNonNull(m.nodes().get(nodeId));
        int rank = m.data(hp).rank(nodeId) + 1;
        long cost = node.cost();
        SeasonId season = hp.season();
        String ref = "inv:" + UUID.randomUUID();
        return m.api()
                .currency()
                .atomic(
                        List.of(
                                new LedgerEntry(
                                        uuid,
                                        Currency.MULBINEUL,
                                        -cost,
                                        LedgerReason.SKILL_INVEST,
                                        ref)),
                        c -> JobsRepository.setRank(c, uuid, season, nodeId, rank, cost))
                .thenApply(
                        r -> {
                            m.busy().remove(uuid);
                            if (r
                                    instanceof
                                    Result.Err<List<Long>, LedgerError>(LedgerError error)) {
                                return error == LedgerError.INSUFFICIENT_FUNDS
                                        ? InvestResult.NOT_ENOUGH_MULBINEUL
                                        : InvestResult.FAILED;
                            }
                            hp.set(JobsManager.COMPONENT, m.data(hp).withRank(nodeId, rank));
                            m.rebuildModifiers(hp);
                            Bukkit.getPluginManager()
                                    .callEvent(new SkillAllocatedEvent(uuid, nodeId, rank));
                            return InvestResult.OK;
                        });
    }
}
