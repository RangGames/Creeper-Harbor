package wiki.creeper.harbor.quest.service;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import wiki.creeper.harbor.collection.api.event.AquariumDonatedEvent;
import wiki.creeper.harbor.collection.api.event.CollectionRegisteredEvent;
import wiki.creeper.harbor.common.content.type.FishDef;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.core.api.HarborItem;
import wiki.creeper.harbor.core.api.HarborPlayer;
import wiki.creeper.harbor.core.api.event.ContentReloadedEvent;
import wiki.creeper.harbor.core.api.event.PlayerFlagChangedEvent;
import wiki.creeper.harbor.core.api.event.ServerFlagChangedEvent;
import wiki.creeper.harbor.fishing.api.CatchSource;
import wiki.creeper.harbor.fishing.api.event.FishCaughtEvent;
import wiki.creeper.harbor.fishing.api.event.TrapHarvestEvent;
import wiki.creeper.harbor.jobs.api.event.JobAdvancedEvent;
import wiki.creeper.harbor.jobs.api.event.JobLevelUpEvent;
import wiki.creeper.harbor.npc.api.event.AffinityLevelUpEvent;
import wiki.creeper.harbor.npc.api.event.CutsceneEndEvent;
import wiki.creeper.harbor.npc.api.event.NpcInteractEvent;
import wiki.creeper.harbor.npc.api.event.ReputationTierChangedEvent;
import wiki.creeper.harbor.quest.api.QuestState;
import wiki.creeper.harbor.quest.content.QuestDef;
import wiki.creeper.harbor.quest.content.QuestType;
import wiki.creeper.harbor.quest.data.QuestData;
import wiki.creeper.harbor.quest.data.QuestEntry;
import wiki.creeper.harbor.quest.logic.CatchFacts;
import wiki.creeper.harbor.quest.logic.Matchers;
import wiki.creeper.harbor.season.api.event.DailyResetEvent;
import wiki.creeper.harbor.season.api.event.SeasonWeekChangedEvent;
import wiki.creeper.harbor.season.api.event.WeeklyResetEvent;

/**
 * Other modules' events → objective progress and requirement re-evaluation (docs 20 6.2
 * subscription table). All on the main thread.
 */
public final class QuestListener implements Listener {

    private final QuestServices services;
    private final QuestEngine engine;
    private final Map<UUID, Long> lastUse = new HashMap<>();

    public QuestListener(QuestServices services) {
        this.services = services;
        this.engine = services.engine();
    }

    // ------------------------------------------------------------------ Join

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        Optional<HarborPlayer> found = engine.api.players().get(player.getUniqueId());
        if (found.isEmpty()) {
            return;
        }
        HarborPlayer hp = found.get();
        engine.migrate(hp);
        services.expireBoards(player, Set.of(QuestType.DAILY, QuestType.WEEKLY, QuestType.COLLECT));
        services.recover(player);
        QuestData data = hp.get(QuestEngine.COMPONENT);
        if (data.tracked() == null) {
            engine.open(hp).stream()
                    .filter(e -> e.getKey().main())
                    .findFirst()
                    .or(() -> engine.open(hp).stream().findFirst())
                    .ifPresent(
                            e ->
                                    hp.set(
                                            QuestEngine.COMPONENT,
                                            hp.get(QuestEngine.COMPONENT)
                                                    .withTracked(e.getKey().id())));
        }
        engine.refreshGauges(player, null);
        // cutscene objectives missed by quitting during the cutscene are played again (docs 20 6.4
        // edge cases)
        engine.open(hp).forEach(e -> engine.startCutsceneObjective(player, e.getKey()));
        engine.open(hp).forEach(e -> engine.checkReady(player, e.getKey()));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        services.forget(event.getPlayer().getUniqueId());
        lastUse.remove(event.getPlayer().getUniqueId());
    }

    // ------------------------------------------------------------------ Objectives

    @EventHandler(priority = EventPriority.MONITOR)
    public void onCatch(FishCaughtEvent event) {
        FishDef fish = event.fish();
        var item = event.item();
        CatchFacts facts =
                CatchFacts.fish(
                        fish.id(),
                        fish.grade(),
                        event.region(),
                        item.quality().stars(),
                        item.sizeCm(),
                        event.source() == CatchSource.TRAP);
        engine.progress(event.getPlayer(), "catch", o -> Matchers.matchesFish(o, facts), 1, false);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onTrap(TrapHarvestEvent event) {
        for (HarborItem.Fish item : event.fish()) {
            engine.snapshot()
                    .get(FishDef.TYPE, item.species().id())
                    .ifPresent(
                            fish -> {
                                CatchFacts facts =
                                        CatchFacts.fish(
                                                fish.id(),
                                                fish.grade(),
                                                event.region(),
                                                item.quality().stars(),
                                                item.sizeCm(),
                                                true);
                                engine.progress(
                                        event.getPlayer(),
                                        "catch",
                                        o -> Matchers.matchesFish(o, facts),
                                        1,
                                        false);
                            });
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDonate(AquariumDonatedEvent event) {
        Player player = Bukkit.getPlayer(event.player());
        if (player == null) {
            return;
        }
        var region =
                engine.snapshot()
                        .get(FishDef.TYPE, event.fish().id())
                        .map(FishDef::region)
                        .orElse(null);
        CatchFacts facts =
                CatchFacts.fish(
                        event.fish(), event.grade(), region, event.quality().stars(), 0, false);
        engine.progress(player, "donate", o -> Matchers.matchesFish(o, facts), 1, false);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onCollection(CollectionRegisteredEvent event) {
        Player player = Bukkit.getPlayer(event.player());
        if (player != null) {
            engine.invalidate(event.player());
            engine.refreshGauges(player, null);
        }
    }

    /**
     * Handles talk objectives (no node) and completion turn-ins first (docs 20 5.2
     * NpcInteractEvent).
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onNpc(NpcInteractEvent event) {
        Player player = event.player();
        var npc = event.npc();
        engine.progressWith(
                player,
                "talk",
                (d, o) ->
                        o.node() == null && QuestEngine.npcOf(d, o).map(npc::equals).orElse(false),
                1,
                false);
        HarborPlayer hp = engine.api.players().get(player);
        for (QuestEntry e : hp.get(QuestEngine.COMPONENT).entries().values()) {
            if (e.state() != QuestState.READY) {
                continue;
            }
            Optional<QuestDef> def = engine.def(e.quest());
            if (def.isPresent()
                    && def.get().turnInNpc().map(npc::equals).orElse(false)
                    && !engine.payer().completing(hp.uuid(), e.quest())) {
                event.setCancelled(true);
                var _ = engine.turnIn(player, e.quest());
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onCutscene(CutsceneEndEvent event) {
        if (!event.result().completed()) {
            return;
        }
        HarborId id = event.cutscene();
        engine.progress(event.player(), "cutscene", o -> Matchers.matchesId(o, id), 1, false);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onUse(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND
                || (event.getAction() != Action.RIGHT_CLICK_AIR
                        && event.getAction() != Action.RIGHT_CLICK_BLOCK)) {
            return;
        }
        useItem(event.getPlayer(), event.getItem());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent event) {
        useItem(event.getPlayer(), event.getItem());
    }

    private void useItem(Player player, @org.jspecify.annotations.Nullable ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return;
        }
        var visits = engine.index(engine.api.players().get(player)).get("use_item");
        if (visits == null || visits.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastUse.getOrDefault(player.getUniqueId(), 0L) < 500) {
            return;
        }
        lastUse.put(player.getUniqueId(), now);
        engine.api
                .items()
                .read(stack)
                .ifPresent(
                        item -> {
                            HarborId id =
                                    switch (item) {
                                        case HarborItem.Plain plain -> plain.id().id();
                                        case HarborItem.Fish fish -> fish.species().id();
                                    };
                            engine.progress(
                                    player, "use_item", o -> Matchers.matchesId(o, id), 1, false);
                        });
    }

    // ------------------------------------------------------------------ Requirement re-evaluation

    @EventHandler(priority = EventPriority.MONITOR)
    public void onLevel(JobLevelUpEvent event) {
        refresh(event.player());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onAdvance(JobAdvancedEvent event) {
        Player player = Bukkit.getPlayer(event.player());
        if (player != null) {
            refresh(event.player());
            services.onJobAdvanced(player, event.tier().level());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onAffinity(AffinityLevelUpEvent event) {
        refresh(event.player());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onReputation(ReputationTierChangedEvent event) {
        refresh(event.player());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerFlag(PlayerFlagChangedEvent event) {
        refresh(event.player());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onServerFlag(ServerFlagChangedEvent event) {
        engine.invalidateAll();
        for (Player p : Bukkit.getOnlinePlayers()) {
            engine.refreshGauges(p, null);
            engine.refreshMarkers(p.getUniqueId());
        }
    }

    private void refresh(UUID uuid) {
        engine.invalidate(uuid);
        Player player = Bukkit.getPlayer(uuid);
        if (player != null) {
            engine.refreshGauges(player, null);
            engine.refreshMarkers(uuid);
        }
    }

    // ------------------------------------------------------------------ Season clock

    @EventHandler
    public void onDaily(DailyResetEvent event) {
        engine.invalidateAll();
        Bukkit.getOnlinePlayers().forEach(p -> services.expireBoards(p, Set.of(QuestType.DAILY)));
    }

    @EventHandler
    public void onWeekly(WeeklyResetEvent event) {
        engine.invalidateAll();
        Bukkit.getOnlinePlayers()
                .forEach(
                        p -> services.expireBoards(p, Set.of(QuestType.WEEKLY, QuestType.COLLECT)));
    }

    /**
     * Week change: quests in progress stay as they are; only the LOCKED cache is recomputed and
     * newly opened quests are announced.
     */
    @EventHandler
    public void onWeek(SeasonWeekChangedEvent event) {
        engine.invalidateAll();
        for (Player p : Bukkit.getOnlinePlayers()) {
            HarborPlayer hp = engine.api.players().get(p);
            for (QuestDef def : engine.defs()) {
                if (def.requires().week() == event.week()
                        && def.type() != QuestType.HIDDEN
                        && engine.avail(hp, def).state() == QuestState.AVAILABLE) {
                    engine.api
                            .messages()
                            .send(p, "quest.week_opened", Placeholder.unparsed("name", def.name()));
                }
            }
            engine.refreshMarkers(p.getUniqueId());
        }
    }

    @EventHandler
    public void onReload(ContentReloadedEvent event) {
        engine.invalidateAll();
        int changed = 0;
        int orphaned = 0;
        for (HarborPlayer hp : engine.api.players().online()) {
            QuestEngine.MigrationReport r = engine.migrate(hp);
            changed += r.changed();
            orphaned += r.orphaned();
            engine.refreshMarkers(hp.uuid());
        }
        if (changed > 0 || orphaned > 0) {
            engine.log.info("퀘스트 리로드: 진행 이전 {}건, 정의가 사라진 진행 {}건 (행은 남김)", changed, orphaned);
        }
    }
}
