package wiki.creeper.harbor.core.api;

import org.bukkit.plugin.java.JavaPlugin;
import org.jspecify.annotations.Nullable;

/**
 * 게임 모듈(Jobs, Fishing …)의 메인 클래스가 상속한다. Core에 콘텐츠 형식·플레이어 데이터·명령어·보정자를 등록하는 순서를 고정한다.
 *
 * <pre>{@code
 * public final class HarborFishingPlugin extends HarborModule {
 *     public String moduleId() { return "fishing"; }
 *     protected void enable(ModuleContext ctx) {
 *         ctx.migrate();
 *         ctx.contentType(FishTable.TYPE);
 *         ctx.listen(new FishingListener(ctx.api()));
 *     }
 * }
 * }</pre>
 */
public abstract class HarborModule extends JavaPlugin {

    private ModuleHost.@Nullable Handle handle;

    /** 모듈 ID (소문자). 마이그레이션 폴더와 이력 테이블 이름에 쓴다. */
    public abstract String moduleId();

    protected abstract void enable(ModuleContext ctx);

    /** 꺼질 때 할 일. 예약 작업은 자동으로 취소된다. */
    protected void disable() {}

    @Override
    public final void onEnable() {
        ModuleHost.Handle h = ModuleHost.get().attach(this);
        handle = h;
        try {
            enable(h.context());
            h.finishEnable();
        } catch (RuntimeException e) {
            getSLF4JLogger().error("모듈 {} 시작 실패", moduleId(), e);
            h.failEnable(e);
        }
    }

    @Override
    public final void onDisable() {
        try {
            disable();
        } finally {
            ModuleHost.Handle h = handle;
            if (h != null) {
                h.detach();
            }
            handle = null;
        }
    }
}
