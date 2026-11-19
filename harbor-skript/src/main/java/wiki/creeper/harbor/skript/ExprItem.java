package wiki.creeper.harbor.skript;

import ch.njol.skript.lang.Expression;
import ch.njol.skript.lang.SkriptParser.ParseResult;
import ch.njol.skript.lang.util.SimpleExpression;
import ch.njol.util.Kleenean;
import java.util.Optional;
import org.bukkit.event.Event;
import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.content.type.ItemDef;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.core.api.Harbor;
import wiki.creeper.harbor.core.api.HarborApi;

/**
 * {@code harbor item %string%}: Harbor 아이템 1개. {@code item/x}는 ItemService, {@code furniture/x}는
 * 가구, {@code block/x}는 커스텀 블록. 개수는 Skript의 {@code 5 of harbor item "item/bait/worm"}처럼 쓴다. 없는 ID면
 * 값이 없다.
 */
public final class ExprItem extends SimpleExpression<ItemStack> {

    private @Nullable Expression<String> id;

    @Override
    @SuppressWarnings("unchecked")
    public boolean init(
            Expression<?>[] exprs, int pattern, Kleenean delayed, ParseResult parseResult) {
        id = (Expression<String>) exprs[0];
        return true;
    }

    @Override
    protected ItemStack @Nullable [] get(Event event) {
        Expression<String> expr = id;
        String raw = expr == null ? null : expr.getSingle(event);
        if (raw == null) {
            return new ItemStack[0];
        }
        return create(raw.strip()).map(s -> new ItemStack[] {s}).orElse(new ItemStack[0]);
    }

    static Optional<ItemStack> create(String raw) {
        Optional<HarborId> parsed = HarborId.tryParse(raw);
        if (parsed.isEmpty()) {
            return Optional.empty();
        }
        HarborId id = parsed.get();
        HarborApi api = Harbor.api();
        return switch (id.category()) {
            case "furniture" -> api.furniture().furnitureItem(id);
            case "block" -> api.furniture().blockItem(id, 1);
            default ->
                    api.content()
                            .snapshot()
                            .get(ItemDef.TYPE, id)
                            .map(def -> api.items().create(def, 1));
        };
    }

    @Override
    public boolean isSingle() {
        return true;
    }

    @Override
    public Class<? extends ItemStack> getReturnType() {
        return ItemStack.class;
    }

    @Override
    public String toString(@Nullable Event event, boolean debug) {
        Expression<String> expr = id;
        return "harbor item " + (expr == null ? "?" : expr.toString(event, debug));
    }
}
