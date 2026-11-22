package wiki.creeper.harbor.collection.display;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import wiki.creeper.harbor.collection.api.AquariumThemeOwnership;
import wiki.creeper.harbor.common.id.HarborId;

/**
 * Aquarium theme ownership check (D14, appearance only). Uses Shop's registered {@link
 * AquariumThemeOwnership} if present, otherwise looks up Shop's CosmeticService.owns(UUID,
 * HarborId) by service name and calls it (a compile dependency is impossible because Shop →
 * Collection). Without Shop, no theme is considered owned. Main thread only.
 */
public final class ShopThemes implements AquariumThemeOwnership {

    static final String COSMETIC_SERVICE = "wiki.creeper.harbor.shop.api.CosmeticService";

    private final Logger log;
    private @Nullable Object service;
    private @Nullable Method owns;
    private boolean warned;

    public ShopThemes(Logger log) {
        this.log = log;
    }

    @Override
    public boolean owns(UUID player, HarborId cosmetic) {
        AquariumThemeOwnership registered =
                Bukkit.getServicesManager().load(AquariumThemeOwnership.class);
        if (registered != null && registered != this) {
            return registered.owns(player, cosmetic);
        }
        if (!resolve()) {
            return false;
        }
        Object target = service;
        Method method = owns;
        if (target == null || method == null) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(method.invoke(target, player, cosmetic));
        } catch (IllegalAccessException | InvocationTargetException | RuntimeException e) {
            if (!warned) {
                warned = true;
                log.warn("Shop 코스메틱 보유 확인 실패: {}", String.valueOf(e.getMessage()));
            }
            return false;
        }
    }

    private boolean resolve() {
        if (service != null && owns != null) {
            return true;
        }
        for (Class<?> type : Bukkit.getServicesManager().getKnownServices()) {
            if (!type.getName().equals(COSMETIC_SERVICE)) {
                continue;
            }
            Object impl = Bukkit.getServicesManager().load(type);
            if (impl == null) {
                return false;
            }
            try {
                owns = type.getMethod("owns", UUID.class, HarborId.class);
                service = impl;
                return true;
            } catch (NoSuchMethodException e) {
                log.warn("Shop CosmeticService.owns(UUID, HarborId)가 없습니다");
                return false;
            }
        }
        return false;
    }
}
