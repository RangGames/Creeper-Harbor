package wiki.creeper.harbor.skript.bridge;

import static wiki.creeper.harbor.skript.bridge.Shared.api;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.bukkit.OfflinePlayer;
import wiki.creeper.harbor.common.ledger.LedgerReason;
import wiki.creeper.harbor.common.model.Currency;
import wiki.creeper.harbor.core.api.BalanceRank;
import wiki.creeper.harbor.core.api.CurrencyService;
import wiki.creeper.harbor.core.api.LedgerRecord;
import wiki.creeper.harbor.core.api.MailService;
import wiki.creeper.harbor.core.api.event.CurrencyChangedEvent;
import wiki.creeper.harbor.skript.lambda.Args;
import wiki.creeper.harbor.skript.lambda.Ext;
import wiki.creeper.harbor.skript.lambda.Registrar;

/**
 * Core currency and mail syntax (always registered): grants and deductions for offline players,
 * transfers, admin set, balance / rank / top / history lookups, unclaimed mail count and extra
 * currency event values.
 *
 * <p>Every change is a ledger transaction through CurrencyService (script changes use the reasons
 * SCRIPT_GRANT · SCRIPT_TAKE, admin set uses ADMIN_GRANT · ADMIN_TAKE). Results arrive
 * asynchronously and are stored in variables by awaiting effects.
 */
public final class CoreEconomySyntax {

    private CoreEconomySyntax() {}

    private static final String CUR = "(0:gold|1:mulbineul)";

    private static CurrencyService currency() {
        return api().currency();
    }

    public static void register(Registrar r) {
        changes(r);
        lookups(r);
        mail(r);
        eventValues(r);
    }

    private static void changes(Registrar r) {
        // pattern 0 grant (amount 0, players 1), pattern 1 take (amount 0, players 1)
        Ext.await(
                r,
                "harbor give or take currency",
                new int[] {2},
                c -> {
                    long amount = c.number(0).map(Number::longValue).orElse(0L);
                    if (amount <= 0) {
                        return null;
                    }
                    Currency cur = Shared.currency(c.mark());
                    boolean give = c.pattern() == 0;
                    List<CompletableFuture<Boolean>> results = new ArrayList<>();
                    for (OfflinePlayer p : c.offline(1)) {
                        UUID uuid = p.getUniqueId();
                        var f =
                                give
                                        ? currency()
                                                .give(
                                                        uuid,
                                                        cur,
                                                        amount,
                                                        LedgerReason.SCRIPT_GRANT,
                                                        Shared.ref())
                                        : currency()
                                                .take(
                                                        uuid,
                                                        cur,
                                                        amount,
                                                        LedgerReason.SCRIPT_TAKE,
                                                        Shared.ref());
                        results.add(f.thenApply(res -> res.isOk()));
                    }
                    return Ext.one(Shared.all(results));
                },
                "harbor (give|grant) %number% "
                        + CUR
                        + " to %offlineplayers% [and (store|save)"
                        + " [the] result[s] in %-objects%]",
                "harbor take %number% "
                        + CUR
                        + " from %offlineplayers% [and (store|save) [the]"
                        + " result[s] in %-objects%]");

        Ext.await(
                r,
                "harbor transfer currency",
                new int[] {3},
                c -> {
                    long amount = c.number(0).map(Number::longValue).orElse(0L);
                    Optional<OfflinePlayer> from = c.offlinePlayer(1);
                    Optional<OfflinePlayer> to = c.offlinePlayer(2);
                    if (amount <= 0
                            || from.isEmpty()
                            || to.isEmpty()
                            || from.get().getUniqueId().equals(to.get().getUniqueId())) {
                        return null;
                    }
                    return Ext.value(
                            currency()
                                    .transfer(
                                            from.get().getUniqueId(),
                                            to.get().getUniqueId(),
                                            Shared.currency(c.mark()),
                                            amount,
                                            LedgerReason.SCRIPT_TAKE,
                                            LedgerReason.SCRIPT_GRANT,
                                            Shared.ref())
                                    .thenApply(res -> res.isOk()));
                },
                "harbor transfer %number% "
                        + CUR
                        + " from %offlineplayer% to %offlineplayer%"
                        + " [and (store|save) [the] result in %-objects%]");

        Ext.await(
                r,
                "harbor admin set currency",
                new int[] {2},
                c -> {
                    Optional<OfflinePlayer> p = c.offlinePlayer(0);
                    long amount = c.number(1).map(Number::longValue).orElse(-1L);
                    if (p.isEmpty() || amount < 0) {
                        return null;
                    }
                    return Ext.value(
                            currency()
                                    .set(
                                            p.get().getUniqueId(),
                                            Shared.currency(c.mark()),
                                            amount,
                                            Shared.ref())
                                    .thenApply(res -> res.isOk() ? (Object) res.orThrow() : null));
                },
                "harbor admin set "
                        + CUR
                        + " of %offlineplayer% to %number% [and (store|save)"
                        + " [the] result in %-objects%]");
    }

    private static void lookups(Registrar r) {
        Ext.await(
                r,
                "harbor load balance",
                new int[] {1},
                c ->
                        c.offlinePlayer(0)
                                .map(
                                        p ->
                                                Ext.value(
                                                        currency()
                                                                .balance(
                                                                        p.getUniqueId(),
                                                                        Shared.currency(c.mark()))))
                                .orElse(null),
                "harbor load " + CUR + " [balance] of %offlineplayer% into %objects%");
        Ext.await(
                r,
                "harbor load rank",
                new int[] {1},
                c ->
                        c.offlinePlayer(0)
                                .map(
                                        p ->
                                                Ext.value(
                                                        currency()
                                                                .rank(
                                                                        p.getUniqueId(),
                                                                        Shared.currency(c.mark()))
                                                                .thenApply(o -> o.orElse(null))))
                                .orElse(null),
                "harbor load " + CUR + " rank of %offlineplayer% into %objects%");
        Ext.await(
                r,
                "harbor load top",
                new int[] {1, 2},
                c -> {
                    int limit = c.number(0).map(Number::intValue).orElse(0);
                    if (limit < 1) {
                        return null;
                    }
                    return currency()
                            .top(Shared.currency(c.mark()), 0, Math.min(limit, 100))
                            .thenApply(
                                    rows ->
                                            new List<?>[] {
                                                rows.stream()
                                                        .map(
                                                                (BalanceRank row) ->
                                                                        Shared.offline(
                                                                                row.player()
                                                                                        .uuid()))
                                                        .toList(),
                                                rows.stream()
                                                        .map(row -> (Object) row.balance())
                                                        .toList()
                                            });
                },
                "harbor load top %number% "
                        + CUR
                        + " [players] into %objects% [with balances in"
                        + " %-objects%]");
        Ext.await(
                r,
                "harbor load history",
                new int[] {2, 3, 4},
                c -> {
                    int limit = c.number(0).map(Number::intValue).orElse(0);
                    Optional<OfflinePlayer> p = c.offlinePlayer(1);
                    if (limit < 1 || p.isEmpty()) {
                        return null;
                    }
                    return currency()
                            .history(
                                    p.get().getUniqueId(),
                                    Shared.currency(c.mark()),
                                    Math.min(limit, 200))
                            .thenApply(
                                    rows ->
                                            new List<?>[] {
                                                rows.stream()
                                                        .map((LedgerRecord x) -> (Object) x.delta())
                                                        .toList(),
                                                rows.stream().map(LedgerRecord::reason).toList(),
                                                rows.stream()
                                                        .map(x -> Shared.date(x.createdAt()))
                                                        .toList()
                                            });
                },
                "harbor load [last] %number% "
                        + CUR
                        + " history [entries] of %offlineplayer% into"
                        + " %objects% [with reasons in %-objects%] [with dates in %-objects%]");
    }

    private static void mail(Registrar r) {
        r.condition(
                wiki.creeper.harbor.skript.lambda.Spec.single("harbor mail available"),
                (e, x) -> api().mail().isPresent(),
                "harbor mail is available",
                "harbor mail (isn't|is not) available");
        // No gold mail: claimed gold is booked as MAIL_GOLD and would escape the SCRIPT_* ledger
        // audit. Scripts give gold to offline players with `harbor give N gold` instead.
        Ext.await(
                r,
                "harbor load unclaimed mail",
                new int[] {1},
                c -> {
                    Optional<MailService> mail = api().mail();
                    Optional<OfflinePlayer> p = c.offlinePlayer(0);
                    if (mail.isEmpty() || p.isEmpty()) {
                        return null;
                    }
                    return Ext.value(mail.get().unclaimed(p.get().getUniqueId()));
                },
                "harbor load unclaimed mail count of %offlineplayer% into %objects%");
    }

    private static void eventValues(Registrar r) {
        r.value(
                CurrencyChangedEvent.class,
                org.bukkit.entity.Player.class,
                e -> org.bukkit.Bukkit.getPlayer(e.player()));
        Ext.expr(r, Number.class, "harbor currency event balance")
                .in(CurrencyChangedEvent.class)
                .register(
                        c ->
                                c.event() instanceof CurrencyChangedEvent e
                                        ? List.of(e.balance())
                                        : List.of(),
                        "[the] harbor (changed|new) balance");
        Ext.expr(r, String.class, "harbor currency event reason")
                .in(CurrencyChangedEvent.class)
                .register(
                        c ->
                                c.event() instanceof CurrencyChangedEvent e
                                        ? Args.one(e.reason().name())
                                        : List.of(),
                        "[the] harbor currency [change] reason");
    }
}
