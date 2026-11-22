package wiki.creeper.harbor.economy.service;

import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import wiki.creeper.harbor.common.redis.Channel;
import wiki.creeper.harbor.core.api.HarborApi;
import wiki.creeper.harbor.core.task.Tasks;

/**
 * Admin alerts (suspected fraudulent trades, stalled market updates). Sent to online players with
 * permission {@value #PERMISSION} on every server. Channel harbor:econ_alert (proposed).
 */
public final class AdminAlerts {

    public static final String PERMISSION = "harbor.admin.economy.alert";

    /** One alert. value fills the {@code <value>} slot in the message. */
    public record Alert(String key, String value) {}

    static final Channel<Alert> CHANNEL = new Channel<>("harbor:econ_alert", Alert.class);

    private final HarborApi api;
    private final Tasks tasks;

    public AdminAlerts(HarborApi api, Tasks tasks) {
        this.api = api;
        this.tasks = tasks;
    }

    public void start() {
        api.bus()
                .subscribe(
                        CHANNEL,
                        envelope ->
                                tasks.main()
                                        .execute(
                                                () ->
                                                        show(
                                                                envelope.payload().key(),
                                                                envelope.payload().value())));
    }

    /** May be called from any thread. */
    public void alert(String key, String value) {
        api.bus().publish(CHANNEL, new Alert(key, value));
    }

    private void show(String key, String value) {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.hasPermission(PERMISSION)) {
                api.messages().send(player, key, Placeholder.unparsed("value", value));
            }
        }
    }
}
