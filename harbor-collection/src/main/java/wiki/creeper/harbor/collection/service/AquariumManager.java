package wiki.creeper.harbor.collection.service;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.slf4j.Logger;
import wiki.creeper.harbor.collection.api.AquariumService;
import wiki.creeper.harbor.collection.api.event.AquariumDonatedEvent;
import wiki.creeper.harbor.collection.api.event.AquariumStageChangedEvent;
import wiki.creeper.harbor.collection.content.AquariumRules;
import wiki.creeper.harbor.collection.content.StatBonus;
import wiki.creeper.harbor.collection.data.CodexData;
import wiki.creeper.harbor.collection.data.CollectionRepository;
import wiki.creeper.harbor.collection.data.CollectionRepository.AquariumState;
import wiki.creeper.harbor.common.content.type.FishDef;
import wiki.creeper.harbor.common.model.Grade;
import wiki.creeper.harbor.common.model.SeasonId;
import wiki.creeper.harbor.common.redis.Channel;
import wiki.creeper.harbor.common.redis.Channels;
import wiki.creeper.harbor.common.redis.DistributedLock;
import wiki.creeper.harbor.core.api.HarborApi;
import wiki.creeper.harbor.core.api.HarborItem;
import wiki.creeper.harbor.core.api.HarborPlayer;
import wiki.creeper.harbor.core.api.Modifier;
import wiki.creeper.harbor.core.item.FishLedger;
import wiki.creeper.harbor.core.task.Tasks;

/**
 * 항구 수족관 (docs 20 4.4). 단계와 클라이맥스 조건은 점수가 아니라 기부율로 판정해, 한 어종을 대량으로 기부해서 밀어붙이는 것을 막는다. 수조
 * 연출(RigInstance)은 Core 리그 재생이 생기면 붙인다.
 */
public final class AquariumManager implements AquariumService {

    /** 다른 서버 동기화 (harbor:collection, 추가 제안). */
    public record AquariumUpdated(int season, int species, int stage) {}

    static final Channel<AquariumUpdated> UPDATES =
            new Channel<>("harbor:collection", AquariumUpdated.class);
    private static final String BUFF_SOURCE = "aquarium_stage";

    private final HarborApi api;
    private final Tasks tasks;
    private final Logger log;
    private volatile AquariumState state = AquariumState.EMPTY;

    public AquariumManager(HarborApi api, Tasks tasks, Logger log) {
        this.api = api;
        this.tasks = tasks;
        this.log = log;
    }

    public void start() {
        refresh();
        api.bus()
                .subscribe(
                        UPDATES,
                        envelope -> {
                            if (!envelope.server().equals(api.infra().serverId())) {
                                refresh();
                            }
                        });
    }

    /** DB에서 다시 읽고 버프를 교체 등록한다. */
    public void refresh() {
        SeasonId season = api.infra().season();
        var _ =
                api.database()
                        .async(c -> CollectionRepository.aquarium(c, season))
                        .thenAcceptAsync(
                                s -> {
                                    state = s;
                                    applyBuffs(s.stage());
                                },
                                tasks.main());
    }

    AquariumRules rules() {
        return api.content().snapshot().single(AquariumRules.TYPE);
    }

    /** 기부 가능 어종 수: 시즌 어종 중 신화·release_only 제외. */
    int donatable() {
        int season = api.infra().season().value();
        return (int)
                api.content().snapshot().all(FishDef.TYPE).values().stream()
                        .filter(
                                f ->
                                        f.season() <= season
                                                && f.grade() != Grade.MYTHIC
                                                && !f.releaseOnly())
                        .count();
    }

    @Override
    public Status status() {
        AquariumState s = state;
        int donatable = donatable();
        return new Status(
                s.points(),
                s.speciesCount(),
                donatable,
                donatable == 0 ? 0 : (double) s.speciesCount() / donatable,
                s.stage());
    }

    @Override
    public CompletableFuture<Boolean> donateHeld(Player player) {
        ItemStack held = player.getInventory().getItemInMainHand();
        Optional<HarborItem.Fish> item =
                api.items()
                        .read(held)
                        .filter(i -> i instanceof HarborItem.Fish)
                        .map(i -> (HarborItem.Fish) i);
        Optional<FishDef> fish =
                item.flatMap(i -> api.content().snapshot().get(FishDef.TYPE, i.species().id()));
        if (item.isEmpty() || fish.isEmpty() || fish.get().releaseOnly()) {
            api.messages().send(player, "collection.aquarium.not_fish");
            return CompletableFuture.completedFuture(false);
        }
        HarborPlayer hp = api.players().get(player);
        CodexData data = hp.get(CollectionManager.COMPONENT);
        AquariumRules rules = rules();
        int already = data.donations().getOrDefault(fish.get().id(), 0);
        boolean counted = already < rules.perPlayerSpeciesCap();
        int estimate =
                counted
                        ? rules.pointsFor(fish.get().grade(), item.get().quality().stars(), false)
                        : 0;
        CompletableFuture<Boolean> result = new CompletableFuture<>();
        api.dialogs()
                .confirm(
                        player,
                        api.messages().get("collection.aquarium.confirm_title"),
                        List.of(
                                api.messages()
                                        .get(
                                                "collection.aquarium.confirm_body",
                                                Placeholder.unparsed("name", fish.get().name()),
                                                Placeholder.unparsed(
                                                        "points", String.valueOf(estimate)))),
                        api.messages().get("collection.aquarium.yes"),
                        api.messages().get("collection.aquarium.no"),
                        ok -> {
                            if (!ok) {
                                result.complete(false);
                                return;
                            }
                            var _ =
                                    donate(player, hp, fish.get(), item.get(), counted)
                                            .thenAccept(result::complete);
                        });
        return result;
    }

    private CompletableFuture<Boolean> donate(
            Player player, HarborPlayer hp, FishDef fish, HarborItem.Fish item, boolean counted) {
        // 손의 아이템이 확인 화면 사이에 바뀌었으면 취소
        ItemStack held = player.getInventory().getItemInMainHand();
        if (!api.items().read(held).map(item::equals).orElse(false)) {
            api.messages().send(player, "collection.aquarium.changed");
            return CompletableFuture.completedFuture(false);
        }
        ItemStack taken = held.asOne();
        held.setAmount(held.getAmount() - 1);
        UUID uuid = hp.uuid();
        SeasonId season = hp.season();
        AquariumRules rules = rules();
        UUID itemUuid = item.uid();
        return api.database()
                .async(
                        c -> {
                            if (itemUuid != null
                                    && !FishLedger.transition(
                                            c,
                                            itemUuid,
                                            FishLedger.State.ACTIVE,
                                            FishLedger.State.DONATED)) {
                                throw new SQLException("고유 ID 장부 상태가 ACTIVE가 아닙니다: " + itemUuid);
                            }
                            boolean firstSpecies = counted && !speciesDonated(c, season, fish);
                            int points =
                                    counted
                                            ? rules.pointsFor(
                                                    fish.grade(),
                                                    item.quality().stars(),
                                                    firstSpecies)
                                            : 0;
                            CollectionRepository.donate(
                                    c,
                                    season,
                                    uuid,
                                    fish.id(),
                                    item.sizeCm(),
                                    item.quality().stars(),
                                    itemUuid,
                                    points);
                            return points;
                        })
                .handleAsync(
                        (points, error) -> {
                            if (error != null) {
                                log.warn(
                                        "수족관 기부 실패, 아이템을 돌려줍니다: {} {}",
                                        player.getName(),
                                        error.getMessage());
                                player.getInventory()
                                        .addItem(taken)
                                        .values()
                                        .forEach(
                                                left ->
                                                        player.getWorld()
                                                                .dropItem(
                                                                        Objects.requireNonNull(
                                                                                player
                                                                                        .getLocation()),
                                                                        left));
                                api.messages().send(player, "collection.aquarium.failed");
                                return false;
                            }
                            hp.set(
                                    CollectionManager.COMPONENT,
                                    hp.get(CollectionManager.COMPONENT).withDonation(fish.id()));
                            Bukkit.getPluginManager()
                                    .callEvent(
                                            new AquariumDonatedEvent(
                                                    uuid,
                                                    fish.id(),
                                                    fish.grade(),
                                                    item.quality(),
                                                    points));
                            api.messages()
                                    .send(
                                            player,
                                            "collection.aquarium.donated",
                                            Placeholder.unparsed("name", fish.name()),
                                            Placeholder.unparsed("points", String.valueOf(points)));
                            var _ = tasks.async(this::checkStage);
                            return true;
                        },
                        tasks.main());
    }

    private static boolean speciesDonated(Connection c, SeasonId season, FishDef fish)
            throws SQLException {
        try (PreparedStatement ps =
                c.prepareStatement(
                        "SELECT 1 FROM aquarium_species WHERE season = ? AND fish_id = ?")) {
            ps.setInt(1, season.value());
            ps.setString(2, fish.id().toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    /** 가상 스레드: 기부율을 다시 보고, 단계를 올릴 수 있으면 분산 잠금 + 조건부 UPDATE로 한 번만 올린다. */
    void checkStage() {
        SeasonId season = api.infra().season();
        Optional<DistributedLock.Handle> lock;
        try {
            lock = api.locks().tryAcquire("harbor:lock:aquarium", Duration.ofSeconds(30));
        } catch (RuntimeException e) {
            log.warn("수족관 잠금 실패: {}", e.getMessage());
            return;
        }
        if (lock.isEmpty()) {
            return;
        }
        try {
            AquariumState current =
                    api.database().tx(c -> CollectionRepository.aquarium(c, season));
            int donatable = donatable();
            double rate = donatable == 0 ? 0 : (double) current.speciesCount() / donatable;
            int target = current.stage();
            for (AquariumRules.Stage stage : rules().stages()) {
                if (rate >= stage.rate()) {
                    target = Math.max(target, stage.stage());
                }
            }
            int finalTarget = target;
            boolean raised =
                    target > current.stage()
                            && api.database()
                                    .tx(
                                            c ->
                                                    CollectionRepository.raiseStage(
                                                            c, season, finalTarget));
            AquariumState next =
                    new AquariumState(
                            current.points(),
                            current.speciesCount(),
                            raised ? target : current.stage());
            tasks.main()
                    .execute(
                            () -> {
                                state = next;
                                if (raised) {
                                    onStageRaised(season, finalTarget);
                                }
                                api.bus()
                                        .publish(
                                                UPDATES,
                                                new AquariumUpdated(
                                                        season.value(),
                                                        next.speciesCount(),
                                                        next.stage()));
                            });
        } catch (SQLException e) {
            log.error("수족관 단계 확인 실패", e);
        } finally {
            lock.get().release();
        }
    }

    /** 단계를 올린 서버만: 서버 플래그, 이벤트, 공지. */
    private void onStageRaised(SeasonId season, int stage) {
        applyBuffs(stage);
        for (AquariumRules.Stage s : rules().stages()) {
            if (s.stage() <= stage && !s.flag().isEmpty()) {
                var _ = api.flags().setServer(s.flag(), "1", true);
            }
        }
        Bukkit.getPluginManager().callEvent(new AquariumStageChangedEvent(season.value(), stage));
        Component msg =
                api.messages()
                        .get(
                                "collection.announce.aquarium_stage",
                                Placeholder.unparsed("stage", String.valueOf(stage)));
        api.bus()
                .publish(
                        Channels.ANNOUNCEMENTS,
                        new Channels.Announcement(MiniMessage.miniMessage().serialize(msg)));
    }

    /** 단계 버프 (출처 aquarium_stage, 서버 전체 보정자): 교체 등록. */
    private void applyBuffs(int stage) {
        api.modifiers().removeGlobalSource(BUFF_SOURCE);
        for (AquariumRules.Stage s : rules().stages()) {
            if (s.stage() <= stage) {
                for (StatBonus bonus : s.modifiers()) {
                    api.modifiers()
                            .addGlobal(Modifier.add(bonus.stat(), bonus.value(), BUFF_SOURCE));
                }
            }
        }
    }

    /** 관리자: 기부 기록에서 다시 집계. */
    public CompletableFuture<Void> recount() {
        SeasonId season = api.infra().season();
        return api.database()
                .asyncVoid(c -> CollectionRepository.recount(c, season))
                .thenRun(this::refresh);
    }

    /** 관리자: 단계 강제 (올리기만). */
    public CompletableFuture<Boolean> forceStage(int stage) {
        SeasonId season = api.infra().season();
        return api.database()
                .async(c -> CollectionRepository.raiseStage(c, season, stage))
                .thenApplyAsync(
                        raised -> {
                            if (raised) {
                                state =
                                        new AquariumState(
                                                state.points(), state.speciesCount(), stage);
                                onStageRaised(season, stage);
                            }
                            return raised;
                        },
                        tasks.main());
    }
}
