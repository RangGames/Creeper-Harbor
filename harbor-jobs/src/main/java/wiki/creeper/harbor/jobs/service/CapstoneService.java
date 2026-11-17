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
 * 캡스톤 액티브 (docs 20 2.4 CapstoneService). 마지막 사용 시각은 즉시 저장하고, 접속·서버 이동 때 남은 시간을 계산해 아이템 쿨타임을 다시 건다
 * (재접속으로 쿨타임을 지우는 것을 막음). 영역형(미개척 해역)은 1초마다 영역 안 플레이어에게 보정자를 걸고 벗어나면 뗀다.
 */
public final class CapstoneService implements Listener {

    /** 영역 효과. 사용자가 서버를 떠나도 만료 시각까지 유지한다. */
    private record Area(
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

    /** 플레이어 계열의 캡스톤 (투자했을 때만). */
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

    /** 주간 사용 캡스톤 (큰손 거래): Economy가 판매 처리 전에 부른다. 사용 기록은 즉시 저장. */
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
        // 계열이 바뀐 뒤의 옛 캡스톤 아이템은 쓰지 못하게 하고 길드에서 교환하도록 안내한다
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

    /** 접속·서버 이동 때 남은 쿨타임을 아이템에 다시 건다. */
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

    /** 1초마다: 영역 안 플레이어에게 보정자를 걸고, 벗어나거나 만료되면 뗀다. */
    private void tickAreas() {
        Instant now = Instant.now();
        areas.removeIf(a -> !now.isBefore(a.until()));
        Map<UUID, Set<String>> nowInside = new HashMap<>();
        for (Area area : areas) {
            for (Player player : area.center().getWorld().getPlayers()) {
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
