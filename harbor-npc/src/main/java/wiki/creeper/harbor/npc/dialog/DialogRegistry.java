package wiki.creeper.harbor.npc.dialog;

import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.plugin.Plugin;
import org.slf4j.Logger;
import wiki.creeper.harbor.common.content.ContentCheck;
import wiki.creeper.harbor.common.content.ContentSnapshot;
import wiki.creeper.harbor.npc.api.ArgCheck;
import wiki.creeper.harbor.npc.api.DialogAction;
import wiki.creeper.harbor.npc.api.DialogCondition;
import wiki.creeper.harbor.npc.content.DialogDef;
import wiki.creeper.harbor.npc.content.DialogRef;

/**
 * 대화 조건·액션 레지스트리 (docs 20 5.4 콘텐츠 검사). 키가 등록되어 있으면 인자까지 검사하고, 등록한 모듈이 하나도 없는 이름공간(선택 의존 모듈 없음)의 키는
 * 오류 대신 경고로 남기고 그 선택지를 숨긴다. NPC 기본 키와, 키가 하나라도 등록된 이름공간의 모르는 키는 오타로 보고 오류다.
 */
public final class DialogRegistry {

    record Entry<T>(Plugin owner, T value) {}

    /** NPC 기본 이름공간 (affinity:add, flag:set …). */
    static final Set<String> BUILTIN_NAMESPACES =
            Set.of("", "affinity", "flag", "gift", "cutscene");

    private final Map<String, Entry<DialogAction>> actions = new ConcurrentHashMap<>();
    private final Map<String, Entry<DialogCondition>> conditions = new ConcurrentHashMap<>();
    private final Set<String> warned = ConcurrentHashMap.newKeySet();
    private final Logger log;

    public DialogRegistry(Logger log) {
        this.log = log;
    }

    public void action(Plugin owner, String key, DialogAction action) {
        requireKey(key);
        if (actions.putIfAbsent(key, new Entry<>(owner, action)) != null) {
            throw new IllegalStateException("대화 액션 키가 이미 등록되어 있습니다: " + key);
        }
    }

    public void condition(Plugin owner, String key, DialogCondition condition) {
        requireKey(key);
        if (conditions.putIfAbsent(key, new Entry<>(owner, condition)) != null) {
            throw new IllegalStateException("대화 조건 키가 이미 등록되어 있습니다: " + key);
        }
    }

    private static void requireKey(String key) {
        if (!DialogRef.KEY.matcher(key).matches()) {
            throw new IllegalArgumentException("대화 키 형식이 틀렸습니다 (소문자, 모듈:이름): " + key);
        }
    }

    public Optional<DialogAction> action(String key) {
        return Optional.ofNullable(actions.get(key)).map(Entry::value);
    }

    public Optional<DialogCondition> condition(String key) {
        return Optional.ofNullable(conditions.get(key)).map(Entry::value);
    }

    public boolean hasAction(String key) {
        return actions.containsKey(key);
    }

    public boolean hasCondition(String key) {
        return conditions.containsKey(key);
    }

    public void unregisterAll(Plugin owner) {
        actions.values().removeIf(e -> e.owner().equals(owner));
        conditions.values().removeIf(e -> e.owner().equals(owner));
    }

    /** 키가 하나라도 등록된 이름공간. */
    private Set<String> namespaces() {
        Set<String> ns = new HashSet<>(BUILTIN_NAMESPACES);
        actions.keySet().forEach(k -> ns.add(namespace(k)));
        conditions.keySet().forEach(k -> ns.add(namespace(k)));
        return ns;
    }

    private static String namespace(String key) {
        int colon = key.indexOf(':');
        return colon < 0 ? "" : key.substring(0, colon);
    }

    /** 콘텐츠 검사 (Core 리로드 때). */
    public ContentCheck<DialogDef> check() {
        return (dialog, ctx) -> {
            Set<String> namespaces = namespaces();
            for (DialogRef ref : dialog.allActions()) {
                Entry<DialogAction> a = actions.get(ref.key());
                if (a != null) {
                    a.value().validate(ref.args(), argCheck(ctx.snapshot(), ref, ctx));
                } else if (namespaces.contains(ref.namespace())) {
                    ctx.error("(" + ref.pos() + ") 등록되지 않은 대화 액션입니다: " + ref.key());
                }
            }
            for (DialogRef ref : dialog.allConditions()) {
                Entry<DialogCondition> c = conditions.get(ref.key());
                if (c != null) {
                    c.value().validate(ref.args(), argCheck(ctx.snapshot(), ref, ctx));
                } else if (namespaces.contains(ref.namespace())) {
                    ctx.error("(" + ref.pos() + ") 등록되지 않은 대화 조건입니다: " + ref.key());
                }
            }
        };
    }

    private static ArgCheck argCheck(
            ContentSnapshot snapshot, DialogRef ref, ContentCheck.Context ctx) {
        return new ArgCheck() {
            @Override
            public ContentSnapshot snapshot() {
                return snapshot;
            }

            @Override
            public void error(String message) {
                ctx.error("(" + ref.pos() + ") " + ref.key() + ": " + message);
            }
        };
    }

    /** 모든 모듈이 켜진 뒤: 등록되지 않은 키를 경고로 남긴다 (그 선택지는 숨김). */
    public void warnMissing(Iterable<DialogDef> dialogs) {
        for (DialogDef dialog : dialogs) {
            for (DialogRef ref : dialog.allActions()) {
                if (!actions.containsKey(ref.key())) {
                    warnOnce(ref, "액션");
                }
            }
            for (DialogRef ref : dialog.allConditions()) {
                if (!conditions.containsKey(ref.key())) {
                    warnOnce(ref, "조건");
                }
            }
        }
    }

    void warnOnce(DialogRef ref, String what) {
        if (warned.add(what + ref.key() + ref.pos())) {
            log.warn("대화 {} 키 '{}'를 등록한 모듈이 없어 그 선택지를 숨깁니다 ({})", what, ref.key(), ref.pos());
        }
    }
}
