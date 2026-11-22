package wiki.creeper.harbor.boss.encounter;

import java.util.Optional;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.entity.Trident;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.player.PlayerInputEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.boss.api.BossService.StartResult;
import wiki.creeper.harbor.boss.content.BossDef;
import wiki.creeper.harbor.boss.data.BossProfile;
import wiki.creeper.harbor.boss.data.BossRepository;
import wiki.creeper.harbor.boss.logic.Qte;
import wiki.creeper.harbor.common.content.type.ItemDef;
import wiki.creeper.harbor.common.id.ItemId;
import wiki.creeper.harbor.core.api.HarborApi;
import wiki.creeper.harbor.core.api.event.ContentReloadedEvent;

/**
 * Encounter listener: harpoon throws, summon bait and repair kits, clicks, input and supply,
 * falling into water and death protection, leave blocking, join/quit, reload. Main thread only.
 */
public final class EncounterListener implements Listener {

    private final EncounterManager mgr;
    private final HarborApi api;

    EncounterListener(EncounterManager mgr) {
        this.mgr = mgr;
        this.api = mgr.api();
    }

    private @Nullable Encounter of(UUID player) {
        return mgr.encounterOfPlayer(player).orElse(null);
    }

    // ------------------------------------------------------------------ Harpoon trajectory hit
    // detection

    @EventHandler(ignoreCancelled = true)
    public void onLaunch(ProjectileLaunchEvent event) {
        if (!(event.getEntity() instanceof Trident trident)
                || !(trident.getShooter() instanceof Player player)) {
            return;
        }
        mgr.harpoons().launched(trident, player);
    }

    // ------------------------------------------------------------------ Listeners

    /** Summon bait use (right-click) · repair kit on a deck without repair spots. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND
                || (event.getAction() != Action.RIGHT_CLICK_AIR
                        && event.getAction() != Action.RIGHT_CLICK_BLOCK)) {
            return;
        }
        Player p = event.getPlayer();
        ItemStack hand = event.getItem();
        Optional<ItemId> id = mgr.items().idOf(hand);
        if (id.isEmpty()) {
            return;
        }
        Encounter current = of(p.getUniqueId());
        if (current != null && mgr.items().isRepairKit(hand)) {
            if (current.freeRepair(p)) {
                event.setCancelled(true);
            }
            return;
        }
        for (BossDef def : mgr.definitions()) {
            BossDef.Summon summon = def.summon();
            if (summon == null || !summon.item().equals(id.get())) {
                continue;
            }
            event.setCancelled(true);
            StartResult r = mgr.trySummon(p, def);
            if (r != StartResult.STARTED) {
                api.messages().send(p, "boss.start." + r.name().toLowerCase(java.util.Locale.ROOT));
            }
            return;
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        EncounterManager.Click c = mgr.click(event.getRightClicked().getUniqueId());
        if (c == null || event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        event.setCancelled(true);
        c.encounter().click(event.getPlayer(), c.kind(), c.index());
    }

    @EventHandler
    public void onInput(PlayerInputEvent event) {
        Encounter e = of(event.getPlayer().getUniqueId());
        if (e == null) {
            return;
        }
        var in = event.getInput();
        e.input(
                event.getPlayer(),
                new Qte.InputState(
                        in.isForward(),
                        in.isBackward(),
                        in.isLeft(),
                        in.isRight(),
                        in.isJump(),
                        in.isSneak()));
    }

    @EventHandler(ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent event) {
        Encounter e = of(event.getPlayer().getUniqueId());
        if (e == null) {
            return;
        }
        mgr.items()
                .defOf(event.getItem())
                .filter(d -> d.type() == ItemDef.Type.FOOD)
                .ifPresent(d -> e.supply(event.getPlayer()));
    }

    /** Inside the arena, 'falling into the water' instead of dying. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player p)) {
            return;
        }
        Encounter e = of(p.getUniqueId());
        if (e == null || !e.fight.state().fighting()) {
            return;
        }
        if (event.getFinalDamage() >= p.getHealth()) {
            event.setCancelled(true);
            e.member(p.getUniqueId()).ifPresent(m -> e.fall(m, p));
        }
    }

    /** Items are kept even on death from other causes (during a boss fight). */
    @EventHandler(priority = EventPriority.HIGH)
    public void onDeath(PlayerDeathEvent event) {
        Encounter e = of(event.getPlayer().getUniqueId());
        if (e != null && !e.finished()) {
            event.setKeepInventory(true);
            event.getDrops().clear();
            event.setKeepLevel(true);
            event.setDroppedExp(0);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        Encounter e = of(event.getPlayer().getUniqueId());
        if (e == null || e.def.isWorld() || !e.fight.state().fighting()) {
            return;
        }
        Location to = event.getTo();
        if (e.inArena(to)) {
            return;
        }
        var cause = event.getCause();
        if (cause == PlayerTeleportEvent.TeleportCause.ENDER_PEARL
                || cause == PlayerTeleportEvent.TeleportCause.CONSUMABLE_EFFECT) {
            event.setCancelled(true);
            api.messages().send(event.getPlayer(), "boss.leave.blocked");
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        Encounter e = of(uuid);
        if (e != null) {
            e.quit(event.getPlayer());
            EncounterManager.InstanceHooks hooks = mgr.instanceHooks();
            if (hooks != null && e.def.arena().mode() == BossDef.ArenaMode.INSTANCE) {
                hooks.quit(uuid, e.id);
            }
        }
        var _ =
                mgr.tasks()
                        .later(
                                mgr.balance().slotHoldTicks() + 20L,
                                () -> {
                                    if (Bukkit.getPlayer(uuid) == null && !mgr.inEncounter(uuid)) {
                                        mgr.parties().dropOffline(uuid);
                                    }
                                });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player p = event.getPlayer();
        Encounter e = of(p.getUniqueId());
        if (e != null && !e.finished()) {
            var _ =
                    mgr.tasks()
                            .later(
                                    5,
                                    () -> {
                                        if (p.isOnline()) {
                                            e.rejoin(p);
                                        }
                                    });
        } else {
            returnFromInstance(p);
        }
        var _ =
                mgr.tasks()
                        .later(
                                40,
                                () -> {
                                    if (p.isOnline()) {
                                        mgr.rewards().deliverPending(p, false);
                                    }
                                });
    }

    /** On return from an instance, go to the location before leaving (BossProfile). */
    private void returnFromInstance(Player p) {
        Optional<BossProfile> profile =
                api.players().get(p.getUniqueId()).map(h -> h.get(EncounterManager.PROFILE));
        if (profile.isEmpty()) {
            return;
        }
        BossProfile.ReturnPoint point = profile.get().returnPoint();
        if (point == null || !point.server().equals(mgr.serverId())) {
            return;
        }
        mgr.items()
                .world(point.world())
                .ifPresent(
                        w -> {
                            var _ =
                                    p.teleportAsync(
                                            new Location(
                                                    w,
                                                    point.x(),
                                                    point.y(),
                                                    point.z(),
                                                    point.yaw(),
                                                    point.pitch()));
                        });
        BossProfile cleared = profile.get().withReturn(null);
        api.players().get(p.getUniqueId()).ifPresent(h -> h.set(EncounterManager.PROFILE, cleared));
        UUID uuid = p.getUniqueId();
        var season = api.players().get(p).season();
        var _ = api.database().asyncVoid(c -> BossRepository.saveProfile(c, uuid, season, cleared));
    }

    /**
     * New content: nothing to do, since running encounters keep the definition from their start
     * (new definitions apply from the next encounter).
     */
    @EventHandler
    public void onReload(ContentReloadedEvent event) {
        int running = mgr.active().size();
        if (event.reloadedTypes().contains(BossDef.TYPE.name()) && running > 0) {
            mgr.log().info("보스 정의 리로드: 진행 중 조우 {}개는 기존 정의로 끝까지 진행합니다", running);
        }
    }
}
