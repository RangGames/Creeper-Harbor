package wiki.creeper.harbor.skript;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Harbor-Skript: Skript addon (proposed). Registers syntax only when Skript is enabled and does
 * nothing otherwise. Skript classes live only in {@link SkriptSyntax}, so they are not loaded
 * without Skript.
 *
 * <p>The syntax goes through the Harbor API: gold and mulbineul through the ledger (SCRIPT_GRANT ·
 * SCRIPT_TAKE), items through ItemService. This keeps scripts from editing balance columns or item
 * tags directly.
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
