package wiki.creeper.harbor.shop.cosmetic;

import java.util.Optional;
import net.kyori.adventure.sound.Sound;
import org.bukkit.Location;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.core.api.HarborSounds;
import wiki.creeper.harbor.fishing.api.CatchPresentationOverride;
import wiki.creeper.harbor.shop.ShopRuntime;
import wiki.creeper.harbor.shop.content.CatchFxPreset;
import wiki.creeper.harbor.shop.content.CosmeticDef;
import wiki.creeper.harbor.shop.content.ShopSettings;

/**
 * Catch effect cosmetic (Fishing CatchPresentationOverride implementation, docs 20 12.4). Plays the
 * light beam, fireworks and sound in the equipped catch_fx's colors and shape instead. Intensity
 * (beam height, fireworks or not) uses the per-grade values as-is (shop.yml catch_fx, same values
 * as the Fishing grade presets), and follows the player's effect intensity setting (0 minimal · 1
 * normal · 2 maximum). Chat text, announcements and titles are still sent by Fishing.
 */
public final class CatchFxPresenter implements CatchPresentationOverride, Listener {

    private static final int VIEW_RADIUS = 48;

    private final ShopRuntime rt;

    public CatchFxPresenter(ShopRuntime rt) {
        this.rt = rt;
    }

    @Override
    public boolean present(Player player, CatchPresentation info) {
        HarborId equipped =
                rt.profile(player.getUniqueId()).equipped().get(CosmeticDef.Category.CATCH_FX);
        if (equipped == null) {
            return false;
        }
        Optional<CatchFxPreset> preset =
                rt.cosmetic(equipped)
                        .map(CosmeticDef::preset)
                        .flatMap(
                                id ->
                                        id == null
                                                ? Optional.empty()
                                                : rt.snapshot().get(CatchFxPreset.TYPE, id));
        if (preset.isEmpty()) {
            return false;
        }
        CatchFxPreset fx = preset.get();
        ShopSettings.FxStrength strength = rt.settings().fx(info.fish().grade());
        Location at = info.at();
        Fx.spawn(fx.splashParticle(), fx.pillarColors().getFirst(), at, 12, 0.4, VIEW_RADIUS);
        // Fishing plays the reel-in and grade catch sounds. The cosmetic sound (harbor: or vanilla)
        // is added only if present
        HarborSounds.playAt(
                at, fx.sound(), HarborSounds.Options.DEFAULT.withSource(Sound.Source.PLAYER));
        if (info.intensity() >= 1 && strength.pillarHeight() > 0) {
            for (int i = 0; i < strength.pillarHeight() * 2; i++) {
                String color = fx.pillarColors().get(i % fx.pillarColors().size());
                Fx.spawn(
                        fx.pillarParticle(),
                        color,
                        at.clone().add(0, 0.5 + i * 0.5, 0),
                        3,
                        0.05,
                        VIEW_RADIUS);
            }
        }
        if (info.intensity() >= 2 && strength.firework()) {
            Fx.firework(
                    at.clone().add(0, Math.max(2, strength.pillarHeight()), 0),
                    fx.fireworkType(),
                    fx.fireworkColors());
        }
        return true;
    }

    /** Effect fireworks deal no damage. */
    @EventHandler(ignoreCancelled = true)
    public void onFireworkDamage(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Firework fw
                && fw.getPersistentDataContainer().has(Fx.FX_TAG)) {
            event.setCancelled(true);
        }
    }
}
