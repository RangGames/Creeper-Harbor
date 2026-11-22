package wiki.creeper.harbor.skript;

import ch.njol.skript.classes.Changer.ChangeMode;
import ch.njol.skript.lang.Expression;
import ch.njol.skript.lang.SkriptParser.ParseResult;
import ch.njol.skript.lang.util.SimpleExpression;
import ch.njol.util.Kleenean;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.ledger.LedgerReason;
import wiki.creeper.harbor.common.model.Currency;
import wiki.creeper.harbor.core.api.CurrencyService;
import wiki.creeper.harbor.core.api.Harbor;
import wiki.creeper.harbor.core.api.HarborPlayer;

/**
 * {@code harbor gold of %players%} · {@code harbor mulbineul of %players%}. Only players online on
 * this server have a value (cached balance). add · remove · set are processed as ledger
 * transactions (async; nothing is deducted if the balance is insufficient).
 */
public final class ExprBalance extends SimpleExpression<Long> {

    private @Nullable Expression<Player> players;
    private Currency currency = Currency.GOLD;

    @Override
    @SuppressWarnings("unchecked")
    public boolean init(
            Expression<?>[] exprs, int pattern, Kleenean delayed, ParseResult parseResult) {
        players = (Expression<Player>) exprs[0];
        currency = parseResult.mark == 1 ? Currency.MULBINEUL : Currency.GOLD;
        return true;
    }

    private Player[] players(Event event) {
        Expression<Player> p = players;
        return p == null ? new Player[0] : p.getArray(event);
    }

    @Override
    protected Long @Nullable [] get(Event event) {
        List<Long> out = new ArrayList<>();
        for (Player player : players(event)) {
            Optional<? extends HarborPlayer> hp = Harbor.api().players().get(player.getUniqueId());
            hp.ifPresent(h -> out.add(h.balance(currency)));
        }
        return out.toArray(new Long[0]);
    }

    @Override
    public Class<?> @Nullable [] acceptChange(ChangeMode mode) {
        return switch (mode) {
            case ADD, REMOVE, SET -> new Class<?>[] {Number.class};
            default -> null;
        };
    }

    @Override
    public void change(Event event, Object @Nullable [] delta, ChangeMode mode) {
        if (delta == null || delta.length == 0 || !(delta[0] instanceof Number n)) {
            return;
        }
        long amount = n.longValue();
        CurrencyService currencies = Harbor.api().currency();
        for (Player player : players(event)) {
            UUID uuid = player.getUniqueId();
            long change =
                    switch (mode) {
                        case ADD -> amount;
                        case REMOVE -> -amount;
                        case SET ->
                                amount
                                        - Harbor.api()
                                                .players()
                                                .get(uuid)
                                                .map(h -> h.balance(currency))
                                                .orElse(amount);
                        default -> 0;
                    };
            String ref = "skript:" + UUID.randomUUID();
            if (change > 0) {
                var _ = currencies.give(uuid, currency, change, LedgerReason.SCRIPT_GRANT, ref);
            } else if (change < 0) {
                var _ = currencies.take(uuid, currency, -change, LedgerReason.SCRIPT_TAKE, ref);
            }
        }
    }

    @Override
    public boolean isSingle() {
        Expression<Player> p = players;
        return p == null || p.isSingle();
    }

    @Override
    public Class<? extends Long> getReturnType() {
        return Long.class;
    }

    @Override
    public String toString(@Nullable Event event, boolean debug) {
        Expression<Player> p = players;
        return "harbor " + currency.key() + " of " + (p == null ? "?" : p.toString(event, debug));
    }
}
