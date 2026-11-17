package wiki.creeper.harbor.quest;

import wiki.creeper.harbor.core.api.HarborModule;
import wiki.creeper.harbor.core.api.ModuleContext;

/** Harbor-Quest: 퀘스트. 아직 빈 모듈이다 ('14. 개발 일정' 참고). */
public final class HarborQuestPlugin extends HarborModule {

    @Override
    public String moduleId() {
        return "quest";
    }

    @Override
    protected void enable(ModuleContext ctx) {
        // 구현 예정
    }
}
