package wiki.creeper.harbor.core.pack;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import wiki.creeper.harbor.common.content.type.SoundTable;
import wiki.creeper.harbor.common.redis.Channels;
import wiki.creeper.harbor.common.redis.MessageBus;
import wiki.creeper.harbor.core.api.ContentService;
import wiki.creeper.harbor.core.api.HarborSounds;
import wiki.creeper.harbor.core.api.event.CurrencyChangedEvent;
import wiki.creeper.harbor.core.task.Tasks;

/**
 * Common sounds played by Core regardless of module ('15. Sound' 4.5, 4.7, proposed).
 *
 * <ul>
 *   <li>Currency change: sounds.yml currency table (gold sell/buy = ui.coin, mulbineul gain =
 *       ui.mulbineul). After commit, on the main thread, to that player only
 *   <li>Server announcements (harbor:announce: legendary catch, new record, boss): event.announce
 *       to everyone on this server. The announcement text is sent by the proxy
 * </ul>
 */
public final class SoundCues implements Listener {

    public static final String ANNOUNCE = "harbor:event.announce";

    private final ContentService content;

    public SoundCues(ContentService content) {
        this.content = content;
    }

    /**
     * Subscribes to server announcements. Messages arrive on a Redis thread, so they are handed to
     * the main thread.
     */
    public void subscribe(MessageBus bus, Tasks tasks) {
        bus.subscribe(
                Channels.ANNOUNCEMENTS,
                envelope ->
                        tasks.main()
                                .execute(
                                        () ->
                                                HarborSounds.play(
                                                        Bukkit.getOnlinePlayers(),
                                                        ANNOUNCE,
                                                        HarborSounds.Options.DEFAULT)));
    }

    @EventHandler
    public void onCurrency(CurrencyChangedEvent event) {
        Player player = Bukkit.getPlayer(event.player());
        if (player == null) {
            return;
        }
        SoundTable table = content.snapshot().single(SoundTable.TYPE);
        table.cue(event.currency())
                .filter(cue -> cue.matches(event.reason(), event.delta()))
                .ifPresent(cue -> HarborSounds.play(player, cue.sound()));
    }
}
