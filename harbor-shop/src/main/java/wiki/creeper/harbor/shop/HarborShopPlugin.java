package wiki.creeper.harbor.shop;

import wiki.creeper.harbor.core.api.HarborModule;
import wiki.creeper.harbor.core.api.ModuleContext;

/** Harbor-Shop: 결제·진주·코스메틱. 아직 빈 모듈이다 ('14. 개발 일정' 참고). */
public final class HarborShopPlugin extends HarborModule {

    @Override
    public String moduleId() {
        return "shop";
    }

    @Override
    protected void enable(ModuleContext ctx) {
        // 구현 예정
    }
}
