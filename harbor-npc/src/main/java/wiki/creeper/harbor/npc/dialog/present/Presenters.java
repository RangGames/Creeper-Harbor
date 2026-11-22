package wiki.creeper.harbor.npc.dialog.present;

import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import wiki.creeper.harbor.common.id.NpcId;
import wiki.creeper.harbor.core.api.HarborApi;
import wiki.creeper.harbor.core.task.Tasks;
import wiki.creeper.harbor.npc.api.DialogStyle;
import wiki.creeper.harbor.npc.entity.NpcManager;

/**
 * Screens per dialog style (proposed). DialogManager only decides pages and buttons and leaves
 * drawing to this. Session-less one-liners (say) are handled here too. Main thread only.
 */
public final class Presenters {

    private final HarborApi api;
    private final NpcManager npcs;
    private final Map<DialogStyle, DialogPresenter> byStyle = new EnumMap<>(DialogStyle.class);
    private final ChatPresenter chat;
    private final TitlePresenter title;
    private final ActionBarPresenter actionBar;
    private final HologramPresenter hologram;

    public Presenters(Plugin plugin, HarborApi api, Tasks tasks, NpcManager npcs) {
        this.api = api;
        this.npcs = npcs;
        ChatButtons buttons =
                new ChatButtons(
                        api,
                        tasks.main(),
                        () -> Duration.ofSeconds(npcs.balance().dialogTimeoutSeconds()));
        this.chat = new ChatPresenter(api, buttons);
        this.title =
                new TitlePresenter(
                        api,
                        buttons,
                        chat,
                        () -> npcs.balance().titleMaxChars(),
                        () -> npcs.balance().titleStaySeconds());
        this.actionBar =
                new ActionBarPresenter(api, buttons, () -> npcs.balance().actionBarRefreshTicks());
        this.hologram = new HologramPresenter(plugin, api, tasks, npcs, buttons, chat);
        byStyle.put(DialogStyle.DIALOG, new DialogScreenPresenter(api));
        byStyle.put(DialogStyle.CHAT, chat);
        byStyle.put(DialogStyle.TITLE, title);
        byStyle.put(DialogStyle.ACTIONBAR, actionBar);
        byStyle.put(DialogStyle.HOLOGRAM, hologram);
    }

    public DialogPresenter of(DialogStyle style) {
        DialogPresenter p = byStyle.get(style);
        return p == null ? chat : p;
    }

    /**
     * Removes the style's leftover display (session end). Does not close the Dialog screen
     * (DialogManager decides).
     */
    public void clear(Player player, DialogStyle style) {
        of(style).clear(player);
    }

    /** A player who quit (without a Player object). */
    public void forget(UUID uuid) {
        hologram.remove(uuid);
    }

    /** Watch timer: handles what to resend or remove per style. */
    public void refresh(Player player) {
        actionBar.refresh(player);
        hologram.refresh(player);
    }

    /** Whether the player has a speech bubble or action bar up that needs refresh. */
    public boolean needsRefresh(UUID uuid) {
        return hologram.has(uuid);
    }

    /** A session-less one-liner (say). DIALOG shows a notice screen. */
    public void say(
            Player player, NpcId npc, Component speaker, Component line, DialogStyle style) {
        switch (style) {
            case DIALOG -> api.dialogs().notice(player, speaker, List.of(line));
            case HOLOGRAM -> {
                if (!hologram.bark(player, npc, speaker, line)) {
                    player.sendMessage(chat.line(speaker, line));
                }
            }
            case TITLE -> title.title(player, speaker, line, npcs.balance().barkSeconds());
            case ACTIONBAR -> actionBar.send(player, actionBar.line(speaker, line));
            case CHAT -> player.sendMessage(chat.line(speaker, line));
        }
    }

    /** Plugin shutdown. */
    public void shutdown() {
        hologram.clearAll();
        for (Player p : Bukkit.getOnlinePlayers()) {
            actionBar.clear(p);
        }
    }
}
