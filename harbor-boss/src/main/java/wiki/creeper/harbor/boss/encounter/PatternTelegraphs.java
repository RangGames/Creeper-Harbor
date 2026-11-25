package wiki.creeper.harbor.boss.encounter;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;
import wiki.creeper.harbor.boss.content.BossDef;
import wiki.creeper.harbor.boss.logic.Fight;
import wiki.creeper.harbor.boss.logic.Geometry;
import wiki.creeper.harbor.boss.logic.PatternScheduler;
import wiki.creeper.harbor.boss.logic.Qte;
import wiki.creeper.harbor.core.api.HarborSounds;
import wiki.creeper.harbor.core.api.RigInstance;
import wiki.creeper.harbor.fishing.api.TensionSession;

/**
 * Boss pattern telegraphs: pick a pattern → floor markers → resolution on the ending tick
 * (knockback, debuffs, ship durability, dodges), and the captain's charge dodge input. Main thread
 * only.
 */
final class PatternTelegraphs {

    private static final String HULL_HIT = "harbor:ship.hull_hit";

    private final Encounter e;
    private final PatternScheduler scheduler = new PatternScheduler();
    private final List<Telegraph> telegraphs = new ArrayList<>();

    PatternTelegraphs(Encounter e) {
        this.e = e;
    }

    /** Delays the next pattern until after this tick. */
    void delay(long until) {
        scheduler.delay(until);
    }

    /** Shows in-progress markers to a player entering the arena. */
    void show(Player p) {
        for (Telegraph t : telegraphs) {
            t.show(e.mgr.plugin(), p);
        }
    }

    int entityCount() {
        return telegraphs.stream().mapToInt(Telegraph::entityCount).sum();
    }

    void run(long now, BossDef.PhaseKind kind) {
        BossDef.PhaseDef phase = e.def.phase(kind).orElse(null);
        if (phase == null || phase.patterns().isEmpty()) {
            return;
        }
        List<BossDef.PatternDef> candidates = new ArrayList<>();
        for (String pid : phase.patterns()) {
            e.def.pattern(pid).ifPresent(candidates::add);
        }
        int gap =
                phase.patternGapTicks() > 0
                        ? phase.patternGapTicks()
                        : e.bb.patternGapTicks(e.def.type());
        scheduler.next(candidates, now, gap, e.random).ifPresent(p -> start(p, now));
    }

    private void start(BossDef.PatternDef pattern, long now) {
        var def = e.def;
        var bb = e.bb;
        var random = e.random;
        List<Member> present = new ArrayList<>();
        for (Member m : e.members.values()) {
            if (m.present() && Bukkit.getPlayer(m.uuid) != null) {
                present.add(m);
            }
        }
        Location spawn = e.deckSpawn();
        // Where the boss is now (it swims around), not its arena point
        Location boss = e.bossRig.location();
        double ox;
        double oz;
        double floorY = spawn.getY() - 1;
        double dirX = spawn.getX() - boss.getX();
        double dirZ = spawn.getZ() - boss.getZ();
        switch (pattern.telegraph().at()) {
            case MEMBER -> {
                if (present.isEmpty()) {
                    ox = spawn.getX();
                    oz = spawn.getZ();
                } else {
                    // Prefer members who can move: anchors on a line are frozen in place and
                    // could never dodge (proposed)
                    List<Member> free = present.stream().filter(m -> m.session == null).toList();
                    List<Member> pool = free.isEmpty() ? present : free;
                    Player target = Bukkit.getPlayer(pool.get(random.nextInt(pool.size())).uuid);
                    Location tl = target == null ? spawn : BossItems.loc(target);
                    ox = tl.getX();
                    oz = tl.getZ();
                    if (def.isWorld()) {
                        floorY = tl.getY() - 0.98;
                    }
                }
            }
            case DECK -> {
                ox = spawn.getX();
                oz = spawn.getZ();
            }
            case BOSS -> {
                ox = boss.getX();
                oz = boss.getZ();
                if (def.isWorld() && !present.isEmpty()) {
                    Player target =
                            Bukkit.getPlayer(present.get(random.nextInt(present.size())).uuid);
                    if (target != null) {
                        dirX = target.getX() - ox;
                        dirZ = target.getZ() - oz;
                        floorY = target.getY() - 0.98;
                    }
                }
            }
            default -> throw new IllegalStateException();
        }
        int ticks =
                pattern.telegraph().ticks() > 0
                        ? pattern.telegraph().ticks()
                        : bb.telegraphTicks(def.type());
        if (e.latencySlow) {
            ticks += bb.latencyExtraTicks();
        }
        Geometry.Area area = Geometry.Area.of(pattern.telegraph(), ox, oz, dirX, dirZ);
        Qte.Key side = null;
        if (pattern.charge()) {
            side = random.nextBoolean() ? Qte.Key.LEFT : Qte.Key.RIGHT;
        }
        Telegraph t = new Telegraph(pattern, area, now, now + ticks, floorY, e.world, side);
        t.spawn(
                e.mgr.plugin(),
                def.isWorld() ? null : e.onlinePlayers(),
                (float) (def.isWorld() ? bb.worldRadius() / 64.0 : 1.0));
        for (Member m : present) {
            Player p = Bukkit.getPlayer(m.uuid);
            if (p != null && t.contains(BossItems.loc(p))) {
                t.insideAtStart.add(m.uuid);
            }
        }
        telegraphs.add(t);
        RigInstance r = e.bossRig.instance();
        String anim = pattern.animation();
        if (r != null && anim != null) {
            r.playOnce(anim, null);
        }
        Component name = MiniMessage.miniMessage().deserialize(pattern.name());
        UUID helm = e.stations.helm();
        for (Member m : present) {
            Player p = Bukkit.getPlayer(m.uuid);
            if (p == null) {
                continue;
            }
            if (side != null && m.uuid.equals(helm)) {
                e.mgr.api()
                        .ui()
                        .title(
                                p,
                                e.msg("boss.charge.title"),
                                e.msg("boss.charge." + side.key()),
                                Duration.ZERO,
                                Duration.ofMillis(ticks * 50L),
                                Duration.ofMillis(100));
            } else {
                e.mgr.api()
                        .ui()
                        .actionBar(
                                p,
                                e.msg("boss.pattern.warn", Placeholder.component("pattern", name)));
            }
        }
    }

    void update(long now) {
        Iterator<Telegraph> it = telegraphs.iterator();
        List<Player> viewers = e.viewers();
        while (it.hasNext()) {
            Telegraph t = it.next();
            if (now >= t.end) {
                resolve(t, now);
                t.remove();
                it.remove();
                if (e.finished()) {
                    return;
                }
            } else if ((now - t.start) % 2 == 0) {
                t.particles(now, viewers);
            }
        }
        e.bossRig.tickMove(now, !telegraphs.isEmpty());
    }

    /**
     * Resolution on the tick a telegraph ends: knockback, debuffs, ship durability, telegraph area
     * dodges, captain dodge.
     */
    private void resolve(Telegraph t, long now) {
        BossDef.Effect effect = t.pattern.effect();
        boolean anyInside = false;
        boolean evaded = t.chargeSide != null && t.evaded;
        for (Member m : e.members.values()) {
            Player p = Bukkit.getPlayer(m.uuid);
            if (p == null || !m.present() || m.fallen(now)) {
                continue;
            }
            Location loc = BossItems.loc(p);
            boolean inside = t.contains(loc);
            if (!inside) {
                if (t.insideAtStart.contains(m.uuid)) {
                    m.evasion += 1;
                    m.activity(now);
                    e.worldBoss.activity(m.uuid, now);
                }
                continue;
            }
            anyInside = true;
            if (evaded) {
                continue;
            }
            if (m.session != null) {
                // Anchor holding a line cannot move: the hit still damages the ship, but no
                // knockback (it would push them into the water) or debuff (proposed)
                continue;
            }
            if (effect.knockback() > 0) {
                Vector push = new Vector(loc.getX() - t.area.ox(), 0, loc.getZ() - t.area.oz());
                if (push.lengthSquared() < 1e-4) {
                    push = new Vector(e.random.nextDouble(-1, 1), 0, e.random.nextDouble(-1, 1));
                }
                push.normalize().multiply(effect.knockback()).setY(0.35);
                p.setVelocity(push);
            }
            applyDebuff(m, p, effect, now);
        }
        if (effect.tension() != 0 && !evaded) {
            for (Member m : e.members.values()) {
                TensionSession s = m.session;
                if (s != null && s.isActive()) {
                    s.addTension(effect.tension());
                }
            }
        }
        if (t.chargeSide != null && evaded) {
            UUID h = e.stations.helm();
            Member captain = h == null ? null : e.members.get(h);
            if (captain != null) {
                captain.evasion += 1;
                captain.activity(now);
            }
            e.broadcast("boss.charge.evaded");
            return;
        }
        Location spawn = e.deckSpawn();
        boolean shipHit =
                anyInside || (!e.def.isWorld() && t.area.contains(spawn.getX(), spawn.getZ()));
        if (shipHit && effect.hullDamage() > 0) {
            var fight = e.fight;
            List<Fight.Transition> out = new ArrayList<>();
            int lost = fight.hullDamage(effect.hullDamage(), out);
            if (lost > 0) {
                e.stations.damageRepairPoint();
                HarborSounds.playAt(
                        spawn, HULL_HIT, HarborSounds.Options.DEFAULT, e.onlinePlayers());
                e.hud.broadcastBar(
                        "boss.hull.hit",
                        Placeholder.unparsed("lost", String.valueOf(lost)),
                        Placeholder.unparsed("hull", String.valueOf(fight.hull())),
                        Placeholder.unparsed("max", String.valueOf(fight.hullMax())));
            }
            e.handle(out);
        }
    }

    private void applyDebuff(Member m, Player p, BossDef.Effect effect, long now) {
        int ticks = Math.max(20, effect.debuffTicks());
        PotionEffectType type =
                switch (effect.debuff()) {
                    case NONE -> null;
                    case BLIND -> PotionEffectType.BLINDNESS;
                    case DARKNESS -> PotionEffectType.DARKNESS;
                    case SLOW -> PotionEffectType.SLOWNESS;
                    case LEVITATE -> PotionEffectType.LEVITATION;
                    case INVERT -> PotionEffectType.NAUSEA;
                };
        if (type == null) {
            return;
        }
        p.addPotionEffect(new PotionEffect(type, ticks, 0, false, false, true));
        if (effect.debuff() == BossDef.Debuff.INVERT) {
            m.invertedUntil = now + ticks;
        }
        e.mgr.api().ui().actionBar(p, e.msg("boss.debuff." + effect.debuff().key()));
    }

    void clear() {
        for (Telegraph t : telegraphs) {
            t.remove();
        }
        telegraphs.clear();
    }

    /**
     * Input from the captain at the helm: pressing opposite to the charge dodges it (within 4
     * blocks of the helm).
     */
    void steer(Player p, Member m, Set<Qte.Key> pressed) {
        for (Telegraph t : telegraphs) {
            Qte.Key side = t.chargeSide;
            if (side == null || t.evaded) {
                continue;
            }
            Interaction w = e.stations.wheel();
            if (w != null && BossItems.loc(w).distanceSquared(BossItems.loc(p)) > 16) {
                e.send(m.uuid, "boss.wheel.too_far");
                continue;
            }
            Qte.Key opposite = side.inverted();
            if (pressed.contains(opposite)) {
                t.evaded = true;
                e.mgr.api().ui().actionBar(p, e.msg("boss.charge.ok"));
            } else if (pressed.contains(side)) {
                e.mgr.api().ui().actionBar(p, e.msg("boss.charge.wrong"));
            }
        }
    }
}
