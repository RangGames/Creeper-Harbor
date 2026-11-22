package wiki.creeper.harbor.season.title;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import wiki.creeper.harbor.core.api.HarborApi;
import wiki.creeper.harbor.core.api.HarborPlayer;
import wiki.creeper.harbor.core.api.PlayerComponent;
import wiki.creeper.harbor.core.task.Tasks;
import wiki.creeper.harbor.season.SeasonContentView;
import wiki.creeper.harbor.season.api.OwnedTitle;
import wiki.creeper.harbor.season.api.SeasonClock;
import wiki.creeper.harbor.season.api.TitleId;
import wiki.creeper.harbor.season.api.TitleService;
import wiki.creeper.harbor.season.api.event.TitleEquippedEvent;
import wiki.creeper.harbor.season.api.event.TitleGrantedEvent;
import wiki.creeper.harbor.season.content.TitleDef;
import wiki.creeper.harbor.season.data.SeasonProfile;
import wiki.creeper.harbor.season.data.TitleStore;
import wiki.creeper.harbor.season.net.SeasonMessages;

/**
 * TitleService implementation (docs 20 11.4 titles). Writes go to the DB immediately, and for
 * online players the PlayerComponent cache is updated too. Players online on another server update
 * their cache via harbor:season TitleChanged.
 */
public final class TitleManager implements TitleService {

    private final HarborApi api;
    private final SeasonContentView content;
    private final SeasonClock clock;
    private final PlayerComponent<SeasonProfile> component;
    private final Tasks tasks;
    private final Logger log;

    public TitleManager(
            HarborApi api,
            SeasonContentView content,
            SeasonClock clock,
            PlayerComponent<SeasonProfile> component,
            Tasks tasks,
            Logger log) {
        this.api = api;
        this.content = content;
        this.clock = clock;
        this.component = component;
        this.tasks = tasks;
        this.log = log;
    }

    public void start() {
        api.bus()
                .subscribe(
                        SeasonMessages.TITLE,
                        envelope -> {
                            if (envelope.server().equals(api.infra().serverId())) {
                                return;
                            }
                            SeasonMessages.TitleChanged change = envelope.payload();
                            Optional<TitleId> title = TitleId.tryOf(change.title());
                            if (title.isEmpty()) {
                                return;
                            }
                            tasks.main()
                                    .execute(
                                            () ->
                                                    applyCache(
                                                            change.player(),
                                                            title.get(),
                                                            change.granted()));
                        });
    }

    // ------------------------------------------------------------------ TitleService

    @Override
    public CompletableFuture<Grant> grant(UUID player, TitleId title, @Nullable String refId) {
        if (definition(title).isEmpty()) {
            log.warn("titles.yml에 없는 칭호라 지급하지 않습니다: {} → {}", title, player);
            return CompletableFuture.completedFuture(Grant.UNKNOWN_TITLE);
        }
        int season = clock.current().value();
        @Nullable String ref = TitleStore.clip(refId);
        return api.database()
                .async(c -> TitleStore.insert(c, player, title, season, ref))
                .handleAsync(
                        (inserted, error) -> {
                            if (error != null) {
                                log.error("칭호 지급 실패: {} → {}", title, player, error);
                                return Grant.FAILED;
                            }
                            if (!inserted) {
                                applyCache(player, title, true);
                                return Grant.ALREADY_OWNED;
                            }
                            afterGrant(player, title, ref);
                            return Grant.GRANTED;
                        },
                        tasks.main());
    }

    /** Main thread after commit: cache, event, notification, propagation to other servers. */
    public void afterGrant(UUID player, TitleId title, @Nullable String ref) {
        applyCache(player, title, true);
        Bukkit.getPluginManager().callEvent(new TitleGrantedEvent(player, title, ref));
        Player online = Bukkit.getPlayer(player);
        if (online != null) {
            api.messages()
                    .send(
                            online,
                            "season.title.granted",
                            Placeholder.component("title", display(title)));
        }
        api.bus()
                .publish(
                        SeasonMessages.TITLE,
                        new SeasonMessages.TitleChanged(player, title.toString(), true));
    }

    @Override
    public CompletableFuture<Boolean> revoke(UUID player, TitleId title) {
        return api.database()
                .async(
                        c -> {
                            try (PreparedStatement ps =
                                    c.prepareStatement(
                                            "DELETE FROM titles WHERE uuid = ? AND title_id = ?")) {
                                ps.setString(1, player.toString());
                                ps.setString(2, title.toString());
                                return ps.executeUpdate() > 0;
                            }
                        })
                .thenApplyAsync(
                        removed -> {
                            applyCache(player, title, false);
                            if (removed) {
                                api.bus()
                                        .publish(
                                                SeasonMessages.TITLE,
                                                new SeasonMessages.TitleChanged(
                                                        player, title.toString(), false));
                            }
                            return removed;
                        },
                        tasks.main());
    }

    @Override
    public CompletableFuture<List<OwnedTitle>> list(UUID player) {
        return api.database()
                .async(
                        c -> {
                            List<OwnedTitle> result = new ArrayList<>();
                            try (PreparedStatement ps =
                                    c.prepareStatement(
                                            "SELECT title_id, season, obtained_at FROM titles WHERE uuid = ? ORDER BY obtained_at, title_id")) {
                                ps.setString(1, player.toString());
                                try (ResultSet rs = ps.executeQuery()) {
                                    while (rs.next()) {
                                        int season = rs.getInt(2);
                                        var at = rs.getTimestamp(3).toInstant();
                                        TitleId.tryOf(rs.getString(1))
                                                .ifPresent(
                                                        t ->
                                                                result.add(
                                                                        new OwnedTitle(
                                                                                t, season, at)));
                                    }
                                }
                            }
                            return List.copyOf(result);
                        })
                .thenApplyAsync(r -> r, tasks.main());
    }

    @Override
    public boolean has(UUID player, TitleId title) {
        return api.players()
                .get(player)
                .map(hp -> hp.get(component).titles().contains(title))
                .orElse(false);
    }

    @Override
    public CompletableFuture<Boolean> hasAsync(UUID player, TitleId title) {
        return api.database()
                .async(
                        c -> {
                            try (PreparedStatement ps =
                                    c.prepareStatement(
                                            "SELECT 1 FROM titles WHERE uuid = ? AND title_id = ?")) {
                                ps.setString(1, player.toString());
                                ps.setString(2, title.toString());
                                try (ResultSet rs = ps.executeQuery()) {
                                    return rs.next();
                                }
                            }
                        })
                .thenApplyAsync(r -> r, tasks.main());
    }

    @Override
    public Optional<TitleId> equipped(UUID player) {
        return api.players()
                .get(player)
                .flatMap(hp -> Optional.ofNullable(hp.get(component).equipped()));
    }

    @Override
    public boolean equip(UUID player, @Nullable TitleId title) {
        Optional<HarborPlayer> hp = api.players().get(player);
        if (hp.isEmpty()) {
            return false;
        }
        SeasonProfile profile = hp.get().get(component);
        if (title != null && !profile.titles().contains(title)) {
            return false;
        }
        hp.get().set(component, profile.withEquipped(title));
        Bukkit.getPluginManager().callEvent(new TitleEquippedEvent(player, title));
        return true;
    }

    @Override
    public Optional<TitleDef> definition(TitleId title) {
        return Optional.ofNullable(content.titles().get(title.id()));
    }

    @Override
    public Component display(TitleId title) {
        return definition(title)
                .map(d -> MiniMessage.miniMessage().deserialize(d.name()))
                .orElse(Component.text(title.shortName()));
    }

    // ------------------------------------------------------------------ internal

    private void applyCache(UUID player, TitleId title, boolean granted) {
        api.players()
                .get(player)
                .ifPresent(
                        hp -> {
                            SeasonProfile profile = hp.get(component);
                            boolean has = profile.titles().contains(title);
                            if (granted && !has) {
                                hp.set(component, profile.withTitle(title));
                            } else if (!granted && has) {
                                boolean wasEquipped = title.equals(profile.equipped());
                                hp.set(component, profile.withoutTitle(title));
                                if (wasEquipped) {
                                    Bukkit.getPluginManager()
                                            .callEvent(new TitleEquippedEvent(player, null));
                                }
                            }
                        });
    }
}
