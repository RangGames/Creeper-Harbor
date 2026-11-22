package wiki.creeper.harbor.jobs.service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import wiki.creeper.harbor.common.ledger.LedgerEntry;
import wiki.creeper.harbor.common.ledger.LedgerError;
import wiki.creeper.harbor.common.ledger.LedgerReason;
import wiki.creeper.harbor.common.model.Currency;
import wiki.creeper.harbor.common.model.JobLine;
import wiki.creeper.harbor.common.model.JobTier;
import wiki.creeper.harbor.common.model.SeasonId;
import wiki.creeper.harbor.common.util.Result;
import wiki.creeper.harbor.core.api.Dialogs;
import wiki.creeper.harbor.core.api.HarborPlayer;
import wiki.creeper.harbor.core.api.HarborSounds;
import wiki.creeper.harbor.core.task.Sequence;
import wiki.creeper.harbor.jobs.api.AdvanceResult;
import wiki.creeper.harbor.jobs.api.event.JobAdvancedEvent;
import wiki.creeper.harbor.jobs.content.JobsBalance;
import wiki.creeper.harbor.jobs.data.JobsData;
import wiki.creeper.harbor.jobs.data.JobsRepository;

/**
 * Job advancement flow (docs 20 2.4): line choice and confirmation dialogs, the gold payment
 * transaction, and the post-commit cache, passives, overflow, event and cutscene. Main thread only.
 */
final class JobAdvancement {

    private static final Duration DIALOG_TIMEOUT = Duration.ofMinutes(5);

    private final JobsManager m;

    JobAdvancement(JobsManager m) {
        this.m = m;
    }

    CompletableFuture<AdvanceResult> request(Player player, JobTier target) {
        HarborPlayer hp = m.api().players().get(player);
        JobsBalance b = m.balance();
        if (m.busy().contains(hp.uuid())) {
            return CompletableFuture.completedFuture(AdvanceResult.BUSY);
        }
        if (target.level() != hp.jobTier().level() + 1) {
            return CompletableFuture.completedFuture(AdvanceResult.WRONG_TIER);
        }
        if (hp.level() < b.advanceLevel(target)) {
            return CompletableFuture.completedFuture(AdvanceResult.LEVEL_TOO_LOW);
        }
        long cost = b.advanceCost(target);
        if (hp.balance(Currency.GOLD) < cost) {
            return CompletableFuture.completedFuture(AdvanceResult.NOT_ENOUGH_GOLD);
        }
        CompletableFuture<AdvanceResult> result = new CompletableFuture<>();
        var _ =
                m.tasks()
                        .later(
                                DIALOG_TIMEOUT.toSeconds() * 20,
                                () -> result.complete(AdvanceResult.CANCELLED));
        if (target == JobTier.SECOND) {
            List<JobLine> lines = List.of(JobLine.values());
            List<Dialogs.Choice> choices = new ArrayList<>();
            for (JobLine line : lines) {
                String name = m.lineDef(line).map(d -> d.name(2)).orElse(line.key());
                choices.add(new Dialogs.Choice(Component.text(name), passiveSummary(line)));
            }
            m.api()
                    .dialogs()
                    .choose(
                            player,
                            m.api().messages().get("jobs.advance.choose_title"),
                            List.of(m.api().messages().get("jobs.advance.choose_body")),
                            choices,
                            2,
                            index ->
                                    confirmAdvance(player, target, lines.get(index), cost, result));
        } else {
            hp.jobLine()
                    .ifPresentOrElse(
                            line -> confirmAdvance(player, target, line, cost, result),
                            () -> result.complete(AdvanceResult.FAILED));
        }
        return result;
    }

    private Component passiveSummary(JobLine line) {
        StringBuilder text = new StringBuilder();
        m.lineDef(line)
                .ifPresent(
                        def ->
                                def.passives().stream()
                                        .filter(p -> p.tier() == 2)
                                        .forEach(
                                                p ->
                                                        text.append(p.stat())
                                                                .append(' ')
                                                                .append(
                                                                        JobsManager.signed(
                                                                                p.value()))
                                                                .append('\n')));
        return Component.text(text.toString().strip());
    }

    private void confirmAdvance(
            Player player,
            JobTier target,
            JobLine line,
            long cost,
            CompletableFuture<AdvanceResult> result) {
        String name = m.lineDef(line).map(d -> d.name(target.level())).orElse(line.key());
        m.api()
                .dialogs()
                .confirm(
                        player,
                        m.api().messages().get("jobs.advance.confirm_title"),
                        List.of(
                                m.api()
                                        .messages()
                                        .get(
                                                "jobs.advance.confirm_body",
                                                Placeholder.unparsed("job", name),
                                                Placeholder.unparsed(
                                                        "cost", String.valueOf(cost)))),
                        m.api().messages().get("jobs.advance.yes"),
                        m.api().messages().get("jobs.advance.no"),
                        ok -> {
                            if (!ok) {
                                result.complete(AdvanceResult.CANCELLED);
                                return;
                            }
                            commitAdvance(player, target, line, cost, result);
                        });
    }

    private void commitAdvance(
            Player player,
            JobTier target,
            JobLine line,
            long cost,
            CompletableFuture<AdvanceResult> result) {
        HarborPlayer hp = m.api().players().get(player);
        UUID uuid = hp.uuid();
        SeasonId season = hp.season();
        if (!m.busy().add(uuid)) {
            result.complete(AdvanceResult.BUSY);
            return;
        }
        String ref = "adv:" + season.value() + ":" + uuid + ":" + target.level();
        // Update the cache first: if changed after commit, a quit or auto-save (saveBase) in
        // between would overwrite the committed job_tier with the old tier in memory. Reverted on
        // failure
        JobLine prevLine = hp.jobLine().orElse(null);
        JobTier prevTier = hp.jobTier();
        hp.setJob(line, target, hp.level(), hp.exp());
        var _ =
                m.api()
                        .currency()
                        .atomic(
                                List.of(
                                        new LedgerEntry(
                                                uuid,
                                                Currency.GOLD,
                                                -cost,
                                                LedgerReason.JOB_ADVANCE,
                                                ref)),
                                c -> JobsRepository.updateJob(c, uuid, season, line, target))
                        .thenAccept(
                                r -> {
                                    m.busy().remove(uuid);
                                    switch (r) {
                                        case Result.Ok<List<Long>, LedgerError> _ -> {
                                            afterAdvance(hp, target, line);
                                            result.complete(AdvanceResult.OK);
                                        }
                                        case Result.Err<List<Long>, LedgerError>(
                                                        LedgerError error) -> {
                                            if (error == LedgerError.DUPLICATE) {
                                                // A payment and advancement with the same reference
                                                // ID was already committed (the tier had been
                                                // overwritten by a save race). Do not charge again;
                                                // only sync the tier
                                                afterAdvance(hp, target, line);
                                                result.complete(AdvanceResult.OK);
                                                return;
                                            }
                                            hp.setJob(prevLine, prevTier, hp.level(), hp.exp());
                                            result.complete(
                                                    error == LedgerError.INSUFFICIENT_FUNDS
                                                            ? AdvanceResult.NOT_ENOUGH_GOLD
                                                            : AdvanceResult.FAILED);
                                        }
                                    }
                                });
    }

    /** Main thread after commit: cache → automatic passives → apply overflow → event → cutscene. */
    void afterAdvance(HarborPlayer hp, JobTier target, JobLine line) {
        hp.setJob(line, target, hp.level(), hp.exp());
        m.rebuildModifiers(hp);
        JobsData data = m.data(hp);
        if (data.overflowExp() > 0) {
            hp.set(JobsManager.COMPONENT, data.withOverflow(0));
            m.addExp(hp, data.overflowExp());
        }
        Bukkit.getPluginManager().callEvent(new JobAdvancedEvent(hp.uuid(), target, line));
        Player player = Bukkit.getPlayer(hp.uuid());
        if (player != null) {
            String name = m.lineDef(line).map(d -> d.name(target.level())).orElse(line.key());
            var cutscene = m.advanceCutscene();
            if (cutscene != null && cutscene.test(player)) {
                m.api()
                        .messages()
                        .send(player, "jobs.advance.done", Placeholder.unparsed("job", name));
                return;
            }
            HarborSounds.play(player, "harbor:ui.job_change"); // '15. Sound' 4.5 job advancement
            Sequence.of(m.plugin(), player)
                    .run(
                            p ->
                                    m.api()
                                            .ui()
                                            .title(
                                                    p,
                                                    m.api()
                                                            .messages()
                                                            .get("jobs.advance.cutscene_title"),
                                                    Component.text(name),
                                                    Duration.ofMillis(500),
                                                    Duration.ofSeconds(3),
                                                    Duration.ofSeconds(1)))
                    .waitTicks(60)
                    .run(
                            p ->
                                    m.api()
                                            .messages()
                                            .send(
                                                    p,
                                                    "jobs.advance.done",
                                                    Placeholder.unparsed("job", name)))
                    .start();
        }
    }
}
