package wiki.creeper.harbor.core.api;

import java.util.List;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;

/**
 * Dialog screen wrapper. The Paper Dialog API is experimental, so it is hidden behind this
 * interface; if the API changes, only Core needs fixing.
 */
public interface Dialogs {

    /** Notice screen (a single confirm button). */
    void notice(Player player, Component title, List<Component> body);

    /**
     * Yes/no screen. The pressed button's result arrives on the main thread (closing with ESC
     * yields no result).
     */
    void confirm(
            Player player,
            Component title,
            List<Component> body,
            Component yes,
            Component no,
            Consumer<Boolean> result);

    /** One option. */
    record Choice(Component label, Component tooltip) {}

    /**
     * Screen for choosing one of several options (e.g. choosing a second job branch). The chosen
     * index (from 0) arrives on the main thread.
     *
     * @param columns number of buttons per row
     */
    void choose(
            Player player,
            Component title,
            List<Component> body,
            List<Choice> choices,
            int columns,
            Consumer<Integer> result);

    /**
     * Text input screen (proposed, e.g. quest editor). On confirm, the input (trimmed) arrives on
     * the main thread. Cancel or ESC yields no result.
     *
     * @param initial initial value to prefill
     * @param maxLength maximum number of characters
     * @param multiline multi-line input (line breaks are kept as is)
     */
    void input(
            Player player,
            Component title,
            List<Component> body,
            Component label,
            String initial,
            int maxLength,
            boolean multiline,
            Component confirm,
            Component cancel,
            Consumer<String> result);
}
