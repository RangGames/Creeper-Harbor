package wiki.creeper.harbor.economy;

import wiki.creeper.harbor.core.api.HarborModule;
import wiki.creeper.harbor.core.api.ModuleContext;

/** Harbor-Economy: 상점·경매·시세. 아직 빈 모듈이다 ('14. 개발 일정' 참고). */
public final class HarborEconomyPlugin extends HarborModule {

    @Override
    public String moduleId() {
        return "economy";
    }

    @Override
    protected void enable(ModuleContext ctx) {
        // 구현 예정
    }
}
