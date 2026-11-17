package wiki.creeper.harbor.boss;

import wiki.creeper.harbor.core.api.HarborModule;
import wiki.creeper.harbor.core.api.ModuleContext;

/** Harbor-Boss: 보스전. 아직 빈 모듈이다 ('14. 개발 일정' 참고). */
public final class HarborBossPlugin extends HarborModule {

    @Override
    public String moduleId() {
        return "boss";
    }

    @Override
    protected void enable(ModuleContext ctx) {
        // 구현 예정
    }
}
