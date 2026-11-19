package wiki.creeper.harbor.skript;

import ch.njol.skript.lang.Condition;
import ch.njol.skript.lang.Expression;
import ch.njol.skript.lang.SkriptParser.ParseResult;
import ch.njol.util.Kleenean;
import org.bukkit.event.Event;
import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.Nullable;

/** {@code %itemstacks% is [a] harbor item} · {@code is not}. */
public final class CondHarborItem extends Condition {

    private @Nullable Expression<ItemStack> items;

    @Override
    @SuppressWarnings("unchecked")
    public boolean init(
            Expression<?>[] exprs, int pattern, Kleenean delayed, ParseResult parseResult) {
        items = (Expression<ItemStack>) exprs[0];
        setNegated(pattern == 1);
        return true;
    }

    @Override
    public boolean check(Event event) {
        Expression<ItemStack> expr = items;
        if (expr == null) {
            return isNegated();
        }
        return expr.check(event, stack -> ExprId.idOf(stack).isPresent(), isNegated());
    }

    @Override
    public String toString(@Nullable Event event, boolean debug) {
        Expression<ItemStack> expr = items;
        return (expr == null ? "?" : expr.toString(event, debug))
                + (isNegated() ? " is not" : " is")
                + " a harbor item";
    }
}
