package wiki.creeper.harbor.season.service;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.bukkit.plugin.Plugin;
import wiki.creeper.harbor.season.api.SeasonHook;
import wiki.creeper.harbor.season.api.SeasonHookRegistry;

/** SeasonHookRegistry 구현. 꺼진 플러그인의 훅은 목록에서 뺀다. */
public final class HookRegistry implements SeasonHookRegistry {

    private record Entry(Plugin owner, SeasonHook hook) {}

    private final List<Entry> entries = new CopyOnWriteArrayList<>();

    @Override
    public void register(Plugin owner, SeasonHook hook) {
        entries.removeIf(e -> e.hook().id().equals(hook.id()));
        entries.add(new Entry(owner, hook));
    }

    @Override
    public void unregister(SeasonHook hook) {
        entries.removeIf(e -> e.hook().equals(hook));
    }

    @Override
    public List<SeasonHook> hooks() {
        return entries.stream().filter(e -> e.owner().isEnabled()).map(Entry::hook).toList();
    }
}
