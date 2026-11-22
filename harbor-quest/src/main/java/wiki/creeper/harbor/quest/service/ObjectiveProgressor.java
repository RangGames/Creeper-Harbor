package wiki.creeper.harbor.quest.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiPredicate;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.collection.api.CodexScope;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.common.id.QuestId;
import wiki.creeper.harbor.common.id.RegionId;
import wiki.creeper.harbor.core.api.HarborPlayer;
import wiki.creeper.harbor.quest.api.QuestState;
import wiki.creeper.harbor.quest.api.event.QuestProgressEvent;
import wiki.creeper.harbor.quest.api.event.QuestReadyEvent;
import wiki.creeper.harbor.quest.api.event.QuestStateChangedEvent;
import wiki.creeper.harbor.quest.content.CutsceneAt;
import wiki.creeper.harbor.quest.content.Objective;
import wiki.creeper.harbor.quest.content.QuestDef;
import wiki.creeper.harbor.quest.content.Target;
import wiki.creeper.harbor.quest.data.QuestEntry;
import wiki.creeper.harbor.quest.logic.Progress;
import wiki.creeper.harbor.quest.logic.QuestMachine;

/**
 * Objective progress (docs 20 6.4 applying progress): looks only at quests of that type in the
 * inverted index, updates memory → QuestProgressEvent → action bar → write-behind queue. Gauge
 * objectives (level, reputation, affinity, codex, flags) are set to the current value, and the
 * quest moves to READY when all objectives are filled. Main thread only.
 */
final class ObjectiveProgressor {

    private final QuestEngine engine;

    ObjectiveProgressor(QuestEngine engine) {
        this.engine = engine;
    }

    /**
     * @param gauge if true, set to the reached value (value)
     */
    void progressWith(
            Player player,
            String type,
            BiPredicate<QuestDef, Objective> match,
            int amount,
            boolean gauge) {
        Optional<HarborPlayer> found = engine.api.players().get(player.getUniqueId());
        if (found.isEmpty()) {
            return;
        }
        HarborPlayer hp = found.get();
        List<QuestId> candidates = engine.index(hp).get(type);
        if (candidates == null || candidates.isEmpty()) {
            return;
        }
        for (QuestId id : List.copyOf(candidates)) {
            progressQuest(player, hp, id, type, match, amount, gauge);
        }
    }

    /** Advances one quest. true if it progressed. */
    boolean progressQuest(
            Player player,
            HarborPlayer hp,
            QuestId id,
            String type,
            BiPredicate<QuestDef, Objective> match,
            int amount,
            boolean gauge) {
        QuestEntry entry = hp.get(QuestEngine.COMPONENT).entries().get(id);
        Optional<QuestDef> found = engine.def(id);
        if (entry == null || found.isEmpty() || entry.state() != QuestState.ACTIVE) {
            return false;
        }
        QuestDef def = found.get();
        Progress progress = entry.progress();
        boolean changed = false;
        List<String> completedKeys = new ArrayList<>();
        for (Objective o : QuestMachine.current(def, progress)) {
            if (!o.type().equals(type) || !match.test(def, o)) {
                continue;
            }
            QuestMachine.Step step =
                    gauge
                            ? QuestMachine.set(def, progress, o.key(), amount)
                            : QuestMachine.add(def, progress, o.key(), amount);
            if (!step.changed()) {
                continue;
            }
            progress = step.progress();
            changed = true;
            completedKeys.addAll(step.completedKeys());
            int value = progress.count(o.key());
            Bukkit.getPluginManager()
                    .callEvent(new QuestProgressEvent(player, id, o.key(), value, o.count()));
            engine.tracker().progressed(player, def, o, value);
        }
        if (!changed) {
            return false;
        }
        boolean ready = QuestMachine.ready(def, progress);
        QuestState next = QuestMachine.afterProgress(entry.state(), ready);
        QuestEntry updated = new QuestEntry(id, entry.season(), next, progress);
        engine.put(hp, hp.get(QuestEngine.COMPONENT).with(updated));
        engine.saveLater(hp.uuid(), updated);
        for (String key : completedKeys) {
            HarborId cutscene = def.cutscene();
            if (cutscene != null
                    && def.cutsceneAt().kind() == CutsceneAt.Kind.OBJECTIVE
                    && key.equals(def.cutsceneAt().objectiveKey())) {
                playCutscene(player, cutscene);
            }
        }
        if (next == QuestState.READY) {
            onReady(player, def);
        } else {
            // the next objective may already be filled (gauge, flag); a cutscene objective plays
            // right away
            refreshGauges(player, id);
            startCutsceneObjective(player, def);
        }
        return true;
    }

    /**
     * Quests with no tracked objectives or already filled ones (delivery-only requests etc.) go
     * straight to READY.
     */
    void checkReady(Player player, QuestDef def) {
        HarborPlayer hp = engine.api.players().get(player);
        QuestEntry e = hp.get(QuestEngine.COMPONENT).entries().get(def.id());
        if (e == null || e.state() != QuestState.ACTIVE || !QuestMachine.ready(def, e.progress())) {
            return;
        }
        QuestEntry updated = e.withState(QuestState.READY);
        engine.put(hp, hp.get(QuestEngine.COMPONENT).with(updated));
        engine.saveLater(hp.uuid(), updated);
        onReady(player, def);
    }

    void onReady(Player player, QuestDef def) {
        Bukkit.getPluginManager()
                .callEvent(
                        new QuestStateChangedEvent(
                                player.getUniqueId(),
                                def.id(),
                                QuestState.ACTIVE,
                                QuestState.READY));
        Bukkit.getPluginManager().callEvent(new QuestReadyEvent(player, def.id()));
        String npc =
                def.turnInNpc()
                        .map(n -> QuestEngine.plain(engine.deps.npcs().displayName(player, n)))
                        .orElse("");
        engine.notifier().ready(player, def, npc);
        engine.refreshMarkers(player.getUniqueId());
        if (def.turnInNpc().isEmpty()
                && def.rewards().jobAdvance() == 0
                && !QuestEngine.hasDeliver(def)) {
            // quests without a turn-in NPC (board requests etc.) complete immediately
            var _ = engine.payer().turnIn(player, def.id());
        }
    }

    /**
     * Sets gauge and flag objectives to the current value (on accept, join, level up, codex entry,
     * flag change).
     */
    void refreshGauges(Player player, @Nullable QuestId only) {
        Optional<HarborPlayer> found = engine.api.players().get(player.getUniqueId());
        if (found.isEmpty()) {
            return;
        }
        HarborPlayer hp = found.get();
        UUID uuid = hp.uuid();
        for (int guard = 0; guard < 8; guard++) {
            boolean any = false;
            for (Map.Entry<QuestDef, QuestEntry> e : engine.open(hp)) {
                QuestDef def = e.getKey();
                if ((only != null && !only.equals(def.id()))
                        || e.getValue().state() != QuestState.ACTIVE) {
                    continue;
                }
                for (Objective o : QuestMachine.current(def, e.getValue().progress())) {
                    int value = gaugeValue(hp, uuid, def, o);
                    if (value < 0) {
                        continue;
                    }
                    String key = o.key();
                    any |=
                            progressQuest(
                                    player,
                                    hp,
                                    def.id(),
                                    o.type(),
                                    (d, x) -> x.key().equals(key),
                                    value,
                                    true);
                    break;
                }
            }
            if (!any) {
                return;
            }
        }
    }

    /** Current value (-1 if not a gauge objective). */
    private int gaugeValue(HarborPlayer hp, UUID uuid, QuestDef def, Objective o) {
        return switch (o.type()) {
            case "job_level" -> hp.level();
            case "job_tier" -> hp.jobTier().level();
            case "reputation" -> engine.deps.reputation().tier(uuid);
            case "affinity" ->
                    QuestEngine.npcOf(def, o)
                            .map(n -> engine.deps.affinity().level(uuid, n))
                            .orElse(0);
            case "codex" -> {
                HarborId region = o.target().kind() == Target.Kind.REGION ? o.target().id() : null;
                double percent =
                        engine.deps
                                .codex()
                                .percent(
                                        uuid,
                                        o.scope().equals("total")
                                                ? CodexScope.TOTAL
                                                : CodexScope.SEASON,
                                        region == null ? null : new RegionId(region));
                yield (int) Math.floor(percent);
            }
            case "flag" -> {
                String flag = o.target().raw();
                boolean on =
                        o.server()
                                ? engine.api.flags().hasServer(flag)
                                : engine.api.flags().hasPlayer(uuid, flag);
                yield on ? o.count() : 0;
            }
            default -> -1;
        };
    }

    /**
     * If the current objective is a cutscene, plays it (a cutscene that blocks progress; watching
     * to the end advances via CutsceneEndEvent).
     */
    void startCutsceneObjective(Player player, QuestDef def) {
        QuestEntry e = engine.data(player.getUniqueId()).entries().get(def.id());
        if (e == null || e.state() != QuestState.ACTIVE) {
            return;
        }
        for (Objective o : QuestMachine.current(def, e.progress())) {
            HarborId id = o.target().id();
            if (o.type().equals("cutscene")
                    && id != null
                    && !engine.deps.cutscenes().isPlaying(player)) {
                engine.tasks.later(
                        20,
                        () -> {
                            if (player.isOnline()) {
                                playCutscene(player, id);
                            }
                        });
                return;
            }
        }
    }

    void playCutscene(Player player, HarborId cutscene) {
        if (!engine.deps.cutscenes().exists(cutscene)) {
            engine.log.warn("컷씬이 없습니다: {}", cutscene);
            return;
        }
        var _ = engine.deps.cutscenes().play(player, cutscene);
    }
}
