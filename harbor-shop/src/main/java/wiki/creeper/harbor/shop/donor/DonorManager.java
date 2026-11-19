package wiki.creeper.harbor.shop.donor;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import wiki.creeper.harbor.common.donor.DonorTier;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.common.redis.DistributedLock;
import wiki.creeper.harbor.season.api.SeasonServices;
import wiki.creeper.harbor.season.api.TitleId;
import wiki.creeper.harbor.season.api.TitleService;
import wiki.creeper.harbor.shop.ShopRuntime;
import wiki.creeper.harbor.shop.api.DonorService;
import wiki.creeper.harbor.shop.api.event.DonorTierChangedEvent;
import wiki.creeper.harbor.shop.content.CosmeticDef;
import wiki.creeper.harbor.shop.content.ShopSettings;
import wiki.creeper.harbor.shop.data.ShopProfile;
import wiki.creeper.harbor.shop.data.ShopRepository;

/**
 * 후원 등급 (DonorService 구현, docs 20 12.4 '후원 등급'). 구독 원본은 subscriptions이고, 상태가 바뀔 때마다 Redis 해시
 * harbor:donor(필드 = UUID, 값 = 등급 이름)를 맞춘다. 3일 유예는 값을 지우는 시점에 반영한다. 서버가 켜질 때 DB 기준으로 해시 전체를 다시 맞춘다
 * (Redis 재시작·누락 대비).
 *
 * <p>상태형 혜택(이름 색·후원자 칭호·채팅 아이콘·대기열 우선·어항 테마 칸 +2)은 구독 상태로 계산하고 끝나면 사라진다. 소유형 혜택(선단 월 1회 한정 코스메틱, 월
 * 진주)은 지급 기록이라 해지 후에도 남는다.
 */
public final class DonorManager implements DonorService {

    private static final String RECONCILE_LOCK = "harbor:lock:donor-reconcile";
    private static final Duration LOCK_TTL = Duration.ofMinutes(5);
    private static final long SWEEP_TICKS = 20L * 60 * 10;

    private final ShopRuntime rt;

    public DonorManager(ShopRuntime rt) {
        this.rt = rt;
    }

    public void start() {
        var _ = rt.tasks().async(this::reconcile);
        var _ =
                rt.tasks()
                        .timer(
                                SWEEP_TICKS,
                                SWEEP_TICKS,
                                () -> {
                                    var _ = rt.tasks().async(this::sweep);
                                });
    }

    @Override
    public DonorTier tier(UUID player) {
        return rt.profile(player).tier();
    }

    @Override
    public Perks perks(UUID player) {
        DonorTier tier = tier(player);
        if (tier == DonorTier.NONE) {
            return Perks.NONE;
        }
        ShopSettings.DonorPerk perk = rt.settings().perk(tier);
        return new Perks(
                tier,
                perk.nameColor(),
                perk.chatIcon(),
                perk.title(),
                tier.queuePriority(),
                tier.extraThemeSlots());
    }

    // ------------------------------------------------------------------ Redis harbor:donor

    /** 해시 한 칸을 맞춘다 (가상 스레드). Redis 장애면 로그만 남긴다 (다음 시작 대조가 맞춘다). */
    public void syncRedis(UUID player, DonorTier tier) {
        try {
            if (tier == DonorTier.NONE) {
                rt.api().redis().sync().hdel(DonorTier.REDIS_KEY, player.toString());
            } else {
                rt.api().redis().sync().hset(DonorTier.REDIS_KEY, player.toString(), tier.name());
            }
        } catch (RuntimeException e) {
            rt.log().warn("harbor:donor 갱신 실패 ({}): {}", player, String.valueOf(e.getMessage()));
        }
    }

    /** DB 기준으로 harbor:donor 전체를 맞춘다 (가상 스레드, 한 서버만). 유예가 지난 구독은 이때 ENDED로 닫는다. */
    public void reconcile() {
        reconcile(false);
    }

    /** quiet면 바뀐 것(제거·종료)이 있을 때만 로그를 남긴다 (10분 주기 점검용). */
    private void reconcile(boolean quiet) {
        Optional<DistributedLock.Handle> lock;
        try {
            lock = rt.api().locks().tryAcquire(RECONCILE_LOCK, LOCK_TTL);
        } catch (RuntimeException e) {
            rt.log().warn("후원 대조 잠금 실패: {}", String.valueOf(e.getMessage()));
            return;
        }
        if (lock.isEmpty()) {
            return;
        }
        try (DistributedLock.Handle _ = lock.get()) {
            Instant now = Instant.now();
            Duration grace = rt.grace();
            List<ShopRepository.Subscription> open =
                    rt.api().database().tx(ShopRepository::openSubscriptions);
            Map<String, String> expected = new HashMap<>();
            int ended = 0;
            for (ShopRepository.Subscription s : open) {
                if (DonorGrace.shouldEnd(s.state(), s.periodEnd(), now, grace)) {
                    endAndRevoke(s.player(), s.tier());
                    ended++;
                    continue;
                }
                DonorTier tier =
                        DonorGrace.effective(s.state(), s.tier(), s.periodEnd(), now, grace);
                if (tier != DonorTier.NONE) {
                    expected.put(s.player().toString(), tier.name());
                }
            }
            Map<String, String> current = rt.api().redis().sync().hgetall(DonorTier.REDIS_KEY);
            int removed = 0;
            for (String field : current.keySet()) {
                if (!expected.containsKey(field)) {
                    rt.api().redis().sync().hdel(DonorTier.REDIS_KEY, field);
                    removed++;
                }
            }
            if (!expected.isEmpty()) {
                rt.api().redis().sync().hset(DonorTier.REDIS_KEY, expected);
            }
            if (!quiet || removed > 0 || ended > 0) {
                rt.log().info("harbor:donor 대조: 유지 {}명, 제거 {}명", expected.size(), removed);
            }
        } catch (Exception e) {
            rt.log().error("harbor:donor 대조 실패", e);
        }
    }

    /** 10분마다 유예가 지난 구독을 닫는다 (가상 스레드, 대조와 같은 잠금). */
    void sweep() {
        reconcile(true);
    }

    private void endAndRevoke(UUID player, DonorTier tier) throws java.sql.SQLException {
        boolean ended = rt.api().database().tx(c -> ShopRepository.endSubscription(c, player));
        syncRedis(player, DonorTier.NONE);
        if (ended) {
            rt.log().info("후원 종료 (유예 만료): {} {}", player, tier);
            ShopSettings.DonorPerk perk = rt.settings().perk(tier);
            String title = perk.title();
            if (title != null) {
                // TitleService는 메인 스레드에서 부른다 (접속 중인 플레이어 캐시)
                rt.tasks()
                        .main()
                        .execute(
                                () -> {
                                    var _ = titles().revoke(player, TitleId.of(title));
                                });
            }
            rt.notifyPlayer(player, "DONOR", 0);
        }
    }

    /** 구독 행을 다시 읽어 캐시·Redis·칭호·이름 색을 맞춘다 (메인 스레드에서 부른다). */
    public void refresh(UUID player) {
        var _ =
                rt.db(c -> ShopRepository.subscription(c, player))
                        .thenAccept(
                                sub -> {
                                    DonorTier before = tier(player);
                                    DonorTier after =
                                            sub.map(
                                                            s ->
                                                                    DonorGrace.effective(
                                                                            s.state(),
                                                                            s.tier(),
                                                                            s.periodEnd(),
                                                                            Instant.now(),
                                                                            rt.grace()))
                                                    .orElse(DonorTier.NONE);
                                    rt.update(
                                            player,
                                            p ->
                                                    p.withDonor(
                                                            after,
                                                            sub.map(
                                                                            ShopRepository
                                                                                            .Subscription
                                                                                    ::state)
                                                                    .orElse(DonorGrace.State.ENDED),
                                                            sub.map(
                                                                            ShopRepository
                                                                                            .Subscription
                                                                                    ::periodEnd)
                                                                    .orElse(null)));
                                    var _ = rt.tasks().async(() -> syncRedis(player, after));
                                    if (before != after) {
                                        rt.call(new DonorTierChangedEvent(player, before, after));
                                    }
                                    Player online = Bukkit.getPlayer(player);
                                    if (online != null) {
                                        apply(online);
                                    }
                                });
    }

    /** 접속 중인 플레이어에게 상태형 혜택을 적용한다: 후원자 칭호 지급·회수, 이름 색 (메인 스레드). */
    public void apply(Player player) {
        DonorTier tier = tier(player.getUniqueId());
        ShopSettings settings = rt.settings();
        TitleService titles = titles();
        for (DonorTier t : List.of(DonorTier.HARBOR, DonorTier.FLEET)) {
            String title = settings.perk(t).title();
            if (title == null) {
                continue;
            }
            TitleId id = TitleId.of(title);
            boolean should = t == tier;
            boolean has = titles.has(player.getUniqueId(), id);
            if (should && !has) {
                var _ =
                        titles.grant(
                                player.getUniqueId(),
                                id,
                                "donor:" + t.name().toLowerCase(java.util.Locale.ROOT));
            } else if (!should && has) {
                var _ = titles.revoke(player.getUniqueId(), id);
            }
        }
        applyName(player);
    }

    /**
     * 표시 이름 = (닉네임 변경권 표시 이름 또는 계정 이름) + 색. 색은 장착한 이름 색 코스메틱 → 후원 등급 색 순서. 탭 목록에도 같은 이름을 쓴다. 서버 간
     * 채팅 형식(Proxy)은 이 값을 아직 읽지 않는다 (Core·Proxy 변경 요청).
     */
    public void applyName(Player player) {
        ShopProfile profile = rt.profile(player.getUniqueId());
        String name = profile.displayName() != null ? profile.displayName() : player.getName();
        String color = "";
        HarborId equipped = profile.equipped().get(CosmeticDef.Category.NAME_COLOR);
        if (equipped != null) {
            color =
                    rt.cosmetic(equipped)
                            .map(CosmeticDef::color)
                            .filter(c -> c != null)
                            .map(String::valueOf)
                            .orElse("");
        }
        if (color.isEmpty() && profile.tier() != DonorTier.NONE) {
            color = rt.settings().perk(profile.tier()).nameColor();
        }
        Component styled =
                MiniMessage.miniMessage()
                        .deserialize(color + "<name>", Placeholder.unparsed("name", name));
        player.displayName(styled);
        player.playerListName(styled);
    }

    private static TitleService titles() {
        return SeasonServices.titles();
    }
}
