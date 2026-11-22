package wiki.creeper.harbor.npc.dialog.present;

import java.time.Duration;
import java.util.function.IntSupplier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.entity.Player;
import wiki.creeper.harbor.core.api.HarborApi;
import wiki.creeper.harbor.npc.logic.SpeechText;

/**
 * TITLE: title = speaker, subtitle = line. Long lines may appear cut off, so they also go to chat.
 * Buttons are a chat row.
 */
final class TitlePresenter implements DialogPresenter {

    private static final Duration FADE_IN = Duration.ofMillis(250);
    private static final Duration FADE_OUT = Duration.ofMillis(500);

    private final HarborApi api;
    private final ChatButtons buttons;
    private final ChatPresenter echo;
    private final IntSupplier maxChars;
    private final java.util.function.DoubleSupplier defaultStay;

    TitlePresenter(
            HarborApi api,
            ChatButtons buttons,
            ChatPresenter echo,
            IntSupplier maxChars,
            java.util.function.DoubleSupplier defaultStay) {
        this.api = api;
        this.buttons = buttons;
        this.echo = echo;
        this.maxChars = maxChars;
        this.defaultStay = defaultStay;
    }

    @Override
    public void show(Player player, Frame frame) {
        Component text = frame.text();
        if (text != null) {
            double stay = frame.staySeconds() > 0 ? frame.staySeconds() : defaultStay.getAsDouble();
            title(player, frame.speaker(), text, stay);
        }
        buttons.send(player, frame);
    }

    void title(Player player, Component speaker, Component text, double staySeconds) {
        api.ui()
                .title(
                        player,
                        api.messages()
                                .get(
                                        "npc.dialog.style.title",
                                        Placeholder.component("speaker", speaker)),
                        api.messages()
                                .get(
                                        "npc.dialog.style.subtitle",
                                        Placeholder.component("text", text)),
                        FADE_IN,
                        Duration.ofMillis(Math.round(staySeconds * 1000)),
                        FADE_OUT);
        if (SpeechText.length(text) > maxChars.getAsInt()) {
            player.sendMessage(echo.line(speaker, text));
        }
    }

    @Override
    public void clear(Player player) {
        player.clearTitle();
    }
}
