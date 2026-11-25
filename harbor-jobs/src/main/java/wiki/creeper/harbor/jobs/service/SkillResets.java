package wiki.creeper.harbor.jobs.service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.db.SqlConsumer;
import wiki.creeper.harbor.common.id.SkillId;
import wiki.creeper.harbor.common.ledger.LedgerEntry;
import wiki.creeper.harbor.common.ledger.LedgerReason;
import wiki.creeper.harbor.common.model.Currency;
import wiki.creeper.harbor.common.model.JobLine;
import wiki.creeper.harbor.common.model.JobTier;
import wiki.creeper.harbor.common.model.SeasonId;
import wiki.creeper.harbor.common.util.Result;
import wiki.creeper.harbor.core.api.HarborPlayer;
import wiki.creeper.harbor.jobs.api.event.JobLineChangedEvent;
import wiki.creeper.harbor.jobs.api.event.SkillResetEvent;
import wiki.creeper.harbor.jobs.content.SkillNode;
import wiki.creeper.harbor.jobs.data.JobsData;
import wiki.creeper.harbor.jobs.data.JobsRepository;

/**
 * Skill tree resets and line changes (docs 20 2.4), plus the admin reset and forced tier commands.
 * Currency changes go through CurrencyService.atomic with the module table update in one
 * transaction. Main thread only.
 */
final class SkillResets {

    private final JobsManager m;

    SkillResets(JobsManager m) {
        this.m = m;
    }

    /**
     * Common tree reset: free once per season, then gold. Common tree investments are refunded 100%
     * (proposed value).
     *
     * @param charge false means no cost (admin)
     * @param refund false means no refund (admin)
     */
    CompletableFuture<Boolean> resetCommon(UUID uuid, boolean charge, boolean refund) {
        HarborPlayer hp = m.api().players().get(uuid).orElseThrow();
        JobsData data = m.data(hp);
        Map<SkillId, SkillNode> nodes = m.nodes();
        List<SkillId> common =
                data.skills().keySet().stream()
                        .filter(
                                id ->
                                        nodes.containsKey(id)
                                                && Objects.requireNonNull(nodes.get(id)).kind()
                                                        == SkillNode.Kind.COMMON)
                        .toList();
        long invested = m.pointsIn(data, n -> n.kind() == SkillNode.Kind.COMMON);
        boolean free = !data.freeResetUsed();
        long cost = charge && !free ? m.balance().commonResetCost() : 0;
        String ref = "rst:" + UUID.randomUUID();
        List<LedgerEntry> entries = new ArrayList<>();
        if (cost > 0) {
            entries.add(new LedgerEntry(uuid, Currency.GOLD, -cost, LedgerReason.SKILL_RESET, ref));
        }
        if (refund && invested > 0) {
            entries.add(
                    new LedgerEntry(
                            uuid, Currency.MULBINEUL, invested, LedgerReason.SKILL_REFUND, ref));
        }
        JobsData next = (charge && free ? data.withFreeResetUsed() : data);
        Map<SkillId, Integer> kept = new HashMap<>(data.skills());
        common.forEach(kept::remove);
        JobsData after = next.withSkills(kept);
        SeasonId season = hp.season();
        SqlConsumer extra =
                c -> {
                    JobsRepository.deleteNodes(c, uuid, season, common);
                    JobsRepository.saveState(c, uuid, season, after);
                };
        long refunded = refund ? invested : 0;
        return runChange(uuid, entries, extra)
                .thenApply(
                        ok -> {
                            if (ok) {
                                hp.set(JobsManager.COMPONENT, after);
                                m.rebuildModifiers(hp);
                                Bukkit.getPluginManager()
                                        .callEvent(new SkillResetEvent(uuid, "common", refunded));
                            }
                            return ok;
                        });
    }

    /**
     * Line change: deducts gold, refunds line tree investments × 0.8 rounded down, keeps the common
     * tree.
     */
    CompletableFuture<Boolean> changeLine(UUID uuid, JobLine newLine, boolean charge) {
        HarborPlayer hp = m.api().players().get(uuid).orElseThrow();
        JobLine oldLine = hp.jobLine().orElse(null);
        if (hp.jobTier() == JobTier.FIRST || oldLine == null || oldLine == newLine) {
            return CompletableFuture.completedFuture(false);
        }
        JobsData data = m.data(hp);
        // 7-day cooldown after a change (docs 20 2.8). Admin changes (charge = false) bypass it
        Duration wait = cooldownLeft(data, m.lineChangeCooldown(), Instant.now());
        if (charge && wait.isPositive()) {
            Player online = Bukkit.getPlayer(uuid);
            if (online != null) {
                sendCooldown(m, online, wait);
            }
            return CompletableFuture.completedFuture(false);
        }
        Instant changedAt = Instant.now();
        Map<SkillId, SkillNode> nodes = m.nodes();
        List<SkillId> lineNodes =
                data.skills().keySet().stream()
                        .filter(
                                id ->
                                        !nodes.containsKey(id)
                                                || Objects.requireNonNull(nodes.get(id)).kind()
                                                        != SkillNode.Kind.COMMON)
                        .toList();
        long invested = m.pointsIn(data, n -> n.kind() != SkillNode.Kind.COMMON);
        long refund = (long) Math.floor(invested * m.balance().refundRatio());
        long cost = charge ? m.balance().lineChangeCost(hp.jobTier()) : 0;
        String ref = "line:" + UUID.randomUUID();
        List<LedgerEntry> entries = new ArrayList<>();
        if (cost > 0) {
            entries.add(new LedgerEntry(uuid, Currency.GOLD, -cost, LedgerReason.LINE_CHANGE, ref));
        }
        if (refund > 0) {
            entries.add(
                    new LedgerEntry(
                            uuid, Currency.MULBINEUL, refund, LedgerReason.SKILL_REFUND, ref));
        }
        Map<SkillId, Integer> kept = new HashMap<>(data.skills());
        lineNodes.forEach(kept::remove);
        SeasonId season = hp.season();
        JobTier tier = hp.jobTier();
        JobsData after = data.withSkills(kept).withCapstoneCleared().withLineChangedAt(changedAt);
        SqlConsumer extra =
                c -> {
                    JobsRepository.deleteNodes(c, uuid, season, lineNodes);
                    JobsRepository.updateJob(c, uuid, season, newLine, tier);
                    JobsRepository.saveState(c, uuid, season, after);
                };
        return runChange(uuid, entries, extra)
                .thenApply(
                        ok -> {
                            if (ok) {
                                hp.setJob(newLine, tier, hp.level(), hp.exp());
                                hp.set(JobsManager.COMPONENT, after);
                                m.rebuildModifiers(hp);
                                Bukkit.getPluginManager()
                                        .callEvent(new JobLineChangedEvent(uuid, oldLine, newLine));
                            }
                            return ok;
                        });
    }

    /** Time left until the line can change again (zero if allowed now). */
    static Duration cooldownLeft(JobsData data, Duration cooldown, Instant now) {
        Instant last = data.lineChangedAt();
        if (last == null || !cooldown.isPositive()) {
            return Duration.ZERO;
        }
        Duration left = Duration.between(now, last.plus(cooldown));
        return left.isNegative() ? Duration.ZERO : left;
    }

    /** Line change cooldown message (days and hours left, hours rounded up). */
    static void sendCooldown(JobsManager m, Player player, Duration left) {
        long hours = Math.max(1, (left.toMinutes() + 59) / 60);
        m.api()
                .messages()
                .send(
                        player,
                        "jobs.npc.change_line_cooldown",
                        Placeholder.unparsed("days", String.valueOf(hours / 24)),
                        Placeholder.unparsed("hours", String.valueOf(hours % 24)));
    }

    /**
     * atomic if there are currency entries, otherwise just the extra SQL in one transaction. Result
     * on the main thread.
     */
    private CompletableFuture<Boolean> runChange(
            UUID uuid, List<LedgerEntry> entries, SqlConsumer extra) {
        if (!m.busy().add(uuid)) {
            return CompletableFuture.completedFuture(false);
        }
        CompletableFuture<Boolean> done =
                entries.isEmpty()
                        ? m.api().database().asyncVoid(extra).thenApply(_ -> true)
                        : m.api().currency().atomic(entries, extra).thenApply(Result::isOk);
        return done.exceptionally(
                        e -> {
                            m.log().error("직업 데이터 변경 실패: {}", uuid, e);
                            return false;
                        })
                .thenApplyAsync(
                        ok -> {
                            m.busy().remove(uuid);
                            return ok;
                        },
                        m.tasks().main());
    }

    /**
     * Admin reset: scope = common · line · all. With refund, investments are refunded 100%; no
     * cost.
     */
    CompletableFuture<Boolean> adminReset(UUID uuid, String scope, boolean refund) {
        if (scope.equals("common")) {
            return resetCommon(uuid, false, refund);
        }
        HarborPlayer hp = m.api().players().get(uuid).orElseThrow();
        JobsData data = m.data(hp);
        Map<SkillId, SkillNode> nodes = m.nodes();
        Predicate<SkillId> target =
                id ->
                        scope.equals("all")
                                || !nodes.containsKey(id)
                                || Objects.requireNonNull(nodes.get(id)).kind()
                                        != SkillNode.Kind.COMMON;
        List<SkillId> removed = data.skills().keySet().stream().filter(target).toList();
        long invested = m.pointsIn(data, n -> target.test(n.id()));
        List<LedgerEntry> entries = new ArrayList<>();
        if (refund && invested > 0) {
            entries.add(
                    new LedgerEntry(
                            uuid,
                            Currency.MULBINEUL,
                            invested,
                            LedgerReason.SKILL_REFUND,
                            "adm:" + UUID.randomUUID()));
        }
        Map<SkillId, Integer> kept = new HashMap<>(data.skills());
        removed.forEach(kept::remove);
        SeasonId season = hp.season();
        long refunded = refund ? invested : 0;
        return runChange(uuid, entries, c -> JobsRepository.deleteNodes(c, uuid, season, removed))
                .thenApply(
                        ok -> {
                            if (ok) {
                                hp.set(JobsManager.COMPONENT, data.withSkills(kept));
                                m.rebuildModifiers(hp);
                                Bukkit.getPluginManager()
                                        .callEvent(new SkillResetEvent(uuid, scope, refunded));
                            }
                            return ok;
                        });
    }

    /** Admin: forcibly sets tier and line (no gold deduction). */
    CompletableFuture<Boolean> forceTier(UUID uuid, JobTier tier, @Nullable JobLine line) {
        HarborPlayer hp = m.api().players().get(uuid).orElseThrow();
        SeasonId season = hp.season();
        JobLine nextLine =
                tier == JobTier.FIRST
                        ? null
                        : line != null ? line : hp.jobLine().orElse(JobLine.EXPLORE);
        return runChange(
                        uuid,
                        List.of(),
                        c -> JobsRepository.updateJob(c, uuid, season, nextLine, tier))
                .thenApply(
                        ok -> {
                            if (ok) {
                                if (nextLine == null) {
                                    hp.setJob(
                                            null,
                                            tier,
                                            Math.min(hp.level(), m.levelCap(tier)),
                                            hp.exp());
                                    m.rebuildModifiers(hp);
                                } else {
                                    m.afterAdvance(hp, tier, nextLine);
                                }
                            }
                            return ok;
                        });
    }
}
