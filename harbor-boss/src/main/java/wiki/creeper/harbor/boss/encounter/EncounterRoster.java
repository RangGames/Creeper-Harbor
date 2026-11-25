package wiki.creeper.harbor.boss.encounter;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import wiki.creeper.harbor.boss.api.BossRole;
import wiki.creeper.harbor.boss.api.EncounterState;
import wiki.creeper.harbor.boss.logic.Qte;
import wiki.creeper.harbor.core.api.Dialogs;
import wiki.creeper.harbor.core.api.RigInstance;
import wiki.creeper.harbor.core.api.WorldBorderService;

/**
 * Participant presence and roles: role selection and anchor filling during preparation, entry
 * display, absence, anchor swaps and slot release, rejoin and falling into water. Main thread only.
 */
final class EncounterRoster {

    private final Encounter e;

    EncounterRoster(Encounter e) {
        this.e = e;
    }

    // ------------------------------------------------------------------ Roles

    void askRole(Player p) {
        List<Dialogs.Choice> choices = new ArrayList<>();
        for (BossRole r : BossRole.values()) {
            choices.add(
                    new Dialogs.Choice(
                            e.msg("boss.role." + r.key()), e.msg("boss.role.desc." + r.key())));
        }
        UUID uuid = p.getUniqueId();
        e.mgr.api()
                .dialogs()
                .choose(
                        p,
                        e.msg("boss.prepare.title", Placeholder.component("boss", e.bossName())),
                        List.of(e.msg("boss.prepare.body")),
                        choices,
                        3,
                        idx ->
                                chooseRole(
                                        uuid,
                                        List.of(BossRole.values()).get(Math.clamp(idx, 0, 4))));
    }

    /** Role selection during preparation (Dialog or /boss role). */
    boolean chooseRole(UUID player, BossRole role) {
        Member m = e.members.get(player);
        if (m == null || e.fight.state() != EncounterState.PREPARING || e.launching) {
            return false;
        }
        m.role = role;
        m.chose = true;
        e.mgr.rememberRole(player, role);
        e.broadcast(
                "boss.prepare.chose",
                Placeholder.unparsed("player", m.name),
                Placeholder.component("role", e.msg("boss.role." + role.key())));
        return true;
    }

    boolean allChose() {
        for (Member m : e.members.values()) {
            if (m.present() && !m.chose) {
                return false;
            }
        }
        return true;
    }

    /**
     * Players who did not pick keep their recommended role; if anchors are short, players who did
     * not pick are switched to anchor first.
     */
    void fillAnchors() {
        int need = e.def.isWorld() ? 0 : e.bb.anchorsMin(e.def.type());
        int have = 0;
        for (Member m : e.members.values()) {
            if (m.role == BossRole.ANCHOR && m.present()) {
                have++;
            }
        }
        for (int pass = 0; pass < 2 && have < need; pass++) {
            for (Member m : e.members.values()) {
                if (have >= need) {
                    break;
                }
                boolean candidate = pass == 0 ? !m.chose : true;
                if (candidate && m.role != BossRole.ANCHOR && m.present()) {
                    m.role = BossRole.ANCHOR;
                    have++;
                    e.send(m.uuid, "boss.prepare.assigned_anchor");
                }
            }
        }
    }

    // ------------------------------------------------------------------ Entry and exit display

    /** Prepares a player entering the arena (boundary, boss bar, display). */
    void enter(Player p, Member m, boolean teleport) {
        m.rememberIp(p);
        if (teleport && !e.def.isWorld()) {
            Location spawn = e.deckSpawn();
            spawn.add(e.random.nextDouble(-1, 1), 0, e.random.nextDouble(-1, 1));
            var _ = p.teleportAsync(spawn);
        }
        if (!e.def.isWorld()) {
            e.mgr.api()
                    .worldBorders()
                    .request(
                            p,
                            WorldBorderService.Priority.BOSS_ARENA,
                            new WorldBorderService.Border(e.center, e.radius * 2, 2));
        }
        BossBar bar = m.bar;
        if (bar == null) {
            bar = BossBar.bossBar(e.bossName(), 1f, BossBar.Color.RED, BossBar.Overlay.NOTCHED_10);
            m.bar = bar;
        }
        p.showBossBar(bar);
        RigInstance r = e.bossRig.instance();
        if (r != null && !r.isPublic()) {
            r.addViewer(p);
        }
        e.stations.show(p);
        e.patterns.show(p);
    }

    void leaveVisuals(Player p, Member m) {
        e.mgr.api().worldBorders().clear(p, WorldBorderService.Priority.BOSS_ARENA);
        BossBar bar = m.bar;
        if (bar != null) {
            p.hideBossBar(bar);
        }
        RigInstance r = e.bossRig.instance();
        if (r != null && !r.isPublic()) {
            r.removeViewer(p);
        }
        p.clearTitle();
    }

    // ------------------------------------------------------------------ Absence

    /** Every 10 ticks: inside the arena and online, slot hold time, anchor swaps. */
    void updatePresence(long now) {
        boolean slow = false;
        for (Member m : e.members.values()) {
            if (m.dropped) {
                continue;
            }
            Player p = Bukkit.getPlayer(m.uuid);
            boolean here = p != null && p.isOnline() && e.inArena(BossItems.loc(p));
            if (p != null && p.getPing() >= e.bb.latencyPingMs()) {
                slow = true;
            }
            if (here) {
                if (m.absentSince >= 0) {
                    m.absentSince = -1;
                }
                if (p != null && !e.def.isWorld() && e.fight.state().fighting()) {
                    if (!m.fallen(now) && DeckGround.fallen(p, e.deckSpawn().getY())) {
                        fall(m, p);
                    }
                }
                continue;
            }
            if (m.absentSince < 0) {
                m.absentSince = now;
                onAbsent(m, now);
            } else if (now - m.absentSince >= e.bb.slotHoldTicks()) {
                drop(m);
            }
        }
        e.latencySlow = slow;
    }

    /** Player left their spot: if an anchor, hand over to the next candidate (5s grace). */
    private void onAbsent(Member m, long now) {
        if (m.role != BossRole.ANCHOR) {
            return;
        }
        e.anchors.close(m);
        if (e.def.isWorld()) {
            return;
        }
        boolean otherAnchor =
                e.members.values().stream()
                        .anyMatch(o -> o != m && o.present() && o.role == BossRole.ANCHOR);
        if (otherAnchor) {
            return;
        }
        for (Member c : e.members.values()) {
            if (c != m && c.present() && c.role != BossRole.ANCHOR) {
                c.role = BossRole.ANCHOR;
                e.anchorGraceUntil = now + e.bb.anchorGraceTicks();
                c.reopenAt = e.anchorGraceUntil;
                e.broadcast("boss.anchor.handover", Placeholder.unparsed("player", c.name));
                return;
            }
        }
        e.broadcast("boss.anchor.none");
    }

    /** Releases the slot (contribution is kept). */
    void drop(Member m) {
        if (m.dropped) {
            return;
        }
        m.dropped = true;
        e.anchors.close(m);
        Player p = Bukkit.getPlayer(m.uuid);
        if (p != null) {
            leaveVisuals(p, m);
        }
        e.mgr.unbind(m.uuid, e);
    }

    // --------------------------------------------------------- Leaving, rejoin, falling into water

    /** Rejoin (within the slot hold time). field encounters send the player back to the deck. */
    void rejoin(Player p) {
        Member m = e.members.get(p.getUniqueId());
        if (m == null || m.dropped) {
            return;
        }
        m.absentSince = -1;
        m.input = Qte.InputState.NONE;
        enter(p, m, true);
        e.send(m.uuid, "boss.rejoin");
        if (m.role == BossRole.ANCHOR) {
            m.reopenAt = e.mgr.now() + 20;
        }
    }

    void quit(Player p) {
        Member m = e.members.get(p.getUniqueId());
        if (m == null || m.dropped) {
            return;
        }
        if (m.absentSince < 0) {
            m.absentSince = e.mgr.now();
            onAbsent(m, e.mgr.now());
        }
    }

    /**
     * 'Falling into the water' instead of dying in the arena (items kept, back on deck after 5s).
     */
    void fall(Member m, Player p) {
        var bb = e.bb;
        long now = e.mgr.now();
        if (m.fallen(now)) {
            return;
        }
        m.fallenUntil = now + bb.fallTicks();
        p.setHealth(Math.max(1.0, p.getHealth()));
        p.setFireTicks(0);
        p.addPotionEffect(
                new PotionEffect(
                        PotionEffectType.BLINDNESS, bb.fallTicks(), 0, false, false, false));
        e.mgr.api()
                .ui()
                .title(
                        p,
                        e.msg("boss.fall.title"),
                        e.msg(
                                "boss.fall.subtitle",
                                Placeholder.unparsed(
                                        "seconds", String.valueOf(bb.fallTicks() / 20))),
                        Duration.ZERO,
                        Duration.ofMillis(bb.fallTicks() * 50L),
                        Duration.ofMillis(200));
        Qte.Track track = m.track;
        if (track != null) {
            track.setExcluded(true);
        }
    }
}
