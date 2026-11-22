package wiki.creeper.harbor.shop;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.UnaryOperator;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import wiki.creeper.harbor.common.content.ContentSnapshot;
import wiki.creeper.harbor.common.db.SqlFunction;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.common.redis.Channel;
import wiki.creeper.harbor.core.api.Harbor;
import wiki.creeper.harbor.core.api.HarborApi;
import wiki.creeper.harbor.core.api.HarborPlayer;
import wiki.creeper.harbor.core.api.PlayerComponent;
import wiki.creeper.harbor.core.task.Tasks;
import wiki.creeper.harbor.season.api.SeasonClock;
import wiki.creeper.harbor.season.api.SeasonServices;
import wiki.creeper.harbor.shop.content.CosmeticDef;
import wiki.creeper.harbor.shop.content.PassDef;
import wiki.creeper.harbor.shop.content.ShopSettings;
import wiki.creeper.harbor.shop.data.ShopProfile;
import wiki.creeper.harbor.shop.data.ShopRepository;
import wiki.creeper.harbor.shop.pearl.PearlLedger;

/**
 * What the Shop services share: Core API, task scheduling, pearl ledger, player cache
 * (ShopProfile), cross-server notices (harbor:shop). Methods that read or change the cache are
 * main-thread only.
 */
public final class ShopRuntime {

    /**
     * Player cache. Writes go through per-feature immediate transactions or the write-behind queue,
     * so the save function does nothing.
     */
    public static final PlayerComponent<ShopProfile> COMPONENT =
            new PlayerComponent<>(
                    "shop",
                    (c, uuid, season) ->
                            ShopRepository.load(c, uuid, season, graceOf(currentSettings())),
                    (c, uuid, season, value) -> {});

    /**
     * Notifies the server the player is on of a payment, refund or gift result (harbor:shop,
     * proposed). The receiving server re-reads the cache from the DB and, if the player is online,
     * shows unacknowledged notices.
     */
    public record ShopNotice(UUID player, String kind, long amount) {}

    /**
     * Ops alert (HELD payment, 5 inbox failures, reconciliation mismatch). Sent to holders of
     * harbor.admin.alerts.
     */
    public record ShopAlert(String key, String detail) {}

    public static final Channel<ShopNotice> NOTICES =
            new Channel<>("harbor:shop", ShopNotice.class);
    public static final Channel<ShopAlert> ALERTS = new Channel<>("harbor:shop", ShopAlert.class);

    private final HarborApi api;
    private final Tasks tasks;
    private final Logger log;
    private final PearlLedger ledger = new PearlLedger();

    public ShopRuntime(HarborApi api, Tasks tasks, Logger log) {
        this.api = api;
        this.tasks = tasks;
        this.log = log;
    }

    public HarborApi api() {
        return api;
    }

    public Tasks tasks() {
        return tasks;
    }

    public Logger log() {
        return log;
    }

    public PearlLedger ledger() {
        return ledger;
    }

    public ContentSnapshot snapshot() {
        return api.content().snapshot();
    }

    public ShopSettings settings() {
        return snapshot().single(ShopSettings.TYPE);
    }

    private static ShopSettings currentSettings() {
        return Harbor.api().content().snapshot().single(ShopSettings.TYPE);
    }

    public static Duration graceOf(ShopSettings settings) {
        return Duration.ofDays(settings.donorGraceDays());
    }

    public Duration grace() {
        return graceOf(settings());
    }

    public SeasonClock clock() {
        return SeasonServices.clock();
    }

    /** Current season number. */
    public int season() {
        return clock().current().value();
    }

    /** Pass for the current season (empty if none). */
    public Optional<PassDef> currentPass() {
        int season = season();
        return snapshot().all(PassDef.TYPE).values().stream()
                .filter(p -> p.season() == season)
                .findFirst();
    }

    public Optional<CosmeticDef> cosmetic(HarborId id) {
        return snapshot().get(CosmeticDef.TYPE, id);
    }

    // ------------------------------------------------------------------ cache (main thread)

    public Optional<HarborPlayer> online(UUID player) {
        return api.players().get(player);
    }

    public ShopProfile profile(UUID player) {
        return online(player).map(p -> p.get(COMPONENT)).orElse(ShopProfile.EMPTY);
    }

    /** Changes the cache if the player is online. */
    public void update(UUID player, UnaryOperator<ShopProfile> change) {
        online(player).ifPresent(p -> p.set(COMPONENT, change.apply(p.get(COMPONENT))));
    }

    /** Runs DB work on a virtual thread and receives the result on the main thread. */
    public <T> CompletableFuture<T> db(SqlFunction<T> work) {
        return api.database().async(work).thenApplyAsync(v -> v, tasks.main());
    }

    public void call(Event event) {
        Bukkit.getPluginManager().callEvent(event);
    }

    // ------------------------------------------------------------------ notices

    /**
     * Notifies the player on another server (or this one). If Redis is down, the shop_notice row
     * remains and is shown on next join.
     */
    public void notifyPlayer(UUID player, String kind, long amount) {
        api.bus().publish(NOTICES, new ShopNotice(player, kind, amount));
    }

    /** Ops alert (log + online admins, also on other servers). */
    public void alert(String key, String detail) {
        log.warn("[상점 알림] {} {}", key, detail);
        api.bus().publish(ALERTS, new ShopAlert(key, detail));
    }

    /** Shows it to admins on this server (main thread). */
    public void showAlert(ShopAlert alert) {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.hasPermission("harbor.admin.alerts")) {
                api.messages()
                        .send(
                                p,
                                "shop.alert." + alert.key(),
                                Placeholder.unparsed("detail", alert.detail()));
            }
        }
    }

    /** Admin action record (admin_log). */
    public void adminLog(
            @Nullable Player admin,
            String action,
            @Nullable String target,
            @Nullable String detail) {
        String who = admin == null ? "CONSOLE" : admin.getUniqueId().toString();
        var _ =
                api.database()
                        .asyncVoid(c -> ShopRepository.adminLog(c, who, action, target, detail))
                        .exceptionally(
                                e -> {
                                    log.error("admin_log 기록 실패: {} {}", action, target, e);
                                    return null;
                                });
    }
}
