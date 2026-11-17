package wiki.creeper.harbor.world;

import wiki.creeper.harbor.core.api.HarborModule;
import wiki.creeper.harbor.core.api.ModuleContext;

/** Harbor-World: 지역·해도·선박·하우징. 아직 빈 모듈이다 ('14. 개발 일정' 참고). */
public final class HarborWorldPlugin extends HarborModule {

    @Override
    public String moduleId() {
        return "world";
    }

    @Override
    protected void enable(ModuleContext ctx) {
        // 구현 예정
    }
}
