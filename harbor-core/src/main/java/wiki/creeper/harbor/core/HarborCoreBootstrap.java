package wiki.creeper.harbor.core;

import io.papermc.paper.plugin.bootstrap.BootstrapContext;
import io.papermc.paper.plugin.bootstrap.PluginBootstrap;

/**
 * 데이터팩 레지스트리 항목 등록 단계 (커스텀 인챈트, Dialog). Phase 1 기술 검증('8. 시스템 상세 기획' 13장)에서 Registry API 동작을 확인한 뒤
 * 낚싯대 전용 인챈트를 여기서 등록한다. 지금은 등록할 항목이 없다.
 */
public final class HarborCoreBootstrap implements PluginBootstrap {

    @Override
    public void bootstrap(BootstrapContext context) {
        context.getLogger().info("Harbor-Core 부트스트랩");
    }
}
