package wiki.creeper.harbor.boss.encounter;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.boss.api.BossRole;
import wiki.creeper.harbor.boss.content.BossDef;
import wiki.creeper.harbor.common.rig.Vec3;
import wiki.creeper.harbor.core.api.HarborSounds;

/**
 * Deck stations and support roles: helm (captain), damaged spots and repair (mechanic), supply
 * (quartermaster). Main thread only.
 */
final class DeckStations {

    private static final String REPAIR_SOUND = "harbor:ship.repair";

    /** One damaged spot. */
    static final class RepairPoint {
        final Location at;
        final Interaction entity;
        boolean damaged;

        RepairPoint(Location at, Interaction entity) {
            this.at = at;
            this.entity = entity;
        }
    }

    private final Encounter e;
    private final List<RepairPoint> repairPoints = new ArrayList<>();
    private @Nullable Interaction wheel;
    private @Nullable UUID helm;

    DeckStations(Encounter e) {
        this.e = e;
    }

    @Nullable Interaction wheel() {
        return wheel;
    }

    /** Player currently at the helm. */
    @Nullable UUID helm() {
        return helm;
    }

    // ------------------------------------------------------------------ Interaction

    void spawnInteractions() {
        if (e.def.isWorld()) {
            return;
        }
        BossDef.Deck deck = e.def.arena().deck();
        Vec3 wheelPos = deck.wheel();
        if (wheelPos != null) {
            wheel = spawnInteraction(e.deck(wheelPos), 1.0f, 1.2f);
            e.mgr.bindClick(wheel, e, Encounter.ClickKind.WHEEL, 0);
        }
        for (int i = 0; i < deck.repair().size(); i++) {
            Location at = e.deck(deck.repair().get(i));
            Interaction en = spawnInteraction(at, 0.9f, 0.9f);
            repairPoints.add(new RepairPoint(at, en));
            e.mgr.bindClick(en, e, Encounter.ClickKind.REPAIR, i);
        }
    }

    private Interaction spawnInteraction(Location at, float width, float height) {
        Interaction en =
                e.world.spawn(
                        at,
                        Interaction.class,
                        x -> {
                            x.setPersistent(false);
                            x.setVisibleByDefault(false);
                            x.setInteractionWidth(width);
                            x.setInteractionHeight(height);
                            x.setResponsive(true);
                        });
        for (Player p : e.onlinePlayers()) {
            p.showEntity(e.mgr.plugin(), en);
        }
        return en;
    }

    void show(Player p) {
        Interaction w = wheel;
        if (w != null && w.isValid()) {
            p.showEntity(e.mgr.plugin(), w);
        }
        for (RepairPoint rp : repairPoints) {
            if (rp.entity.isValid()) {
                p.showEntity(e.mgr.plugin(), rp.entity);
            }
        }
    }

    /** When the ship is hit, picks one intact repair spot. */
    void damageRepairPoint() {
        List<RepairPoint> intact = new ArrayList<>();
        for (RepairPoint rp : repairPoints) {
            if (!rp.damaged) {
                intact.add(rp);
            }
        }
        if (!intact.isEmpty()) {
            intact.get(e.random.nextInt(intact.size())).damaged = true;
        }
    }

    /** Smoke at damaged spots. */
    void smoke() {
        for (RepairPoint rp : repairPoints) {
            if (rp.damaged) {
                for (Player p : e.viewers()) {
                    p.spawnParticle(
                            Particle.CAMPFIRE_COSY_SMOKE,
                            rp.at.getX(),
                            rp.at.getY() + 0.5,
                            rp.at.getZ(),
                            1,
                            0.1,
                            0.1,
                            0.1,
                            0.01);
                }
            }
        }
    }

    /** Cleanup: removes the helm and damaged spot Interactions. */
    void remove() {
        Interaction w = wheel;
        if (w != null) {
            e.mgr.unbindClick(w);
            w.remove();
        }
        for (RepairPoint rp : repairPoints) {
            e.mgr.unbindClick(rp.entity);
            rp.entity.remove();
        }
        repairPoints.clear();
    }

    // ------------------------------------------------------------------ Captain, mechanic,
    // quartermaster

    /** Right-click on the helm or a damaged spot. */
    void click(Player p, Encounter.ClickKind kind, int index) {
        Member m = e.members.get(p.getUniqueId());
        if (m == null || !m.present()) {
            return;
        }
        switch (kind) {
            case WHEEL -> {
                boolean captainPresent =
                        e.members.values().stream()
                                .anyMatch(o -> o.present() && o.role == BossRole.CAPTAIN);
                if (m.role != BossRole.CAPTAIN && captainPresent) {
                    e.send(m.uuid, "boss.wheel.captain_only");
                    return;
                }
                helm = m.uuid;
                e.send(m.uuid, "boss.wheel.taken");
            }
            case REPAIR -> repair(p, m, index);
        }
    }

    /**
     * Mechanic repair: restores durability with one repair kit held in hand. index −1 on a deck
     * without spots.
     */
    void repair(Player p, Member m, int index) {
        var fight = e.fight;
        if (!fight.state().fighting()) {
            return;
        }
        if (!e.mgr.items().isRepairKit(p.getInventory().getItemInMainHand())) {
            e.send(m.uuid, "boss.repair.need_kit");
            return;
        }
        if (fight.hull() >= fight.hullMax()) {
            e.send(m.uuid, "boss.repair.full");
            return;
        }
        var hand = p.getInventory().getItemInMainHand();
        hand.setAmount(hand.getAmount() - 1);
        p.getInventory().setItemInMainHand(hand.getAmount() <= 0 ? null : hand);
        int restored = fight.repair(e.def.hull().repairPerAction());
        HarborSounds.playAt(BossItems.loc(p), REPAIR_SOUND);
        m.repair += restored;
        m.activity(e.mgr.now());
        if (index >= 0 && index < repairPoints.size()) {
            repairPoints.get(index).damaged = false;
        }
        e.hud.broadcastBar(
                "boss.repair.done",
                Placeholder.unparsed("player", m.name),
                Placeholder.unparsed("hull", String.valueOf(fight.hull())),
                Placeholder.unparsed("max", String.valueOf(fight.hullMax())));
    }

    /** Deck without repair spots: right-click with a repair kit in hand. */
    boolean freeRepair(Player p) {
        Member m = e.members.get(p.getUniqueId());
        if (m == null || !repairPoints.isEmpty()) {
            return false;
        }
        repair(p, m, -1);
        return true;
    }

    /** Quartermaster ate buff food: adds nearby party members × supply_seconds to repair. */
    void supply(Player p) {
        Member m = e.members.get(p.getUniqueId());
        if (m == null || m.role != BossRole.QUARTERMASTER || !e.fight.state().fighting()) {
            return;
        }
        Location at = BossItems.loc(p);
        int near = 0;
        for (Member o : e.members.values()) {
            Player op = Bukkit.getPlayer(o.uuid);
            if (op != null && o.present() && BossItems.loc(op).distanceSquared(at) <= 64) {
                near++;
            }
        }
        m.repair += (double) near * e.bb.supplySeconds();
        m.activity(e.mgr.now());
        e.broadcast(
                "boss.supply.done",
                Placeholder.unparsed("player", m.name),
                Placeholder.unparsed("count", String.valueOf(near)));
    }

    /** Drop bonus when there is a quartermaster (proposed). */
    boolean hasQuartermaster() {
        return e.members.values().stream()
                .anyMatch(m -> !m.dropped && m.role == BossRole.QUARTERMASTER);
    }
}
