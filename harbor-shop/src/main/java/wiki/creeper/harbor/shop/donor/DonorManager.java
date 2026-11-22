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
import wiki.creeper.harbor.shop.data.SubscriptionRepository;

/**
 * Donor tiers (DonorService implementation, docs 20 12.4 'donor tiers'). The source of truth is
 * subscriptions; on every state change the Redis hash harbor:donor (field = UUID, value = tier
 * name) is synced. The 3-day grace is applied when the value is removed. On server start the whole
 * hash is re-synced from the DB (in case of Redis restart or missed updates).
 *
 * <p>Status perks (name color, donor title, chat icon, queue priority, +2 aquarium theme slots) are
 * computed from subscription state and disappear when it ends. Owned perks (monthly limited fleet
 * cosmetic, monthly pearls) are grant records and remain after cancellation.
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

    /**
     * Syncs one hash field (virtual thread). On Redis failure only logs (the next startup
     * reconciliation fixes it).
     */
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

    /**
     * Syncs all of harbor:donor from the DB (virtual thread, one server only). Subscriptions past
     * grace are closed as ENDED here.
     */
    public void reconcile() {
        reconcile(false);
    }

    /** If quiet, logs only when something changed (removed or ended) (for the 10-minute check). */
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
            List<SubscriptionRepository.Subscription> open =
                    rt.api().database().tx(SubscriptionRepository::openSubscriptions);
            Map<String, String> expected = new HashMap<>();
            int ended = 0;
            for (SubscriptionRepository.Subscription s : open) {
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

    /**
     * Every 10 minutes closes subscriptions past grace (virtual thread, same lock as
     * reconciliation).
     */
    void sweep() {
        reconcile(true);
    }

    private void endAndRevoke(UUID player, DonorTier tier) throws java.sql.SQLException {
        boolean ended =
                rt.api().database().tx(c -> SubscriptionRepository.endSubscription(c, player));
        syncRedis(player, DonorTier.NONE);
        if (ended) {
            rt.log().info("후원 종료 (유예 만료): {} {}", player, tier);
            ShopSettings.DonorPerk perk = rt.settings().perk(tier);
            String title = perk.title();
            if (title != null) {
                // TitleService is called on the main thread (online player cache)
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

    /**
     * Re-reads the subscription row and syncs cache, Redis, title and name color (called on the
     * main thread).
     */
    public void refresh(UUID player) {
        var _ =
                rt.db(c -> SubscriptionRepository.subscription(c, player))
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
                                                                            SubscriptionRepository
                                                                                            .Subscription
                                                                                    ::state)
                                                                    .orElse(DonorGrace.State.ENDED),
                                                            sub.map(
                                                                            SubscriptionRepository
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

    /**
     * Applies status perks to an online player: grant or revoke donor title, name color (main
     * thread).
     */
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
     * Display name = (nickname change display name or account name) + color. Color is the equipped
     * name color cosmetic → donor tier color, in that order. The tab list uses the same name.
     * Cross-server chat format (Proxy) does not read this value yet (Core/Proxy change request).
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
