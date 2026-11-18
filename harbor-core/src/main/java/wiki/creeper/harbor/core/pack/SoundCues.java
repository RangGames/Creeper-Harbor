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
 * 모듈과 상관없이 Core가 내는 공통 소리 ('15. 사운드' 4.5·4.7, 추가 제안).
 *
 * <ul>
 *   <li>재화 변경: sounds.yml currency 표 (골드 판매·구매 = ui.coin, 물비늘 획득 = ui.mulbineul). 커밋 뒤 메인 스레드에서
 *       본인에게만
 *   <li>서버 공지(harbor:announce, 전설 포획·신기록·보스): 이 서버에 접속한 모두에게 event.announce. 공지 문구는 프록시가 보낸다
 * </ul>
 */
public final class SoundCues implements Listener {

    public static final String ANNOUNCE = "harbor:event.announce";

    private final ContentService content;

    public SoundCues(ContentService content) {
        this.content = content;
    }

    /** 서버 공지 구독. 메시지는 Redis 스레드에서 오므로 메인 스레드로 넘긴다. */
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
