package wiki.creeper.harbor.shop.festival;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.common.redis.Channel;
import wiki.creeper.harbor.common.redis.Channels;
import wiki.creeper.harbor.season.api.SeasonServices;
import wiki.creeper.harbor.season.api.TitleId;
import wiki.creeper.harbor.shop.ShopRuntime;
import wiki.creeper.harbor.shop.api.event.FestivalProgressEvent;
import wiki.creeper.harbor.shop.content.FestivalDef;
import wiki.creeper.harbor.shop.cosmetic.CosmeticManager;

/**
 * Harbor festival donation gauge (docs 20 12.4, '6. BM plan' 3.5). Adds Tebex festival package
 * payments to the gauge and broadcasts via harbor:festival to all servers. When the goal is
 * reached, every user gets the same festival cosmetic (immediately if online, otherwise on next
 * join). Donors get a contribution title (reference ID festival:&lt;id&gt;:&lt;uuid&gt;). No
 * performance buffs are included.
 */
public final class FestivalManager {

    /** Gauge update (harbor:festival, proposed). */
    public record FestivalUpdate(
            String festival, long totalKrw, long goalKrw, boolean reachedNow) {}

    public static final Channel<FestivalUpdate> CHANNEL =
            new Channel<>("harbor:festival", FestivalUpdate.class);

    /** Gauge cache. */
    public record Gauge(long totalKrw, boolean reached) {}

    /** Contribution result within the transaction. */
    public record Contribution(HarborId festival, UUID player, long totalKrw, boolean reachedNow) {}

    private final ShopRuntime rt;
    private final CosmeticManager cosmetics;
    private final Map<HarborId, Gauge> gauges = new ConcurrentHashMap<>();

    public FestivalManager(ShopRuntime rt, CosmeticManager cosmetics) {
        this.rt = rt;
        this.cosmetics = cosmetics;
    }

    public void start() {
        var _ =
                rt.db(
                                c -> {
                                    Map<HarborId, Gauge> map = new HashMap<>();
                                    try (PreparedStatement ps =
                                                    c.prepareStatement(
                                                            "SELECT festival_id, total_krw, reached_at IS NOT NULL FROM festival_state");
                                            ResultSet rs = ps.executeQuery()) {
                                        while (rs.next()) {
                                            Optional<HarborId> id =
                                                    HarborId.tryParse(rs.getString(1));
                                            if (id.isPresent()) {
                                                map.put(
                                                        id.get(),
                                                        new Gauge(rs.getLong(2), rs.getBoolean(3)));
                                            }
                                        }
                                    }
                                    return map;
                                })
                        .thenAccept(gauges::putAll);
        rt.api()
                .bus()
                .subscribe(
                        CHANNEL, env -> rt.tasks().main().execute(() -> onUpdate(env.payload())));
    }

    public Optional<Gauge> gauge(HarborId festival) {
        return Optional.ofNullable(gauges.get(festival));
    }

    /** Festival of the current season. */
    public List<FestivalDef> current() {
        int season = rt.season();
        return rt.snapshot().all(FestivalDef.TYPE).values().stream()
                .filter(f -> f.season() == 0 || f.season() == season)
                .toList();
    }

    /** Records a contribution inside the payment transaction. The same transaction ID only once. */
    public static @Nullable Contribution contribute(
            Connection c, FestivalDef festival, UUID player, String txId, long amountKrw)
            throws SQLException {
        try (PreparedStatement ps =
                c.prepareStatement(
                        "INSERT IGNORE INTO festival_contribution (tx_id, uuid, festival_id, amount_krw) VALUES (?, ?, ?, ?)")) {
            ps.setString(1, txId);
            ps.setString(2, player.toString());
            ps.setString(3, festival.id().toString());
            ps.setLong(4, amountKrw);
            if (ps.executeUpdate() == 0) {
                return null;
            }
        }
        try (PreparedStatement ps =
                c.prepareStatement(
                        "INSERT INTO festival_state (festival_id, total_krw) VALUES (?, 0)"
                                + " ON DUPLICATE KEY UPDATE festival_id = festival_id")) {
            ps.setString(1, festival.id().toString());
            ps.executeUpdate();
        }
        long total;
        boolean reachedBefore;
        try (PreparedStatement ps =
                c.prepareStatement(
                        "SELECT total_krw, reached_at IS NOT NULL FROM festival_state WHERE festival_id = ? FOR UPDATE")) {
            ps.setString(1, festival.id().toString());
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                total = rs.getLong(1) + amountKrw;
                reachedBefore = rs.getBoolean(2);
            }
        }
        boolean reachedNow = !reachedBefore && total >= festival.goalKrw();
        try (PreparedStatement ps =
                c.prepareStatement(
                        "UPDATE festival_state SET total_krw = ?, reached_at = IF(? AND reached_at IS NULL, NOW(3), reached_at)"
                                + " WHERE festival_id = ?")) {
            ps.setLong(1, total);
            ps.setBoolean(2, reachedNow);
            ps.setString(3, festival.id().toString());
            ps.executeUpdate();
        }
        return new Contribution(festival.id(), player, total, reachedNow);
    }

    /**
     * Subtracts a refunded festival payment from the gauge (already reached festivals stay as is).
     */
    public static void withdraw(Connection c, String txId) throws SQLException {
        String festival = null;
        long amount = 0;
        try (PreparedStatement ps =
                c.prepareStatement(
                        "SELECT festival_id, amount_krw FROM festival_contribution WHERE tx_id = ?")) {
            ps.setString(1, txId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    festival = rs.getString(1);
                    amount = rs.getLong(2);
                }
            }
        }
        if (festival == null) {
            return;
        }
        try (PreparedStatement ps =
                c.prepareStatement("DELETE FROM festival_contribution WHERE tx_id = ?")) {
            ps.setString(1, txId);
            ps.executeUpdate();
        }
        try (PreparedStatement ps =
                c.prepareStatement(
                        "UPDATE festival_state SET total_krw = GREATEST(0, total_krw - ?) WHERE festival_id = ?")) {
            ps.setLong(1, amount);
            ps.setString(2, festival);
            ps.executeUpdate();
        }
    }

    /**
     * After commit (main thread): cross-server notice, contribution title, completion announcement.
     */
    public void afterContribution(Contribution contribution) {
        Optional<FestivalDef> def = rt.snapshot().get(FestivalDef.TYPE, contribution.festival());
        if (def.isEmpty()) {
            return;
        }
        rt.api()
                .bus()
                .publish(
                        CHANNEL,
                        new FestivalUpdate(
                                contribution.festival().toString(),
                                contribution.totalKrw(),
                                def.get().goalKrw(),
                                contribution.reachedNow()));
        String title = def.get().contributorTitle();
        if (title != null) {
            var _ =
                    SeasonServices.titles()
                            .grant(
                                    contribution.player(),
                                    TitleId.of(title),
                                    "festival:"
                                            + def.get().id().path()
                                            + ":"
                                            + contribution.player());
        }
        if (contribution.reachedNow()) {
            String text =
                    MiniMessage.miniMessage()
                            .serialize(
                                    rt.api()
                                            .messages()
                                            .get(
                                                    "shop.festival.reached",
                                                    Placeholder.unparsed(
                                                            "name", def.get().name())));
            rt.api().bus().publish(Channels.ANNOUNCEMENTS, new Channels.Announcement(text));
        }
    }

    private void onUpdate(FestivalUpdate update) {
        Optional<HarborId> id = HarborId.tryParse(update.festival());
        if (id.isEmpty()) {
            return;
        }
        boolean reached = update.totalKrw() >= update.goalKrw();
        Gauge before = gauges.get(id.get());
        boolean reachedFlag = reached || (before != null && before.reached());
        gauges.put(id.get(), new Gauge(update.totalKrw(), reachedFlag));
        rt.call(
                new FestivalProgressEvent(
                        id.get(), update.totalKrw(), update.goalKrw(), update.reachedNow()));
        if (reachedFlag) {
            Bukkit.getOnlinePlayers().forEach(this::grantRewards);
        }
    }

    /**
     * Grants rewards of reached festivals (on join and on completion notice). Does nothing if
     * already owned.
     */
    public void grantRewards(Player player) {
        for (FestivalDef def : current()) {
            Gauge gauge = gauges.get(def.id());
            if (gauge == null || !gauge.reached()) {
                continue;
            }
            for (HarborId reward : def.rewards()) {
                if (!cosmetics.owns(player.getUniqueId(), reward)) {
                    var _ =
                            cosmetics.grant(
                                    player.getUniqueId(),
                                    reward,
                                    "FESTIVAL",
                                    "festival:" + def.id().path());
                }
            }
        }
    }

    /** Festival plaque: top n contributors (name, total). */
    public CompletableFuture<List<String[]>> topContributors(HarborId festival, int limit) {
        return rt.db(
                c -> {
                    List<String[]> rows = new ArrayList<>();
                    try (PreparedStatement ps =
                            c.prepareStatement(
                                    "SELECT COALESCE(p.display_name, p.name, fc.uuid), SUM(fc.amount_krw) s"
                                            + " FROM festival_contribution fc LEFT JOIN players p ON p.uuid = fc.uuid"
                                            + " WHERE fc.festival_id = ? GROUP BY fc.uuid ORDER BY s DESC LIMIT ?")) {
                        ps.setString(1, festival.toString());
                        ps.setInt(2, limit);
                        try (ResultSet rs = ps.executeQuery()) {
                            while (rs.next()) {
                                rows.add(
                                        new String[] {
                                            rs.getString(1), String.valueOf(rs.getLong(2))
                                        });
                            }
                        }
                    }
                    return rows;
                });
    }
}
