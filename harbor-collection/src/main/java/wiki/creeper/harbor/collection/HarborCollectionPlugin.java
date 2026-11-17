package wiki.creeper.harbor.collection;

import wiki.creeper.harbor.core.api.HarborModule;
import wiki.creeper.harbor.core.api.ModuleContext;

/** Harbor-Collection: 도감·수족관. 아직 빈 모듈이다 ('14. 개발 일정' 참고). */
public final class HarborCollectionPlugin extends HarborModule {

    @Override
    public String moduleId() {
        return "collection";
    }

    @Override
    protected void enable(ModuleContext ctx) {
        // 구현 예정
    }
}
