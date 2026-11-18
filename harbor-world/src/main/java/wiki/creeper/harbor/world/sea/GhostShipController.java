package wiki.creeper.harbor.world.sea;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.id.RegionId;
import wiki.creeper.harbor.world.WorldKit;
import wiki.creeper.harbor.world.api.ActiveSeaEvent;
import wiki.creeper.harbor.world.api.TreasureService;
import wiki.creeper.harbor.world.content.SeaEventDef;
import wiki.creeper.harbor.world.region.RegionManager;

/**
 * 유령선 습격 해골 해적 (docs 20 8.4 해상 이벤트 표, 0.1: World가 직접 소환·관리, 몹 정의는 events.yml ghost, Boss에 의존하지 않음).
 * 이 서버에 있는 해역에서만 웨이브마다 해역 안 플레이어 주변에 해적을 띄우고, 세 번째 웨이브마다 선장을 띄운다. 선장을 쓰러뜨린 플레이어는 실행당 한 번 유령선 보물 지도
 * (treasure.yml ghost 등급)를 받는다. 해적은 저장하지 않고(setPersistent false) 드롭이 없으며, 이벤트가 끝나면 모두 지운다.
 */
public final class GhostShipController implements Listener {

    private static final class Run {
        final ActiveSeaEvent event;
        final SeaEventDef.Ghost ghost;
        final Set<UUID> alive = new HashSet<>();
        final Set<UUID> rewarded = new HashSet<>();
        int wave;
        @Nullable BukkitTask task;

        Run(ActiveSeaEvent event, SeaEventDef.Ghost ghost) {
            this.event = event;
            this.ghost = ghost;
        }
    }

    private final WorldKit kit;
    private final RegionManager regions;
    private final TreasureService treasure;
    private final Map<String, Run> runs = new HashMap<>();

    public GhostShipController(WorldKit kit, RegionManager regions, TreasureService treasure) {
        this.kit = kit;
        this.regions = regions;
        this.treasure = treasure;
    }

    void begin(ActiveSeaEvent event, SeaEventDef def) {
        SeaEventDef.Ghost ghost = def.ghost();
        RegionId region = event.region();
        if (ghost == null || region == null || runs.containsKey(event.runId())) {
            return;
        }
        boolean local =
                kit.index()
                        .get(region)
                        .map(e -> kit.bukkitWorld(e.def().world()) != null)
                        .orElse(false);
        if (!local) {
            return;
        }
        Run run = new Run(event, ghost);
        runs.put(event.runId(), run);
        long period = 20L * ghost.waveIntervalSeconds();
        run.task = kit.tasks().timer(100, period, () -> wave(run));
    }

    void end(ActiveSeaEvent event) {
        Run run = runs.remove(event.runId());
        if (run == null) {
            return;
        }
        BukkitTask task = run.task;
        if (task != null) {
            task.cancel();
        }
        for (UUID id : run.alive) {
            Entity e = Bukkit.getEntity(id);
            if (e != null) {
                e.remove();
            }
        }
    }

    private void wave(Run run) {
        RegionId region = run.event.region();
        if (region == null) {
            return;
        }
        run.alive.removeIf(
                id -> {
                    Entity e = Bukkit.getEntity(id);
                    return e == null || !e.isValid();
                });
        List<Player> players = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (regions.current(p.getUniqueId()).filter(region::equals).isPresent()) {
                players.add(p);
            }
        }
        if (players.isEmpty()) {
            return;
        }
        run.wave++;
        SeaEventDef.Ghost g = run.ghost;
        for (Player p : players) {
            kit.actionBar(
                    p,
                    "world.event.ghost_wave",
                    Placeholder.unparsed("wave", String.valueOf(run.wave)));
            for (int i = 0; i < g.perPlayer() && run.alive.size() < g.maxAlive(); i++) {
                spawn(run, pick(g.mobs()), p, false);
            }
        }
        SeaEventDef.PirateMob captain = g.captain();
        if (captain != null && run.wave % 3 == 0 && run.alive.size() < g.maxAlive()) {
            Player target = players.get(ThreadLocalRandom.current().nextInt(players.size()));
            spawn(run, captain, target, true);
            for (Player p : players) {
                kit.send(p, "world.event.ghost_captain");
            }
        }
    }

    private static SeaEventDef.PirateMob pick(List<SeaEventDef.PirateMob> mobs) {
        int total = mobs.stream().mapToInt(SeaEventDef.PirateMob::weight).sum();
        if (total <= 0) {
            return mobs.getFirst();
        }
        int roll = ThreadLocalRandom.current().nextInt(total);
        for (SeaEventDef.PirateMob m : mobs) {
            roll -= m.weight();
            if (roll < 0) {
                return m;
            }
        }
        return mobs.getLast();
    }

    private void spawn(Run run, SeaEventDef.PirateMob mob, Player near, boolean captain) {
        Location at = WorldKit.loc(near);
        World world = near.getWorld();
        double angle = ThreadLocalRandom.current().nextDouble() * Math.PI * 2;
        double r =
                run.ghost.minRadius()
                        + ThreadLocalRandom.current().nextDouble()
                                * (run.ghost.maxRadius() - run.ghost.minRadius());
        int x = (int) Math.floor(at.getX() + Math.cos(angle) * r);
        int z = (int) Math.floor(at.getZ() + Math.sin(angle) * r);
        int y = world.getHighestBlockYAt(x, z) + 1;
        Location spawn = new Location(world, x + 0.5, y, z + 0.5);
        EntityType type;
        try {
            type = EntityType.valueOf(mob.entity().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            type = EntityType.SKELETON;
        }
        if (!(world.spawnEntity(spawn, type) instanceof LivingEntity entity)) {
            return;
        }
        entity.setPersistent(false);
        entity.getPersistentDataContainer()
                .set(kit.key("pirate"), PersistentDataType.STRING, run.event.runId());
        if (captain) {
            entity.getPersistentDataContainer()
                    .set(kit.key("pirate_captain"), PersistentDataType.BYTE, (byte) 1);
        }
        if (!mob.name().isEmpty()) {
            entity.customName(MiniMessage.miniMessage().deserialize(mob.name()));
            entity.setCustomNameVisible(true);
        }
        AttributeInstance health = entity.getAttribute(Attribute.MAX_HEALTH);
        if (health != null) {
            health.setBaseValue(mob.health());
            entity.setHealth(mob.health());
        }
        EntityEquipment equipment = entity.getEquipment();
        if (equipment != null) {
            item(mob.weapon()).ifPresent(equipment::setItemInMainHand);
            item(mob.helmet()).ifPresent(equipment::setHelmet);
            equipment.setItemInMainHandDropChance(0);
            equipment.setHelmetDropChance(0);
        }
        if (entity instanceof Mob m) {
            m.setTarget(near);
        }
        run.alive.add(entity.getUniqueId());
    }

    private static Optional<ItemStack> item(@Nullable String key) {
        if (key == null) {
            return Optional.empty();
        }
        Material material = Material.matchMaterial(key);
        return material == null ? Optional.empty() : Optional.of(ItemStack.of(material));
    }

    @EventHandler
    public void onDeath(EntityDeathEvent event) {
        LivingEntity entity = event.getEntity();
        String runId =
                entity.getPersistentDataContainer()
                        .get(kit.key("pirate"), PersistentDataType.STRING);
        if (runId == null) {
            return;
        }
        event.getDrops().clear();
        event.setDroppedExp(0);
        Run run = runs.get(runId);
        if (run == null) {
            return;
        }
        run.alive.remove(entity.getUniqueId());
        Player killer = entity.getKiller();
        RegionId region = run.event.region();
        if (killer == null
                || region == null
                || !entity.getPersistentDataContainer()
                        .has(kit.key("pirate_captain"), PersistentDataType.BYTE)) {
            return;
        }
        if (!run.rewarded.add(killer.getUniqueId())) {
            return;
        }
        treasure.createMap(killer, region, run.ghost.treasureTier())
                .ifPresent(
                        map -> {
                            kit.give(killer, map);
                            kit.send(killer, "world.event.ghost_treasure");
                        });
    }

    /** 재시작 뒤 청크에 남은 해적을 지운다 (저장하지 않지만 안전장치). */
    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        for (Entity e : event.getEntities()) {
            String runId =
                    e.getPersistentDataContainer()
                            .get(kit.key("pirate"), PersistentDataType.STRING);
            if (runId != null && !runs.containsKey(runId)) {
                e.remove();
            }
        }
    }

    public int alive() {
        return runs.values().stream().mapToInt(r -> r.alive.size()).sum();
    }
}
