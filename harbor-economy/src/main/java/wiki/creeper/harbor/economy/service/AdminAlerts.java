package wiki.creeper.harbor.economy.service;

import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import wiki.creeper.harbor.common.redis.Channel;
import wiki.creeper.harbor.core.api.HarborApi;
import wiki.creeper.harbor.core.task.Tasks;

/**
 * 관리자 알림 (부정 거래 의심, 시세 갱신 중단). 모든 서버의 권한 {@value #PERMISSION}을 가진 접속자에게 보낸다. 채널 harbor:econ_alert
 * (추가 제안).
 */
public final class AdminAlerts {

    public static final String PERMISSION = "harbor.admin.economy.alert";

    /** 알림 한 건. value는 문구의 {@code <value>} 자리. */
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

    /** 어느 스레드에서 불러도 된다. */
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
