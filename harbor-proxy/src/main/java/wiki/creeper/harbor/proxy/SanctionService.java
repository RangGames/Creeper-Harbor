package wiki.creeper.harbor.proxy;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import wiki.creeper.harbor.common.db.Database;

/**
 * 제재 (docs 20 1.4·1.5). BAN은 로그인 때 DB에서 확인해 거부하고, MUTE는 로그인 때 읽어 메모리에 두고 채팅·귓속말마다 확인한다. 프록시에서 막으므로
 * 서버를 옮겨도 그대로 유지된다. 프록시는 1대라 캐시를 공유할 필요가 없다.
 */
final class SanctionService {

    /** 로그인 검사 결과. */
    record LoginCheck(@Nullable Sanction ban, @Nullable Sanction mute) {}

    private final ProxyServer proxy;
    private final Database db;
    private final ProxyText text;
    private final JoinLog joinLog;
    private final Logger log;
    private final Map<UUID, Sanction> mutes = new ConcurrentHashMap<>();

    SanctionService(ProxyServer proxy, Database db, ProxyText text, JoinLog joinLog, Logger log) {
        this.proxy = proxy;
        this.db = db;
        this.text = text;
        this.joinLog = joinLog;
        this.log = log;
    }

    /** LoginEvent (가상 스레드): 유효 제재를 읽고 MUTE는 캐시에 둔다. DB 오류는 그대로 실패로 돌려 접속을 거부하게 한다. */
    CompletableFuture<LoginCheck> loadAtLogin(UUID player) {
        Instant now = Instant.now();
        return db.async(c -> SanctionStore.active(c, player, now))
                .thenApply(
                        list -> {
                            Sanction ban = first(list, Sanction.Type.BAN);
                            Sanction mute = first(list, Sanction.Type.MUTE);
                            if (mute != null) {
                                mutes.put(player, mute);
                            } else {
                                mutes.remove(player);
                            }
                            return new LoginCheck(ban, mute);
                        });
    }

    /** 유효한 MUTE. 만료됐으면 캐시에서 지운다. */
    Optional<Sanction> activeMute(UUID player) {
        Sanction mute = mutes.get(player);
        if (mute == null) {
            return Optional.empty();
        }
        if (!mute.activeAt(Instant.now())) {
            mutes.remove(player, mute);
            return Optional.empty();
        }
        return Optional.of(mute);
    }

    /** 채팅이 막혔으면 안내하고 true. */
    boolean blockIfMuted(UUID player) {
        Optional<Sanction> mute = activeMute(player);
        if (mute.isEmpty()) {
            return false;
        }
        proxy.getPlayer(player).ifPresent(p -> p.sendMessage(muteMessage(mute.get())));
        return true;
    }

    /** 제재를 기록하고 바로 적용한다 (BAN이면 접속 중인 대상을 끊고, MUTE면 캐시에 넣는다). */
    CompletableFuture<Sanction> issue(
            UUID target, Sanction.Type type, SanctionTerm term, String reason, String issuedBy) {
        Instant now = Instant.now();
        Instant expires = term.expiresAt(now);
        return db.async(c -> SanctionStore.issue(c, target, type, reason, issuedBy, now, expires))
                .thenApply(
                        sanction -> {
                            apply(sanction);
                            log.info(
                                    "제재 {} {} ({}) by {}: {}",
                                    type,
                                    target,
                                    term.describe("perm"),
                                    issuedBy,
                                    reason);
                            return sanction;
                        });
    }

    /** 해제. 해제한 건수. */
    CompletableFuture<Integer> revoke(UUID target, Sanction.Type type, String by) {
        Instant now = Instant.now();
        return db.async(c -> SanctionStore.revoke(c, target, type, by, now))
                .thenApply(
                        count -> {
                            if (type == Sanction.Type.MUTE && mutes.remove(target) != null) {
                                proxy.getPlayer(target)
                                        .ifPresent(
                                                p -> p.sendMessage(text.get("sanction.unmuted")));
                            }
                            return count;
                        });
    }

    CompletableFuture<List<Sanction>> active(UUID target) {
        Instant now = Instant.now();
        return db.async(c -> SanctionStore.active(c, target, now));
    }

    void forget(UUID player) {
        mutes.remove(player);
    }

    Component banMessage(Sanction ban) {
        return text.get(
                "sanction.ban_kick",
                Placeholder.unparsed("reason", ban.reason()),
                Placeholder.unparsed("until", text.time(ban.expiresAt())));
    }

    Component muteMessage(Sanction mute) {
        return text.get(
                "sanction.muted",
                Placeholder.unparsed("reason", mute.reason()),
                Placeholder.unparsed("until", text.time(mute.expiresAt())));
    }

    private void apply(Sanction sanction) {
        Optional<Player> online = proxy.getPlayer(sanction.player());
        switch (sanction.type()) {
            case BAN ->
                    online.ifPresent(
                            p -> {
                                joinLog.markKick(p.getUniqueId(), "ban: " + sanction.reason());
                                p.disconnect(banMessage(sanction));
                            });
            case MUTE -> {
                mutes.put(sanction.player(), sanction);
                online.ifPresent(p -> p.sendMessage(muteMessage(sanction)));
            }
        }
    }

    private static @Nullable Sanction first(List<Sanction> list, Sanction.Type type) {
        Instant now = Instant.now();
        for (Sanction sanction : list) {
            if (sanction.type() == type && sanction.activeAt(now)) {
                return sanction;
            }
        }
        return null;
    }
}
