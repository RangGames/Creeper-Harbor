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
 * Release (docs 20 4.4 "release", Phase 3). Opens once server flag climax_done (release.yml flag)
 * is set.
 *
 * <ol>
 *   <li>Main: fish item check → confirmation screen → right after confirming, re-check the item is
 *       unchanged and remove one fish
 *   <li>One transaction on a virtual thread: unique-ID items go ACTIVE → RELEASED in the ledger,
 *       release_log record (proposed)
 *   <li>Main: FishReleasedEvent; for rare and above, reputation (NPC RELEASE, daily cap applied by
 *       NPC) and luck buff (size_luck, not stacked, duration restarts). It replaces selling, so no
 *       gold. On failure the item is returned (to the mailbox if offline or inventory is full)
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

    /** Releases the item in hand (/방류). */
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
                            // Cancel if the item changed or moved while the confirmation screen was
                            // open
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
                            // The release record commits immediately, the inventory is
                            // write-behind: save soon. If the server crashes, the fish comes back
                            // and release reputation
                            // can be earned again
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
        // Luck buff: not stacked, duration restarts (source release_luck)
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

    /**
     * When climax_done is set, tells online players that release is open (cross-server propagation
     * is Core FlagService).
     */
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
