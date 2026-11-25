package wiki.creeper.harbor.boss.encounter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.boss.api.BossRole;
import wiki.creeper.harbor.boss.api.BossService.StartResult;
import wiki.creeper.harbor.boss.api.event.BossEncounterStartEvent;
import wiki.creeper.harbor.boss.content.BossBalance;
import wiki.creeper.harbor.boss.content.BossDef;
import wiki.creeper.harbor.boss.logic.UuidV7;
import wiki.creeper.harbor.boss.party.PartyManager;
import wiki.creeper.harbor.common.id.BossId;
import wiki.creeper.harbor.common.id.ItemId;
import wiki.creeper.harbor.common.rig.Vec3;

/**
 * Encounter start (docs 20 10.4 steps 1-2): summon checks, slot selection, summon cooldown, and
 * creation of field, instance and world boss encounters. Main thread only.
 */
final class EncounterStarter {

    private final EncounterManager mgr;
    private final PartyManager parties;
    private final Map<UUID, Long> summonCooldown = new HashMap<>();

    EncounterStarter(EncounterManager mgr, PartyManager parties) {
        this.mgr = mgr;
        this.parties = parties;
    }

    List<Player> partyPlayers(Player leader) {
        List<Player> out = new ArrayList<>();
        for (UUID u : parties.ordered(leader.getUniqueId())) {
            Player p = Bukkit.getPlayer(u);
            if (p != null) {
                out.add(p);
            }
        }
        if (out.isEmpty()) {
            out.add(leader);
        }
        return out;
    }

    /**
     * Summon bait use (docs 20 10.4 step 1). Conditions: party size min-max, everyone within the
     * arena radius, a free slot, not under maintenance.
     */
    StartResult trySummon(Player leader, BossDef def) {
        if (def.arena().mode() != BossDef.ArenaMode.FIELD) {
            return StartResult.WRONG_SERVER;
        }
        if (!parties.isLeader(leader.getUniqueId())) {
            return StartResult.NOT_LEADER;
        }
        Long until = summonCooldown.get(leader.getUniqueId());
        if (until != null && until > mgr.now()) {
            return StartResult.COOLDOWN;
        }
        BossDef.Summon summon = def.summon();
        List<Player> party = partyPlayers(leader);
        return startField(
                def,
                leader,
                party,
                summon == null ? null : summon.item(),
                false,
                BossItems.loc(leader));
    }

    /**
     * Quest or dialog start without a summon bait ('3. item list' 3: "the first main chapter 2
     * encounter is summoned by the quest directly"; also the chapter 6 story fight). The player's
     * party (or the player alone) is moved to the free slot nearest to them; party size min and the
     * arena radius are not checked (max still applies). Uses the same per-leader cooldown as summon
     * bait (summon.cooldown, otherwise balance.yml boss.scripted_cooldown_seconds).
     */
    StartResult tryScripted(Player leader, BossDef def) {
        if (def.arena().mode() != BossDef.ArenaMode.FIELD) {
            return StartResult.WRONG_SERVER;
        }
        if (!parties.isLeader(leader.getUniqueId())) {
            return StartResult.NOT_LEADER;
        }
        Long until = summonCooldown.get(leader.getUniqueId());
        if (until != null && until > mgr.now()) {
            return StartResult.COOLDOWN;
        }
        StartResult r =
                startField(def, leader, partyPlayers(leader), null, true, BossItems.loc(leader));
        if (r == StartResult.STARTED) {
            BossDef.Summon summon = def.summon();
            long seconds =
                    summon != null
                            ? summon.cooldownSeconds()
                            : mgr.balance().scriptedCooldownSeconds();
            summonCooldown.put(leader.getUniqueId(), mgr.now() + seconds * 20L);
        }
        return r;
    }

    StartResult startField(
            BossDef def,
            Player leader,
            List<Player> party,
            @Nullable ItemId bait,
            boolean forced,
            Location near) {
        BossBalance bb = mgr.balance();
        if (!forced && (party.size() < def.party().min() || party.size() > def.party().max())) {
            return StartResult.PARTY_SIZE;
        }
        if (party.size() > def.party().max()) {
            party = party.subList(0, def.party().max());
        }
        for (Player p : party) {
            if (mgr.inEncounter(p.getUniqueId())) {
                return StartResult.BUSY;
            }
        }
        long fieldCount =
                mgr.active().stream()
                        .filter(e -> e.def.arena().mode() == BossDef.ArenaMode.FIELD)
                        .count();
        if (fieldCount >= bb.maxFieldEncounters()) {
            return StartResult.NO_SLOT;
        }
        Optional<World> world = mgr.items().world(def.arena().world());
        if (world.isEmpty() || !world.get().equals(near.getWorld())) {
            return forced ? StartResult.WRONG_SERVER : StartResult.OUT_OF_RANGE;
        }
        // Nearest free slot
        int best = -1;
        double bestDist = Double.MAX_VALUE;
        List<BossDef.Slot> slots = def.arena().slots();
        for (int i = 0; i < slots.size(); i++) {
            if (slotBusy(def.id(), i)) {
                continue;
            }
            Vec3 c = slots.get(i).center();
            double d = near.distanceSquared(new Location(world.get(), c.x(), c.y(), c.z()));
            if (d < bestDist) {
                bestDist = d;
                best = i;
            }
        }
        if (best < 0) {
            return StartResult.NO_SLOT;
        }
        BossDef.Slot slot = slots.get(best);
        Location center =
                new Location(world.get(), slot.center().x(), slot.center().y(), slot.center().z());
        if (!forced) {
            double r2 = slot.radius() * slot.radius();
            for (Player p : party) {
                Location at = BossItems.loc(p);
                if (!world.get().equals(at.getWorld()) || at.distanceSquared(center) > r2) {
                    return StartResult.OUT_OF_RANGE;
                }
            }
        }
        StartResult r =
                create(
                        def,
                        bb,
                        world.get(),
                        center,
                        best,
                        leader.getUniqueId(),
                        party,
                        bait,
                        forced,
                        true);
        if (r == StartResult.STARTED && def.summon() != null && !forced) {
            summonCooldown.put(
                    leader.getUniqueId(), mgr.now() + def.summon().cooldownSeconds() * 20L);
        }
        return r;
    }

    /** Instance server: starts a raid with the arrived party (InstanceManager). */
    StartResult startInstance(
            BossDef def,
            int slot,
            Location center,
            UUID leader,
            List<Player> party,
            Map<UUID, String> origins) {
        StartResult r =
                create(
                        def,
                        mgr.balance(),
                        center.getWorld(),
                        center,
                        slot,
                        leader,
                        party,
                        null,
                        true,
                        true);
        if (r == StartResult.STARTED && !party.isEmpty()) {
            Encounter e = mgr.encounterOfPlayer(party.getFirst().getUniqueId()).orElse(null);
            if (e != null) {
                e.origins.putAll(origins);
            }
        }
        return r;
    }

    /** World boss start (WorldBossManager). Spawns immediately without a preparation stage. */
    @Nullable Encounter startWorld(BossDef def, UUID eventId, Location center, long seed) {
        BossBalance bb = mgr.balance();
        Encounter e =
                new Encounter(
                        mgr,
                        eventId,
                        def,
                        bb,
                        center.getWorld(),
                        center,
                        -1,
                        new UUID(0, 0),
                        List.of(),
                        Map.of(),
                        seed,
                        null,
                        true);
        BossEncounterStartEvent event = new BossEncounterStartEvent(e.info(), true);
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled()) {
            return null;
        }
        mgr.register(e);
        e.begin(false);
        return e;
    }

    private StartResult create(
            BossDef def,
            BossBalance bb,
            World world,
            Location center,
            int slot,
            UUID leader,
            List<Player> party,
            @Nullable ItemId bait,
            boolean forced,
            boolean prepare) {
        Map<UUID, BossRole> preset = new HashMap<>();
        for (Player p : party) {
            Optional<BossRole> role = parties.roleOf(p.getUniqueId());
            if (role.isEmpty()) {
                role =
                        mgr.api()
                                .players()
                                .get(p.getUniqueId())
                                .map(h -> h.get(EncounterManager.PROFILE).preferred());
            }
            role.ifPresent(r -> preset.put(p.getUniqueId(), r));
        }
        UUID id = UuidV7.next();
        long seed = new java.security.SecureRandom().nextLong();
        Encounter e =
                new Encounter(
                        mgr, id, def, bb, world, center, slot, leader, party, preset, seed, bait,
                        forced);
        BossEncounterStartEvent event = new BossEncounterStartEvent(e.info(), forced);
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled()) {
            return StartResult.CANCELLED;
        }
        mgr.register(e);
        for (Player p : party) {
            mgr.bind(p.getUniqueId(), e);
        }
        e.begin(prepare);
        return StartResult.STARTED;
    }

    private boolean slotBusy(BossId boss, int slot) {
        for (Encounter e : mgr.active()) {
            if (e.def.id().equals(boss) && e.slot == slot) {
                return true;
            }
        }
        return false;
    }

    /** Whether an instance cell is in use. */
    boolean instanceSlotBusy(int slot) {
        for (Encounter e : mgr.active()) {
            if (e.def.arena().mode() == BossDef.ArenaMode.INSTANCE && e.slot == slot) {
                return true;
            }
        }
        return false;
    }

    /** Clears expired summon cooldowns (every minute). */
    void purgeCooldowns(long now) {
        summonCooldown.values().removeIf(t -> t < now);
    }
}
