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
 * 어항 테마 보유 확인 (D14, 외형만). Shop이 {@link AquariumThemeOwnership}를 등록했으면 그것을, 없으면 Shop의
 * CosmeticService.owns(UUID, HarborId)를 서비스 이름으로 찾아 부른다 (Shop → Collection 의존 때문에 컴파일 의존을 둘 수 없다).
 * Shop이 없으면 아무 테마도 보유하지 않은 것으로 본다. 메인 스레드 전용.
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
