package wiki.creeper.harbor.skript.lambda;

import java.util.function.Function;
import org.bukkit.OfflinePlayer;
import org.bukkit.event.Event;
import org.jspecify.annotations.Nullable;
import org.skriptlang.skript.bukkit.lang.eventvalue.EventValue;
import org.skriptlang.skript.bukkit.lang.eventvalue.EventValueRegistry;
import org.skriptlang.skript.bukkit.registration.BukkitSyntaxInfos;
import org.skriptlang.skript.registration.DefaultSyntaxInfos;
import org.skriptlang.skript.registration.SyntaxInfo;
import org.skriptlang.skript.registration.SyntaxRegistry;

/**
 * Lambda syntax registration helper (Skript 2.17 registration API). Counts the registered syntax
 * elements.
 */
public final class Registrar {

    private final SyntaxRegistry registry;
    private final EventValueRegistry values;
    private int count;

    public Registrar(SyntaxRegistry registry, EventValueRegistry values) {
        this.registry = registry;
        this.values = values;
    }

    public SyntaxRegistry registry() {
        return registry;
    }

    public int count() {
        return count;
    }

    public void counted() {
        count++;
    }

    public void number(Spec spec, Spec.Getter<Number> getter, String... patterns) {
        registry.register(
                SyntaxRegistry.EXPRESSION,
                DefaultSyntaxInfos.Expression.builder(NumberExpr.class, Number.class)
                        .addPatterns(patterns)
                        .supplier(() -> new NumberExpr(spec, getter))
                        .build());
        count++;
    }

    public void text(Spec spec, Spec.Getter<String> getter, String... patterns) {
        registry.register(
                SyntaxRegistry.EXPRESSION,
                DefaultSyntaxInfos.Expression.builder(TextExpr.class, String.class)
                        .addPatterns(patterns)
                        .supplier(() -> new TextExpr(spec, getter))
                        .build());
        count++;
    }

    public void player(Spec spec, Spec.Getter<OfflinePlayer> getter, String... patterns) {
        registry.register(
                SyntaxRegistry.EXPRESSION,
                DefaultSyntaxInfos.Expression.builder(OfflinePlayerExpr.class, OfflinePlayer.class)
                        .addPatterns(patterns)
                        .supplier(() -> new OfflinePlayerExpr(spec, getter))
                        .build());
        count++;
    }

    /**
     * @param patterns positive and negative patterns alternating (even index = positive)
     */
    public void condition(Spec spec, Spec.Check check, String... patterns) {
        registry.register(
                SyntaxRegistry.CONDITION,
                SyntaxInfo.builder(LambdaCondition.class)
                        .addPatterns(patterns)
                        .supplier(() -> new LambdaCondition(spec, check))
                        .build());
        count++;
    }

    public void effect(Spec spec, Spec.Action action, String... patterns) {
        registry.register(
                SyntaxRegistry.EFFECT,
                SyntaxInfo.builder(LambdaEffect.class)
                        .addPatterns(patterns)
                        .supplier(() -> new LambdaEffect(spec, action))
                        .build());
        count++;
    }

    public void event(
            String name, Class<? extends Event> type, String description, String... patterns) {
        registry.register(
                BukkitSyntaxInfos.Event.KEY,
                BukkitSyntaxInfos.Event.builder(HarborEvent.class, name)
                        .addEvent(type)
                        .addPatterns(patterns)
                        .addDescription(description)
                        .supplier(HarborEvent::new)
                        .build());
        count++;
    }

    /** event-value (the value of this type extracted from that event). */
    public <E extends Event, V> void value(
            Class<E> event, Class<V> type, Function<E, @Nullable V> getter) {
        values.register(EventValue.simple(event, type, getter::apply));
    }
}
