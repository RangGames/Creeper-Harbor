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
 * Shop 서비스들이 함께 쓰는 것: Core API, 작업 예약, 진주 원장, 플레이어 캐시(ShopProfile), 서버 간 알림(harbor:shop). 캐시를 읽고
 * 바꾸는 메서드는 메인 스레드 전용이다.
 */
public final class ShopRuntime {

    /** 플레이어 캐시. 쓰기는 기능별로 즉시 트랜잭션 또는 지연 쓰기 큐로 따로 하므로 저장 함수는 아무것도 하지 않는다. */
    public static final PlayerComponent<ShopProfile> COMPONENT =
            new PlayerComponent<>(
                    "shop",
                    (c, uuid, season) ->
                            ShopRepository.load(c, uuid, season, graceOf(currentSettings())),
                    (c, uuid, season, value) -> {});

    /**
     * 결제·환불·선물 처리 결과를 플레이어가 있는 서버에 알린다 (harbor:shop, 추가 제안). 받은 서버는 캐시를 DB에서 다시 읽고, 접속 중이면 미확인 알림을
     * 보여 준다.
     */
    public record ShopNotice(UUID player, String kind, long amount) {}

    /** 운영 알림 (HELD 결제, 수신함 5회 실패, 대조 불일치). harbor.admin.alerts 권한자에게. */
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

    /** 지금 시즌 번호. */
    public int season() {
        return clock().current().value();
    }

    /** 지금 시즌의 패스 (없으면 빈 값). */
    public Optional<PassDef> currentPass() {
        int season = season();
        return snapshot().all(PassDef.TYPE).values().stream()
                .filter(p -> p.season() == season)
                .findFirst();
    }

    public Optional<CosmeticDef> cosmetic(HarborId id) {
        return snapshot().get(CosmeticDef.TYPE, id);
    }

    // ------------------------------------------------------------------ 캐시 (메인 스레드)

    public Optional<HarborPlayer> online(UUID player) {
        return api.players().get(player);
    }

    public ShopProfile profile(UUID player) {
        return online(player).map(p -> p.get(COMPONENT)).orElse(ShopProfile.EMPTY);
    }

    /** 접속 중이면 캐시를 바꾼다. */
    public void update(UUID player, UnaryOperator<ShopProfile> change) {
        online(player).ifPresent(p -> p.set(COMPONENT, change.apply(p.get(COMPONENT))));
    }

    /** DB 작업을 가상 스레드에서 하고 결과를 메인 스레드에서 받는다. */
    public <T> CompletableFuture<T> db(SqlFunction<T> work) {
        return api.database().async(work).thenApplyAsync(v -> v, tasks.main());
    }

    public void call(Event event) {
        Bukkit.getPluginManager().callEvent(event);
    }

    // ------------------------------------------------------------------ 알림

    /** 다른 서버(또는 이 서버)의 그 플레이어에게 알린다. Redis가 끊겨도 shop_notice 행이 남아 다음 접속 때 보인다. */
    public void notifyPlayer(UUID player, String kind, long amount) {
        api.bus().publish(NOTICES, new ShopNotice(player, kind, amount));
    }

    /** 운영 알림 (로그 + 접속 중인 관리자, 다른 서버에도). */
    public void alert(String key, String detail) {
        log.warn("[상점 알림] {} {}", key, detail);
        api.bus().publish(ALERTS, new ShopAlert(key, detail));
    }

    /** 이 서버의 관리자에게 보여 준다 (메인 스레드). */
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

    /** 관리자 작업 기록 (admin_log). */
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
