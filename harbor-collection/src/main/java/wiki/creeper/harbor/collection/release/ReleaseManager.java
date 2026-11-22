package wiki.creeper.harbor.collection.release;

import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import wiki.creeper.harbor.collection.api.ReleaseService;
import wiki.creeper.harbor.collection.api.event.FishReleasedEvent;
import wiki.creeper.harbor.collection.content.ReleaseRules;
import wiki.creeper.harbor.collection.data.CollectionRepository;
import wiki.creeper.harbor.collection.hook.NpcBridge;
import wiki.creeper.harbor.collection.service.ItemReturns;
import wiki.creeper.harbor.collection.service.Locs;
import wiki.creeper.harbor.common.content.type.FishDef;
import wiki.creeper.harbor.common.model.SeasonId;
import wiki.creeper.harbor.core.api.HarborApi;
import wiki.creeper.harbor.core.api.HarborItem;
import wiki.creeper.harbor.core.api.Modifier;
import wiki.creeper.harbor.core.api.event.ServerFlagChangedEvent;
import wiki.creeper.harbor.core.item.FishLedger;
import wiki.creeper.harbor.core.task.Tasks;

/**
 * 방류 (docs 20 4.4 '방류', Phase 3). 서버 플래그 climax_done(release.yml flag)이 서면 열린다.
 *
 * <ol>
 *   <li>메인: 어종 아이템 판정 → 확인 화면 → 확인 직후 아이템이 그대로인지 다시 보고 한 마리를 뺀다
 *   <li>가상 스레드 한 트랜잭션: 고유 ID는 장부 ACTIVE → RELEASED, release_log 기록 (추가 제안)
 *   <li>메인: FishReleasedEvent, 희귀 이상이면 평판(NPC RELEASE, 하루 상한은 NPC)과 행운 버프(size_luck, 겹치지 않고 시간만
 *       새로). 판매 대신이라 골드는 없다. 실패하면 아이템을 돌려준다 (오프라인·인벤토리가 차 있으면 우편함)
 * </ol>
 */
public final class ReleaseManager implements ReleaseService, Listener {

    static final String BUFF_SOURCE = "release_luck";
    private static final String RETURN_SOURCE = "release_return";

    private final HarborApi api;
    private final Tasks tasks;
    private final Logger log;
    private final ItemReturns returns;
    private final NpcBridge npc;

    public ReleaseManager(
            HarborApi api, Tasks tasks, Logger log, ItemReturns returns, NpcBridge npc) {
        this.api = api;
        this.tasks = tasks;
        this.log = log;
        this.returns = returns;
        this.npc = npc;
    }

    ReleaseRules rules() {
        return api.content().snapshot().single(ReleaseRules.TYPE);
    }

    @Override
    public boolean available() {
        return api.flags().hasServer(rules().flag());
    }

    /** 손에 든 아이템 방류 (/방류). */
    public CompletableFuture<Result> releaseHeld(Player player) {
        return release(player, player.getInventory().getItemInMainHand());
    }

    @Override
    public CompletableFuture<Result> release(Player player, ItemStack stack) {
        ReleaseRules rules = rules();
        Optional<HarborItem.Fish> item = fishOf(stack);
        Optional<FishDef> fish =
                item.flatMap(i -> api.content().snapshot().get(FishDef.TYPE, i.species().id()));
        ReleaseRules.Verdict verdict =
                ReleaseRules.check(
                        available(),
                        item.isPresent() && fish.isPresent(),
                        fish.map(FishDef::releaseOnly).orElse(false));
        switch (verdict) {
            case LOCKED -> {
                api.messages().send(player, "collection.release.locked");
                return CompletableFuture.completedFuture(Result.LOCKED);
            }
            case NOT_FISH, RELEASE_ONLY -> {
                api.messages().send(player, "collection.release.not_fish");
                return CompletableFuture.completedFuture(Result.NOT_ALLOWED);
            }
            case ALLOWED -> {}
        }
        HarborItem.Fish held = item.orElseThrow();
        FishDef def = fish.orElseThrow();
        boolean rewards = rules.rewards(def.grade());
        CompletableFuture<Result> result = new CompletableFuture<>();
        api.dialogs()
                .confirm(
                        player,
                        api.messages().get("collection.release.confirm_title"),
                        List.of(
                                api.messages()
                                        .get(
                                                "collection.release.confirm_body",
                                                Placeholder.unparsed("name", def.name()),
                                                Placeholder.unparsed(
                                                        "size",
                                                        String.format(
                                                                Locale.ROOT,
                                                                "%.1f",
                                                                held.sizeCm()))),
                                rewards
                                        ? api.messages()
                                                .get(
                                                        "collection.release.confirm_reward",
                                                        Placeholder.unparsed(
                                                                "minutes",
                                                                String.valueOf(
                                                                        rules.buffMinutes())))
                                        : api.messages()
                                                .get("collection.release.confirm_no_reward")),
                        api.messages().get("collection.release.button_yes"),
                        api.messages().get("collection.release.button_no"),
                        ok -> {
                            if (!ok) {
                                result.complete(Result.CANCELLED);
                                return;
                            }
                            // 확인 화면 사이에 아이템이 바뀌었거나 옮겨졌으면 취소
                            if (stack.getAmount() <= 0
                                    || !fishOf(stack).map(held::equals).orElse(false)
                                    || !player.getInventory().contains(stack)) {
                                api.messages().send(player, "collection.release.changed");
                                result.complete(Result.CANCELLED);
                                return;
                            }
                            ItemStack taken = stack.asOne();
                            stack.setAmount(stack.getAmount() - 1);
                            var _ = commit(player, taken, held, def).thenAccept(result::complete);
                        });
        return result;
    }

    private CompletableFuture<Result> commit(
            Player player, ItemStack taken, HarborItem.Fish item, FishDef fish) {
        UUID uuid = player.getUniqueId();
        SeasonId season = api.players().get(player).season();
        UUID uid = item.uid();
        return api.database()
                .asyncVoid(
                        c -> {
                            if (uid != null
                                    && !FishLedger.transition(
                                            c,
                                            uid,
                                            FishLedger.State.ACTIVE,
                                            FishLedger.State.RELEASED)) {
                                throw new SQLException("고유 ID 장부 상태가 ACTIVE가 아닙니다: " + uid);
                            }
                            CollectionRepository.logRelease(
                                    c,
                                    season,
                                    uuid,
                                    fish.id(),
                                    item.sizeCm(),
                                    item.quality().stars(),
                                    uid);
                        })
                .handleAsync(
                        (v, error) -> {
                            // 방류 기록은 즉시 커밋, 인벤토리는 지연 쓰기다: 곧 저장한다. 서버가 죽으면 물고기가 돌아오고 방류 평판을
                            // 다시 받을 수 있다
                            api.players().saveSoon(uuid);
                            if (error != null) {
                                log.warn(
                                        "방류 실패, 아이템을 돌려줍니다: {} {}",
                                        uuid,
                                        String.valueOf(error.getMessage()));
                                returns.giveBack(uuid, List.of(taken), RETURN_SOURCE);
                                if (player.isOnline()) {
                                    api.messages().send(player, "collection.release.failed");
                                }
                                return Result.FAILED;
                            }
                            afterRelease(player, fish, item, uid);
                            return Result.RELEASED;
                        },
                        tasks.main());
    }

    private void afterRelease(
            Player player, FishDef fish, HarborItem.Fish item, @Nullable UUID uid) {
        UUID uuid = player.getUniqueId();
        ReleaseRules rules = rules();
        Bukkit.getPluginManager()
                .callEvent(new FishReleasedEvent(uuid, fish.id(), fish.grade(), item.sizeCm()));
        if (player.isOnline()) {
            player.getWorld()
                    .spawnParticle(
                            Particle.SPLASH,
                            Locs.of(player).add(0, 0.5, 0),
                            30,
                            0.4,
                            0.2,
                            0.4,
                            0.05);
            player.playSound(player, Sound.ENTITY_FISH_SWIM, 1f, 1f);
        }
        if (!rules.rewards(fish.grade())) {
            api.messages()
                    .send(
                            player,
                            "collection.release.done",
                            Placeholder.unparsed("name", fish.name()));
            return;
        }
        // 행운 버프: 겹치지 않고 시간만 새로 (출처 release_luck)
        api.modifiers().removeSource(uuid, BUFF_SOURCE);
        api.modifiers()
                .add(
                        uuid,
                        Modifier.add(rules.buffStat(), rules.buffValue(), BUFF_SOURCE)
                                .until(Instant.now().plusSeconds(rules.buffMinutes() * 60L)));
        String ref = "release:" + (uid != null ? uid : UUID.randomUUID());
        var _ =
                npc.addReleaseReputation(uuid, rules.reputation(), ref)
                        .thenAcceptAsync(
                                applied -> {
                                    if (!player.isOnline()) {
                                        return;
                                    }
                                    Component line =
                                            api.messages()
                                                    .get(
                                                            applied > 0
                                                                    ? "collection.release.rewarded"
                                                                    : "collection.release.rewarded_capped",
                                                            Placeholder.unparsed(
                                                                    "name", fish.name()),
                                                            Placeholder.unparsed(
                                                                    "reputation",
                                                                    String.valueOf(applied)),
                                                            Placeholder.unparsed(
                                                                    "minutes",
                                                                    String.valueOf(
                                                                            rules.buffMinutes())));
                                    player.sendMessage(line);
                                },
                                tasks.main());
    }

    /** climax_done이 서면 접속자에게 방류가 열렸다고 알린다 (서버 간 전파는 Core FlagService). */
    @EventHandler
    public void onFlag(ServerFlagChangedEvent event) {
        if (event.flag().equals(rules().flag()) && event.value() != null) {
            Bukkit.getOnlinePlayers()
                    .forEach(p -> api.messages().send(p, "collection.release.opened"));
        }
    }

    private Optional<HarborItem.Fish> fishOf(@Nullable ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return Optional.empty();
        }
        return api.items()
                .read(stack)
                .filter(i -> i instanceof HarborItem.Fish)
                .map(i -> (HarborItem.Fish) i);
    }
}
