package wiki.creeper.harbor.collection.hook;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.collection.api.AquariumService;
import wiki.creeper.harbor.collection.api.HintService;
import wiki.creeper.harbor.common.content.type.FishDef;
import wiki.creeper.harbor.common.id.FishId;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.common.id.RegionId;
import wiki.creeper.harbor.core.api.FlagService;
import wiki.creeper.harbor.core.api.HarborApi;
import wiki.creeper.harbor.npc.api.ActionResult;
import wiki.creeper.harbor.npc.api.ArgCheck;
import wiki.creeper.harbor.npc.api.DialogAction;
import wiki.creeper.harbor.npc.api.DialogArgs;
import wiki.creeper.harbor.npc.api.DialogContext;
import wiki.creeper.harbor.npc.api.DialogService;
import wiki.creeper.harbor.npc.api.ReputationService;
import wiki.creeper.harbor.npc.api.ReputationSource;
import wiki.creeper.harbor.npc.api.event.ReputationTierChangedEvent;

/**
 * NPC integration (docs 20 4.2·5.2, optional dependency). This class is loaded only when Harbor-Npc
 * is enabled.
 *
 * <ul>
 *   <li>Dialog action {@code aquarium:donate}: opens the donation box GUI (Iseul, aquarium keeper)
 *   <li>Dialog action {@code codex:hint}: one hint stage (HintService.grantRandom). Arguments
 *       {@code region} (optional, start from that region), {@code daily} (daily count, default 1,
 *       proposed value). The count is the season-scoped player flag collection/hint_day =
 *       "date:count"
 *   <li>Highest reputation tier (aquarium slots, D11) and release reputation (RELEASE)
 *   <li>When the reputation tier changes, recomputes that player's plot display slots
 * </ul>
 */
public final class NpcHooks implements NpcBridge, Listener {

    static final String HINT_FLAG = "collection/hint_day";

    private final HarborApi api;
    private @Nullable Consumer<UUID> tierChanged;

    private NpcHooks(HarborApi api) {
        this.api = api;
    }

    /** Registers dialog actions and returns the facade (module enable, before content load). */
    public static NpcBridge install(
            Plugin plugin, HarborApi api, AquariumService aquarium, HintService hints) {
        NpcHooks hooks = new NpcHooks(api);
        DialogService dialogs = Bukkit.getServicesManager().load(DialogService.class);
        if (dialogs != null) {
            dialogs.registerAction(
                    plugin,
                    "aquarium:donate",
                    (ctx, args) -> {
                        aquarium.openDonationBox(ctx.player());
                        return ActionResult.HANDOFF;
                    });
            dialogs.registerAction(plugin, "codex:hint", hooks.hintAction(hints));
        }
        Bukkit.getPluginManager().registerEvents(hooks, plugin);
        return hooks;
    }

    private DialogAction hintAction(HintService hints) {
        return new DialogAction() {
            @Override
            public ActionResult run(DialogContext ctx, DialogArgs args) {
                return hint(hints, ctx, args);
            }

            @Override
            public void validate(DialogArgs args, ArgCheck check) {
                args.string("region")
                        .ifPresent(
                                raw -> {
                                    Optional<HarborId> id = HarborId.tryParse(raw);
                                    if (id.isEmpty()
                                            || !id.get().hasPrefix(RegionId.PREFIX)
                                            || !check.snapshot().has("region", id.get())) {
                                        check.error("codex:hint region이 없는 지역입니다: " + raw);
                                    }
                                });
                if (args.has("daily")
                        && (!args.isInteger("daily") || args.integer("daily", 1) < 1)) {
                    check.error("codex:hint daily는 1 이상 정수여야 합니다");
                }
            }
        };
    }

    private ActionResult hint(HintService hints, DialogContext ctx, DialogArgs args) {
        Player player = ctx.player();
        UUID uuid = player.getUniqueId();
        int daily = Math.max(1, args.integer("daily", 1));
        String today = api.time().gameDay().toString();
        int used = usedToday(api.flags().playerValue(uuid, HINT_FLAG).orElse(null), today);
        if (used >= daily) {
            return ActionResult.failMessage("collection.hint.npc_limit");
        }
        RegionId region =
                args.string("region")
                        .flatMap(HarborId::tryParse)
                        .filter(id -> id.hasPrefix(RegionId.PREFIX))
                        .map(RegionId::new)
                        .orElse(null);
        Optional<FishId> fish = hints.grantRandom(uuid, region, "npc:" + ctx.npc().id().name());
        if (fish.isEmpty()) {
            return ActionResult.failMessage("collection.hint.none");
        }
        var _ =
                api.flags()
                        .setPlayer(
                                uuid,
                                FlagService.Scope.SEASON,
                                HINT_FLAG,
                                today + ":" + (used + 1));
        int no =
                api.content()
                        .snapshot()
                        .get(FishDef.TYPE, fish.get().id())
                        .map(FishDef::no)
                        .orElse(0);
        api.messages()
                .send(
                        player,
                        "collection.hint.granted",
                        Placeholder.unparsed("no", String.valueOf(no)),
                        Placeholder.unparsed(
                                "level", String.valueOf(hints.level(uuid, fish.get()))));
        return ActionResult.CONTINUE;
    }

    /** Today's used count from the flag value "date:count". */
    static int usedToday(@Nullable String value, String today) {
        if (value == null) {
            return 0;
        }
        int colon = value.lastIndexOf(':');
        if (colon < 0 || !value.substring(0, colon).equals(today)) {
            return 0;
        }
        try {
            return Integer.parseInt(value.substring(colon + 1));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    // ------------------------------------------------------------------ reputation

    private @Nullable ReputationService reputation() {
        return Bukkit.getServicesManager().load(ReputationService.class);
    }

    @Override
    public int bestTier(UUID player) {
        ReputationService rep = reputation();
        return rep == null ? 1 : rep.bestTier(player);
    }

    @Override
    public CompletableFuture<Integer> loadBestTier(UUID player) {
        ReputationService rep = reputation();
        if (rep == null) {
            return CompletableFuture.completedFuture(1);
        }
        if (Bukkit.getPlayer(player) != null) {
            return CompletableFuture.completedFuture(rep.bestTier(player));
        }
        return rep.loadBestTier(player);
    }

    @Override
    public CompletableFuture<Integer> addReleaseReputation(UUID player, int amount, String refId) {
        ReputationService rep = reputation();
        if (rep == null) {
            return CompletableFuture.completedFuture(0);
        }
        int delta = amount > 0 ? amount : rep.defaultValue(ReputationSource.RELEASE);
        return rep.add(player, delta, ReputationSource.RELEASE, refId)
                .thenApply(r -> r.ok() ? r.applied() : 0)
                .exceptionally(e -> 0);
    }

    @Override
    public void onTierChanged(Consumer<UUID> listener) {
        this.tierChanged = listener;
    }

    @EventHandler
    public void onTier(ReputationTierChangedEvent event) {
        Consumer<UUID> callback = tierChanged;
        if (callback != null) {
            callback.accept(event.player());
        }
    }
}
