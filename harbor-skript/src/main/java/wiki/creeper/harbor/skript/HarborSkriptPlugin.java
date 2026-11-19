package wiki.creeper.harbor.skript;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Harbor-Skript: Skript 애드온 (추가 제안). Skript가 켜져 있을 때만 문법을 등록하고, 없으면 아무것도 하지 않는다. Skript 클래스는 {@link
 * SkriptSyntax}에만 있어서 Skript가 없으면 불러오지 않는다.
 *
 * <p>문법은 Harbor API를 거친다: 골드·물비늘은 원장(SCRIPT_GRANT · SCRIPT_TAKE)으로, 아이템은 ItemService로 만든다. 스크립트가 잔액
 * 열이나 아이템 태그를 직접 고치지 않게 하기 위해서다.
 */
public final class HarborSkriptPlugin extends JavaPlugin {

    @Override
    public void onEnable() {
        Plugin skript = Bukkit.getPluginManager().getPlugin("Skript");
        if (skript == null || !skript.isEnabled()) {
            getSLF4JLogger().info("Skript가 없어서 Harbor 문법을 등록하지 않습니다");
            return;
        }
        try {
            int count = SkriptSyntax.register(this);
            getSLF4JLogger().info("Skript에 Harbor 문법 {}개를 등록했습니다", count);
        } catch (LinkageError | RuntimeException e) {
            getSLF4JLogger().error("Skript 문법 등록 실패 (Skript 버전 확인)", e);
        }
    }
}
