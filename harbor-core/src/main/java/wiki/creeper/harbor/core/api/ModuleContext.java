package wiki.creeper.harbor.core.api;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.event.Listener;
import wiki.creeper.harbor.common.content.ContentCheck;
import wiki.creeper.harbor.common.content.ContentType;
import wiki.creeper.harbor.core.task.Tasks;

/** 모듈이 Core에 자기 것을 등록하는 창구. {@link HarborModule#enable}에서만 쓴다. */
public interface ModuleContext {

    HarborModule plugin();

    HarborApi api();

    /** 모듈이 꺼질 때 남은 작업을 자동 취소하는 작업 예약 도구. */
    Tasks tasks();

    /** 콘텐츠 형식 등록. 모듈이 켜진 뒤 콘텐츠를 다시 읽는다 (실패하면 서버 시작 중단). */
    void contentType(ContentType<?> type);

    <T> void contentCheck(ContentType<T> type, ContentCheck<T> check);

    void playerComponent(PlayerComponent<?> component);

    /** {@code /harbor <이름> …} 관리 하위 명령. 권한은 harbor.admin.<이름>으로 자동으로 건다. */
    void adminCommand(LiteralArgumentBuilder<CommandSourceStack> command);

    /** 이 모듈의 DB 마이그레이션(db/migration/<모듈 ID>)을 적용한다. */
    void migrate();

    void listen(Listener listener);
}
