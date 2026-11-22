package wiki.creeper.harbor.skript;

import ch.njol.skript.lang.Expression;
import ch.njol.skript.lang.SkriptParser.ParseResult;
import ch.njol.skript.lang.util.SimpleExpression;
import ch.njol.util.Kleenean;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.bukkit.event.Event;
import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.core.api.Harbor;
import wiki.creeper.harbor.core.api.HarborApi;
import wiki.creeper.harbor.core.api.HarborItem;

/**
 * {@code harbor id of %itemstacks%}: Harbor item ID (fish/x for fish items, including furniture and
 * custom blocks). No value if it is not a Harbor item.
 */
public final class ExprId extends SimpleExpression<String> {

    private @Nullable Expression<ItemStack> items;

    @Override
    @SuppressWarnings("unchecked")
    public boolean init(
            Expression<?>[] exprs, int pattern, Kleenean delayed, ParseResult parseResult) {
        items = (Expression<ItemStack>) exprs[0];
        return true;
    }

    @Override
    protected String @Nullable [] get(Event event) {
        Expression<ItemStack> expr = items;
        List<String> out = new ArrayList<>();
        if (expr != null) {
            for (ItemStack stack : expr.getArray(event)) {
                idOf(stack).ifPresent(out::add);
            }
        }
        return out.toArray(new String[0]);
    }

    static Optional<String> idOf(ItemStack stack) {
        HarborApi api = Harbor.api();
        Optional<HarborItem> item = api.items().read(stack);
        if (item.isPresent()) {
            return Optional.of(
                    switch (item.get()) {
                        case HarborItem.Plain plain -> plain.id().id().toString();
                        case HarborItem.Fish fish -> fish.species().id().toString();
                    });
        }
        return api.furniture()
                .furnitureOf(stack)
                .or(() -> api.furniture().blockOf(stack))
                .map(Object::toString);
    }

    @Override
    public boolean isSingle() {
        Expression<ItemStack> expr = items;
        return expr == null || expr.isSingle();
    }

    @Override
    public Class<? extends String> getReturnType() {
        return String.class;
    }

    @Override
    public String toString(@Nullable Event event, boolean debug) {
        Expression<ItemStack> expr = items;
        return "harbor id of " + (expr == null ? "?" : expr.toString(event, debug));
    }
}
