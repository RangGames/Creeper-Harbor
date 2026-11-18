package wiki.creeper.harbor.season.api;

import java.util.List;
import org.bukkit.plugin.Plugin;

/** 시즌 전환 훅 등록 창구. 등록한 플러그인이 꺼지면 자동으로 빠진다. */
public interface SeasonHookRegistry {

    void register(Plugin owner, SeasonHook hook);

    void unregister(SeasonHook hook);

    /** 지금 등록된 훅 (등록 순서). */
    List<SeasonHook> hooks();
}
