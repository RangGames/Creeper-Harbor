package wiki.creeper.harbor.jobs.service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.content.type.ItemDef;
import wiki.creeper.harbor.common.model.JobLine;
import wiki.creeper.harbor.common.model.SeasonId;
import wiki.creeper.harbor.core.api.HarborApi;
import wiki.creeper.harbor.core.api.HarborItem;
import wiki.creeper.harbor.core.api.HarborPlayer;
import wiki.creeper.harbor.core.api.Modifier;
import wiki.creeper.harbor.core.task.Tasks;
import wiki.creeper.harbor.jobs.api.CapstoneState;
import wiki.creeper.harbor.jobs.api.event.CapstoneUsedEvent;
import wiki.creeper.harbor.jobs.content.JobLineDef;
import wiki.creeper.harbor.jobs.content.SkillNode;
import wiki.creeper.harbor.jobs.data.JobsData;
import wiki.creeper.harbor.jobs.data.JobsRepository;

/**
 * Capstone actives (docs 20 2.4 CapstoneService). The last use time is saved immediately, and on
 * join or server move the remaining time is computed to reapply the item cooldown (prevents
 * clearing the cooldown by rejoining). Area capstones (uncharted waters) apply a modifier to
 * players inside the area every second and remove it when they leave.
 */
public final class CapstoneService implements Listener {

    /**
     * Area effect. Lasts until its expiry even if the user leaves the server. Only the user and
     * their party members inside get it (docs 20 2.8: judged with the Core api PartyService;
     * without a party only the user).
     */
    private record Area(
            UUID owner,
            String source,
            Location center,
            double radius,
            Instant until,
            List<SkillNode.Effect> effects) {}

    private final HarborApi api;
    private final JobsManager jobs;
    private final Tasks tasks;
    private final List<Area> areas = new ArrayList<>();
    private final Map<UUID, Set<String>> insideAreas = new HashMap<>();

    public CapstoneService(HarborApi api, JobsManager jobs, Tasks tasks) {
        this.api = api;
        this.jobs = jobs;
        this.tasks = tasks;
    }

    public void start() {
        var _ = tasks.timer(20, 20, this::tickAreas);
    }

    /** Capstone of the player's line (only if invested). */
    private Optional<JobLineDef.Capstone> owned(HarborPlayer hp) {
        JobLine line = hp.jobLine().orElse(null);
        return jobs.lineDef(line)
                .map(JobLineDef::capstone)
                .filter(c -> jobs.data(hp).rank(c.node()) > 0);
    }

    public CapstoneState state(UUID uuid) {
        Optional<HarborPlayer> found = api.players().get(uuid);
        if (found.isEmpty()) {
            return new CapstoneState(Optional.empty(), Duration.ZERO, -1);
        }
        HarborPlayer hp = found.get();
        Optional<JobLineDef.Capstone> cap = owned(hp);
        if (cap.isEmpty()) {
            return new CapstoneState(Optional.empty(), Duration.ZERO, -1);
        }
        JobsData data = jobs.data(hp);
        return new CapstoneState(
                Optional.of(cap.get().node()),
                cooldownLeft(data, cap.get()),
                weeklyLeft(data, cap.get()));
    }

    private static Duration cooldownLeft(JobsData data, JobLineDef.Capstone cap) {
        Instant used = data.capstoneUsedAt();
        if (used == null || cap.cooldownSeconds() <= 0) {
            return Duration.ZERO;
        }
        Duration left = Duration.between(Instant.now(), used.plusSeconds(cap.cooldownSeconds()));
        return left.isNegative() ? Duration.ZERO : left;
    }

    private int weeklyLeft(JobsData data, JobLineDef.Capstone cap) {
        if (cap.weeklyUses() <= 0) {
            return -1;
        }
        int used = data.capstoneWeek() == api.time().seasonWeek() ? data.capstoneWeekUses() : 0;
        return Math.max(0, cap.weeklyUses() - used);
    }

    /**
     * Weekly capstone (big spender deal): Economy calls it before processing a sale. The use record
     * is saved immediately.
     */
    public CompletableFuture<Boolean> consumeWeeklyUse(UUID uuid) {
        Optional<HarborPlayer> found = api.players().get(uuid);
        if (found.isEmpty()) {
            return CompletableFuture.completedFuture(false);
        }
        HarborPlayer hp = found.get();
        Optional<JobLineDef.Capstone> cap = owned(hp);
        if (cap.isEmpty() || weeklyLeft(jobs.data(hp), cap.get()) <= 0) {
            return CompletableFuture.completedFuture(false);
        }
        return recordUse(hp)
                .thenApply(
                        _ -> {
                            Bukkit.getPluginManager()
                                    .callEvent(new CapstoneUsedEvent(uuid, cap.get().node()));
                            return true;
                        });
    }

    private CompletableFuture<Void> recordUse(HarborPlayer hp) {
        JobsData data = jobs.data(hp);
        int week = api.time().seasonWeek();
        int uses = (data.capstoneWeek() == week ? data.capstoneWeekUses() : 0) + 1;
        JobsData next = data.withCapstoneUse(Instant.now(), week, uses);
        hp.set(JobsManager.COMPONENT, next);
        UUID uuid = hp.uuid();
        SeasonId season = hp.season();
        return api.database()
                .asyncVoid(c -> JobsRepository.saveState(c, uuid, season, next))
                .thenRunAsync(() -> {}, tasks.main());
    }

    @EventHandler
    public void onUse(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR
                && event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        ItemStack item = event.getItem();
        if (item == null) {
            return;
        }
        Optional<HarborItem> read = api.items().read(item);
        if (read.isEmpty() || !(read.get() instanceof HarborItem.Plain plain)) {
            return;
        }
        Optional<JobLineDef> def =
                jobs.content().all(JobLineDef.TYPE).values().stream()
                        .filter(
                                d -> {
                                    JobLineDef.Capstone c = d.capstone();
                                    return c != null && plain.id().equals(c.item());
                                })
                        .findFirst();
        if (def.isEmpty()) {
            return;
        }
        event.setCancelled(true);
        Player player = event.getPlayer();
        HarborPlayer hp = api.players().get(player);
        JobLineDef.Capstone cap = def.get().capstone();
        if (cap == null) {
            return;
        }
        // After a line change, old capstone items cannot be used; tell the player to exchange them
        // at the guild
        if (hp.jobLine().orElse(null) != def.get().line()) {
            api.messages().send(player, "jobs.capstone.wrong_line");
            return;
        }
        if (jobs.data(hp).rank(cap.node()) <= 0) {
            api.messages().send(player, "jobs.capstone.locked");
            return;
        }
        Duration left = cooldownLeft(jobs.data(hp), cap);
        if (!left.isZero()) {
            api.messages()
                    .send(
                            player,
                            "jobs.capstone.cooldown",
                            Placeholder.unparsed("minutes", String.valueOf(left.toMinutes() + 1)));
            return;
        }
        SkillNode node = jobs.nodes().get(cap.node());
        if (node == null) {
            return;
        }
        var _ = recordUse(hp);
        Instant until = Instant.now().plusSeconds(cap.durationSeconds());
        String source = "capstone:" + cap.node().id().path();
        if (cap.radius() > 0) {
            areas.add(
                    new Area(
                            hp.uuid(),
                            source + ":" + UUID.randomUUID(),
                            Objects.requireNonNull(player.getLocation()),
                            cap.radius(),
                            until,
                            node.effects()));
        } else {
            for (SkillNode.Effect effect : node.effects()) {
                api.modifiers()
                        .add(
                                hp.uuid(),
                                new Modifier(
                                        effect.stat(),
                                        effect.op(),
                                        effect.valuePerRank(),
                                        source,
                                        until));
            }
        }
        applyCooldown(player, cap, Duration.ofSeconds(cap.cooldownSeconds()));
        api.messages().send(player, "jobs.capstone.used");
        Bukkit.getPluginManager().callEvent(new CapstoneUsedEvent(hp.uuid(), cap.node()));
    }

    /** Reapplies the remaining cooldown to items on join or server move. */
    public void restoreCooldown(Player player) {
        HarborPlayer hp = api.players().get(player);
        owned(hp)
                .ifPresent(
                        cap -> {
                            Duration left = cooldownLeft(jobs.data(hp), cap);
                            if (!left.isZero()) {
                                applyCooldown(player, cap, left);
                            }
                        });
    }

    public void clearCooldown(UUID uuid) {
        api.players()
                .get(uuid)
                .ifPresent(
                        hp -> {
                            JobsData next = jobs.data(hp).withCapstoneCleared();
                            hp.set(JobsManager.COMPONENT, next);
                            SeasonId season = hp.season();
                            var _ =
                                    api.database()
                                            .asyncVoid(
                                                    c ->
                                                            JobsRepository.saveState(
                                                                    c, uuid, season, next));
                            Player player = Bukkit.getPlayer(uuid);
                            if (player != null) {
                                owned(hp)
                                        .ifPresent(
                                                cap -> applyCooldown(player, cap, Duration.ZERO));
                            }
                        });
    }

    private void applyCooldown(Player player, JobLineDef.Capstone cap, Duration left) {
        Material material = material(cap);
        if (material != null) {
            player.setCooldown(material, (int) Math.min(Integer.MAX_VALUE, left.toSeconds() * 20));
        }
    }

    private @Nullable Material material(JobLineDef.Capstone cap) {
        if (cap.item() == null) {
            return null;
        }
        return jobs.content()
                .get(ItemDef.TYPE, cap.item().id())
                .map(d -> Material.matchMaterial(d.baseItem()))
                .orElse(null);
    }

    /** The user or a member of the user's party (party changes apply on the next second). */
    private boolean party(UUID owner, UUID player) {
        return owner.equals(player)
                || api.parties().map(p -> p.sameParty(owner, player)).orElse(false);
    }

    /**
     * Every second: applies the modifier to players inside the area, and removes it when they leave
     * or it expires.
     */
    private void tickAreas() {
        Instant now = Instant.now();
        areas.removeIf(a -> !now.isBefore(a.until()));
        Map<UUID, Set<String>> nowInside = new HashMap<>();
        for (Area area : areas) {
            for (Player player : area.center().getWorld().getPlayers()) {
                if (!party(area.owner(), player.getUniqueId())) {
                    continue;
                }
                if (Objects.requireNonNull(player.getLocation()).distanceSquared(area.center())
                        <= area.radius() * area.radius()) {
                    nowInside
                            .computeIfAbsent(player.getUniqueId(), k -> new HashSet<>())
                            .add(area.source());
                    if (!insideAreas
                            .getOrDefault(player.getUniqueId(), Set.of())
                            .contains(area.source())) {
                        for (SkillNode.Effect effect : area.effects()) {
                            api.modifiers()
                                    .add(
                                            player.getUniqueId(),
                                            new Modifier(
                                                    effect.stat(),
                                                    effect.op(),
                                                    effect.valuePerRank(),
                                                    area.source(),
                                                    area.until()));
                        }
                    }
                }
            }
        }
        insideAreas.forEach(
                (uuid, before) ->
                        before.stream()
                                .filter(
                                        source ->
                                                !nowInside
                                                        .getOrDefault(uuid, Set.of())
                                                        .contains(source))
                                .forEach(source -> api.modifiers().removeSource(uuid, source)));
        insideAreas.clear();
        insideAreas.putAll(nowInside);
    }
}
