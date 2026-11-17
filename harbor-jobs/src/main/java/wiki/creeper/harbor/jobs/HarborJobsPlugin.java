package wiki.creeper.harbor.jobs;

import wiki.creeper.harbor.core.api.HarborModule;
import wiki.creeper.harbor.core.api.ModuleContext;

/** Harbor-Jobs: 직업·전직·스킬 트리. 아직 빈 모듈이다 ('14. 개발 일정' 참고). */
public final class HarborJobsPlugin extends HarborModule {

    @Override
    public String moduleId() {
        return "jobs";
    }

    @Override
    protected void enable(ModuleContext ctx) {
        // 구현 예정
    }
}
