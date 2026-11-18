package wiki.creeper.harbor.shop.cosmetic;

import java.util.Optional;
import org.bukkit.Location;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.fishing.api.CatchPresentationOverride;
import wiki.creeper.harbor.shop.ShopRuntime;
import wiki.creeper.harbor.shop.content.CatchFxPreset;
import wiki.creeper.harbor.shop.content.CosmeticDef;
import wiki.creeper.harbor.shop.content.ShopSettings;

/**
 * 포획 연출 코스메틱 (Fishing CatchPresentationOverride 구현, docs 20 12.4). 장착한 catch_fx의 색·모양으로 빛기둥·폭죽·효과음을
 * 대신 재생한다. 강도(빛기둥 높이·폭죽 여부)는 등급 값(shop.yml catch_fx, Fishing 등급 프리셋과 같은 값)을 그대로 쓰고, 플레이어 연출 강도 설정(0
 * 최소 · 1 보통 · 2 최대)도 따른다. 채팅 문구·공지·타이틀은 Fishing이 그대로 보낸다.
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
        String sound = fx.sound();
        at.getWorld()
                .playSound(
                        at,
                        sound != null ? sound : "minecraft:entity.player.splash",
                        SoundCategory.PLAYERS,
                        1f,
                        1f);
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

    /** 연출용 폭죽은 피해를 주지 않는다. */
    @EventHandler(ignoreCancelled = true)
    public void onFireworkDamage(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Firework fw
                && fw.getPersistentDataContainer().has(Fx.FX_TAG)) {
            event.setCancelled(true);
        }
    }
}
