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
 * 대화 방식별 화면 모음 (추가 제안). DialogManager는 쪽·버튼만 정하고 그리기는 여기 맡긴다. 세션 없는 한마디(say)도 여기서 처리한다. 메인 스레드 전용.
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

    /** 그 방식의 남은 표시를 지운다 (세션 끝). Dialog 화면은 닫지 않는다 (DialogManager가 정함). */
    public void clear(Player player, DialogStyle style) {
        of(style).clear(player);
    }

    /** 퇴장한 플레이어 (Player 객체 없이). */
    public void forget(UUID uuid) {
        hologram.remove(uuid);
    }

    /** watch 타이머: 방식마다 다시 보낼 것·지울 것 처리. */
    public void refresh(Player player) {
        actionBar.refresh(player);
        hologram.refresh(player);
    }

    /** 말풍선·액션바가 떠 있어 refresh가 필요한 플레이어인지. */
    public boolean needsRefresh(UUID uuid) {
        return hologram.has(uuid);
    }

    /** 세션 없는 한마디 (say). DIALOG면 안내 화면. */
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

    /** 플러그인 종료. */
    public void shutdown() {
        hologram.clearAll();
        for (Player p : Bukkit.getOnlinePlayers()) {
            actionBar.clear(p);
        }
    }
}
