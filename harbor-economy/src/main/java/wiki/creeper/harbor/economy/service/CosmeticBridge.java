package wiki.creeper.harbor.economy.service;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import org.bukkit.Bukkit;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.jspecify.annotations.Nullable;

/**
 * Removes a cosmetic rod skin before an item leaves its owner through Economy (auction listing,
 * docs 20 12.4: "on auction listing, trading or pickup by someone else Economy reverts it with
 * CosmeticService.stripSkin"). Shop is not a compile dependency of Economy (it would close a Gradle
 * cycle through Collection and World), so the Shop CosmeticService is looked up by class name in
 * the ServicesManager and called through a method handle. Without Shop this does nothing. Main
 * thread.
 */
final class CosmeticBridge {

    private static final String SERVICE = "wiki.creeper.harbor.shop.api.CosmeticService";

    private CosmeticBridge() {}

    /** True if a skin was removed. */
    static boolean stripSkin(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        for (Class<?> type : Bukkit.getServicesManager().getKnownServices()) {
            if (!type.getName().equals(SERVICE)) {
                continue;
            }
            RegisteredServiceProvider<?> rsp = Bukkit.getServicesManager().getRegistration(type);
            Object provider = rsp == null ? null : rsp.getProvider();
            MethodHandle strip = provider == null ? null : handle(type);
            if (provider == null || strip == null) {
                return false;
            }
            try {
                return (boolean) strip.invoke(provider, stack);
            } catch (Throwable e) {
                Bukkit.getLogger().warning("코스메틱 스킨 제거 실패: " + String.valueOf(e.getMessage()));
                return false;
            }
        }
        return false;
    }

    private static @Nullable MethodHandle handle(Class<?> type) {
        try {
            return MethodHandles.publicLookup()
                    .findVirtual(
                            type,
                            "stripSkin",
                            MethodType.methodType(boolean.class, ItemStack.class));
        } catch (NoSuchMethodException | IllegalAccessException e) {
            return null;
        }
    }
}
