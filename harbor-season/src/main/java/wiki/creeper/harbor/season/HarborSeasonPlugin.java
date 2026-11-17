package wiki.creeper.harbor.season;

import wiki.creeper.harbor.core.api.HarborModule;
import wiki.creeper.harbor.core.api.ModuleContext;

/** Harbor-Season: 시즌 전환·시즌 패스. 아직 빈 모듈이다 ('14. 개발 일정' 참고). */
public final class HarborSeasonPlugin extends HarborModule {

    @Override
    public String moduleId() {
        return "season";
    }

    @Override
    protected void enable(ModuleContext ctx) {
        // 구현 예정
    }
}
