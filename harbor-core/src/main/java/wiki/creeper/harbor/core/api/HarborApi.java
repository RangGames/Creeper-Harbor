package wiki.creeper.harbor.core.api;

import java.util.Optional;
import org.bukkit.Bukkit;
import wiki.creeper.harbor.common.config.InfraConfig;
import wiki.creeper.harbor.common.db.Database;
import wiki.creeper.harbor.common.db.WriteBehindQueue;
import wiki.creeper.harbor.common.redis.DistributedLock;
import wiki.creeper.harbor.common.redis.MessageBus;
import wiki.creeper.harbor.common.redis.Redis;
import wiki.creeper.harbor.common.time.HarborTime;

/** Core가 다른 모듈에 제공하는 서비스 묶음. */
public interface HarborApi {

    ContentService content();

    PlayerService players();

    ItemService items();

    CurrencyService currency();

    ModifierService modifiers();

    MessageService messages();

    FlagService flags();

    Ui ui();

    Dialogs dialogs();

    ProxyBridge proxy();

    /** 개인 세계 경계 중재. */
    WorldBorderService worldBorders();

    /** 디스플레이 리그 재생 ('16. 플러그인 설계' 5.10). */
    RigService rigs();

    InfraConfig infra();

    HarborTime time();

    Database database();

    WriteBehindQueue writeBehind();

    MessageBus bus();

    DistributedLock locks();

    /** Redis 키·값 접근 (캐시, 인스턴스 배정, 재접속 키). 입출력이므로 메인 스레드에서 부르지 않는다. */
    Redis redis();

    /**
     * 다른 모듈이 등록한 서비스 (Bukkit ServicesManager). 선택 의존 모듈의 서비스는 없을 수 있어서 Optional이다. 모듈이 켜진 뒤(다른 모듈의
     * enable 이후)에 찾는다.
     */
    default <T> Optional<T> service(Class<T> type) {
        return Optional.ofNullable(Bukkit.getServicesManager().load(type));
    }

    /** 우편함 (Economy가 구현). 없으면 호출한 쪽이 보류한다. */
    default Optional<MailService> mail() {
        return service(MailService.class);
    }

    /** 파티 (Boss가 구현). 없으면 모두 혼자다. */
    default Optional<PartyService> parties() {
        return service(PartyService.class);
    }
}
