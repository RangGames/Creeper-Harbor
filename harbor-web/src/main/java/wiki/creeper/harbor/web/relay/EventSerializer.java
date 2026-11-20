package wiki.creeper.harbor.web.relay;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.common.model.Keyed;

/**
 * 게임 이벤트 → 외부 이벤트 data. 이벤트 클래스(와 Event 아래 부모)의 공개 getter를 읽어 snake_case 이름으로 담는다. 값은 JSON에 맞게 바꾼다:
 * 플레이어·UUID → UUID 글, Harbor ID(와 FishId 같은 감싼 레코드) → ID 글 + 이름을 찾으면 {@code <필드>_name}, 레코드 → 지도,
 * enum → 키, 위치 → {world,x,y,z}, 아이템 → {material, amount}, 문구 → 평문.
 *
 * <p>처음 나온 플레이어(Player 또는 이름이 player·uuid인 UUID)를 이벤트의 player로 쓴다. 어느 스레드에서 불러도 된다 (콘텐츠 스냅샷만 읽는다).
 */
public final class EventSerializer {

    /** 읽지 않는 메서드 (Bukkit Event 기본, 상태). */
    private static final Set<String> SKIP =
            Set.of(
                    "getHandlers",
                    "getEventName",
                    "isAsynchronous",
                    "isCancelled",
                    "callEvent",
                    "hashCode",
                    "toString",
                    "getClass");

    private static final int MAX_DEPTH = 5;

    /** 직렬화 결과. */
    public record Result(
            @Nullable UUID player, @Nullable String playerName, Map<String, Object> data) {}

    private record Getter(String name, Method method) {}

    private final Map<Class<?>, List<Getter>> getters = new ConcurrentHashMap<>();
    private final Function<HarborId, Optional<String>> names;

    /**
     * @param names Harbor ID → 콘텐츠 이름 (없으면 빈 값)
     */
    public EventSerializer(Function<HarborId, Optional<String>> names) {
        this.names = names;
    }

    public Result serialize(Event event) {
        Map<String, Object> data = new LinkedHashMap<>();
        UUID player = null;
        String playerName = null;
        for (Getter g : getters.computeIfAbsent(event.getClass(), EventSerializer::scan)) {
            Object raw;
            try {
                raw = g.method().invoke(event);
            } catch (ReflectiveOperationException | RuntimeException e) {
                continue;
            }
            if (raw instanceof Optional<?> opt) {
                raw = opt.orElse(null);
            }
            if (raw == null) {
                continue;
            }
            if (player == null) {
                if (raw instanceof Player p) {
                    player = p.getUniqueId();
                    playerName = p.getName();
                } else if (raw instanceof UUID u
                        && (g.name().equals("player") || g.name().equals("uuid"))) {
                    player = u;
                }
            }
            put(data, g.name(), raw, 0);
        }
        return new Result(player, playerName, data);
    }

    private void put(Map<String, Object> into, String name, Object raw, int depth) {
        Object value = convert(raw, depth);
        if (value == null) {
            return;
        }
        into.put(name, value);
        HarborId id = idOf(raw);
        if (id != null) {
            names.apply(id).ifPresent(n -> into.putIfAbsent(name + "_name", n));
        }
    }

    private @Nullable Object convert(@Nullable Object v, int depth) {
        return switch (v) {
            case null -> null;
            case String s -> s;
            case Number n -> n;
            case Boolean b -> b;
            case Character c -> String.valueOf(c);
            case UUID u -> u.toString();
            case HarborId id -> id.toString();
            case Player p -> p.getUniqueId().toString();
            case OfflinePlayer p -> p.getUniqueId().toString();
            case Keyed k -> k.key();
            case Enum<?> e -> e.name().toLowerCase(Locale.ROOT);
            case Component c -> PlainTextComponentSerializer.plainText().serialize(c);
            case Location l -> location(l);
            case ItemStack i -> item(i);
            case Entity e ->
                    Map.of(
                            "type",
                            e.getType().name().toLowerCase(Locale.ROOT),
                            "uuid",
                            e.getUniqueId().toString());
            case TemporalAccessor t -> t.toString();
            case java.time.Duration d -> d.toString();
            case Optional<?> o -> convert(o.orElse(null), depth);
            default -> depth >= MAX_DEPTH ? String.valueOf(v) : nested(v, depth);
        };
    }

    private @Nullable Object nested(Object v, int depth) {
        if (v instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            m.forEach(
                    (k, x) -> {
                        if (x != null) {
                            put(out, String.valueOf(convert(k, depth + 1)), x, depth + 1);
                        }
                    });
            return out;
        }
        if (v instanceof Collection<?> c) {
            return list(c, depth);
        }
        if (v.getClass().isArray() && !v.getClass().getComponentType().isPrimitive()) {
            return list(Arrays.asList((Object[]) v), depth);
        }
        if (v instanceof Record r) {
            HarborId id = idOf(r);
            if (id != null) {
                return id.toString();
            }
            if (r.getClass().getSimpleName().endsWith("Def")) {
                return definition(r);
            }
            Map<String, Object> out = new LinkedHashMap<>();
            for (RecordComponent rc : r.getClass().getRecordComponents()) {
                try {
                    Method accessor = rc.getAccessor();
                    accessor.setAccessible(true);
                    Object x = accessor.invoke(r);
                    if (x instanceof Optional<?> opt) {
                        x = opt.orElse(null);
                    }
                    if (x != null) {
                        put(out, snake(rc.getName()), x, depth + 1);
                    }
                } catch (ReflectiveOperationException | RuntimeException e) {
                    // 읽지 못한 칸은 뺀다
                }
            }
            return out;
        }
        return String.valueOf(v);
    }

    private List<Object> list(Collection<?> c, int depth) {
        List<Object> out = new ArrayList<>();
        for (Object x : c) {
            Object n = convert(x, depth + 1);
            if (n != null) {
                out.add(n);
            }
        }
        return out;
    }

    /** 콘텐츠 정의(…Def 레코드)는 통째로 내보내지 않고 id·no·name·grade만. */
    private static Map<String, Object> definition(Record r) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (RecordComponent rc : r.getClass().getRecordComponents()) {
            String n = rc.getName();
            if (!n.equals("id") && !n.equals("no") && !n.equals("name") && !n.equals("grade")) {
                continue;
            }
            try {
                Method accessor = rc.getAccessor();
                accessor.setAccessible(true);
                Object x = accessor.invoke(r);
                HarborId id = idOf(x);
                Object value =
                        id != null
                                ? id.toString()
                                : x instanceof Keyed k
                                        ? k.key()
                                        : x instanceof Enum<?> e
                                                ? e.name().toLowerCase(Locale.ROOT)
                                                : x;
                if (value != null) {
                    out.put(n, value);
                }
            } catch (ReflectiveOperationException | RuntimeException e) {
                // 읽지 못한 칸은 뺀다
            }
        }
        return out;
    }

    /** HarborId 또는 HarborId 하나만 든 레코드 (FishId · QuestId · NpcId …). */
    static @Nullable HarborId idOf(@Nullable Object v) {
        if (v instanceof HarborId id) {
            return id;
        }
        if (v instanceof Record r) {
            RecordComponent[] parts = r.getClass().getRecordComponents();
            if (parts.length == 1 && parts[0].getType() == HarborId.class) {
                try {
                    Method accessor = parts[0].getAccessor();
                    accessor.setAccessible(true);
                    return (HarborId) accessor.invoke(r);
                } catch (ReflectiveOperationException | RuntimeException e) {
                    return null;
                }
            }
        }
        return null;
    }

    private static Map<String, Object> location(Location l) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (l.getWorld() != null) {
            out.put("world", l.getWorld().getName());
        }
        out.put("x", Math.round(l.getX() * 10) / 10.0);
        out.put("y", Math.round(l.getY() * 10) / 10.0);
        out.put("z", Math.round(l.getZ() * 10) / 10.0);
        return out;
    }

    private static Map<String, Object> item(ItemStack i) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("material", i.getType().getKey().toString());
        out.put("amount", i.getAmount());
        return out;
    }

    // ------------------------------------------------------------------ getter 찾기

    private static List<Getter> scan(Class<?> type) {
        Map<String, Getter> found = new LinkedHashMap<>();
        for (Class<?> c = type;
                c != null && c != Event.class && c != Object.class;
                c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                int mod = m.getModifiers();
                if (!Modifier.isPublic(mod)
                        || Modifier.isStatic(mod)
                        || m.getParameterCount() != 0
                        || m.getReturnType() == void.class
                        || m.isSynthetic()
                        || m.isBridge()
                        || SKIP.contains(m.getName())
                        || m.getName().startsWith("set")) {
                    continue;
                }
                String name = propertyName(m.getName());
                if (!found.containsKey(name)) {
                    m.setAccessible(true);
                    found.put(name, new Getter(name, m));
                }
            }
        }
        List<Getter> list = new ArrayList<>(found.values());
        list.sort(Comparator.comparing(Getter::name));
        return List.copyOf(list);
    }

    /** getFooBar → foo_bar, isFoo → foo, fooBar → foo_bar. */
    static String propertyName(String method) {
        String base = method;
        if (base.length() > 3 && base.startsWith("get") && Character.isUpperCase(base.charAt(3))) {
            base = base.substring(3);
        } else if (base.length() > 2
                && base.startsWith("is")
                && Character.isUpperCase(base.charAt(2))) {
            base = base.substring(2);
        }
        return snake(base);
    }

    static String snake(String name) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (Character.isUpperCase(c)) {
                if (i > 0 && !Character.isUpperCase(name.charAt(i - 1))) {
                    b.append('_');
                }
                b.append(Character.toLowerCase(c));
            } else {
                b.append(c);
            }
        }
        return b.toString();
    }
}
