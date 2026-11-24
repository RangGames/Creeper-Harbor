package wiki.creeper.harbor.fishing.present;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import wiki.creeper.harbor.common.content.type.FishDef;
import wiki.creeper.harbor.common.model.Grade;
import wiki.creeper.harbor.common.model.Quality;
import wiki.creeper.harbor.core.api.Dialogs;
import wiki.creeper.harbor.core.api.HarborApi;

/**
 * Catch result card ("4. Fishing presentation & boss fights" 1.3: catch result · record card ·
 * collection cut). A Dialog instead of chat with the size, ★ and first-catch line, and "view
 * collection / show off" buttons. A legendary or higher first catch adds the species description
 * (collection cut). Main thread only.
 */
final class CatchCard {

    /** Show-off cooldown per player (proposed value). */
    private static final Duration BRAG_COOLDOWN = Duration.ofSeconds(30);

    private static final String CODEX_COMMAND = "도감";

    private final HarborApi api;
    private final Map<UUID, Instant> lastBrag = new HashMap<>();

    CatchCard(HarborApi api) {
        this.api = api;
    }

    void show(Player player, FishDef fish, double sizeCm, Quality quality, boolean firstCatch) {
        String size = String.format(Locale.ROOT, "%.1f", sizeCm);
        List<Component> body = new ArrayList<>();
        body.add(
                api.messages()
                        .get(
                                "fishing.present.card",
                                Placeholder.unparsed("size", size),
                                Placeholder.unparsed("quality", quality.display())));
        if (firstCatch) {
            body.add(api.messages().get("fishing.present.first_catch"));
            if (fish.grade().isAtLeast(Grade.LEGENDARY)) {
                for (String line : fish.lore()) {
                    body.add(
                            api.messages()
                                    .get(
                                            "fishing.present.cut_line",
                                            Placeholder.parsed("line", line)));
                }
            }
        }
        // Collection registers /도감 (Brigadier); without it the button is left out
        boolean codex = Bukkit.getCommandMap().getCommand(CODEX_COMMAND) != null;
        List<Dialogs.Choice> choices = new ArrayList<>();
        if (codex) {
            choices.add(choice("card_codex"));
        }
        choices.add(choice("card_brag"));
        choices.add(choice("card_close"));
        api.dialogs()
                .choose(
                        player,
                        api.messages()
                                .get(
                                        "item.name." + fish.grade().key(),
                                        Placeholder.unparsed("name", fish.name())),
                        body,
                        choices,
                        choices.size(),
                        index -> {
                            int i = codex ? index : index + 1;
                            switch (i) {
                                case 0 -> player.performCommand(CODEX_COMMAND);
                                case 1 -> brag(player, fish, size, quality);
                                default -> {
                                    // Close
                                }
                            }
                        });
    }

    private Dialogs.Choice choice(String key) {
        return new Dialogs.Choice(
                api.messages().get("fishing.present." + key),
                api.messages().get("fishing.present." + key + "_tip"));
    }

    /** Show off: one line to everyone on this server (cooldown per player). */
    private void brag(Player player, FishDef fish, String size, Quality quality) {
        Instant now = Instant.now();
        Instant last = lastBrag.get(player.getUniqueId());
        if (last != null && last.plus(BRAG_COOLDOWN).isAfter(now)) {
            api.messages().send(player, "fishing.present.brag_cooldown");
            return;
        }
        lastBrag.put(player.getUniqueId(), now);
        Component line =
                api.messages()
                        .get(
                                "fishing.present.brag",
                                Placeholder.unparsed("player", player.getName()),
                                Placeholder.unparsed("name", fish.name()),
                                Placeholder.unparsed("size", size),
                                Placeholder.unparsed("quality", quality.display()));
        for (Player online : Bukkit.getOnlinePlayers()) {
            online.sendMessage(line);
        }
    }

    void forget(UUID player) {
        lastBrag.remove(player);
    }
}
