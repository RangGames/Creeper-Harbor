package wiki.creeper.harbor.boss.encounter;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.WeatherType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import wiki.creeper.harbor.boss.api.event.BossEncounterEndEvent;
import wiki.creeper.harbor.boss.api.event.WorldBossProgressEvent;
import wiki.creeper.harbor.boss.content.BossType;

/**
 * World boss sky effects at the 75 · 50 · 25% stages (docs 20 10.2 WorldBossProgressEvent
 * "announcements and sky effects", 10.4). Viewers are the players in the arena world within
 * balance.yml boss.world.sky_radius of the arena (proposed). Each stage darkens the sky one step
 * further with personal weather and time, plus lightning effects (no damage) and green light
 * columns; everything is reset when the world boss ends. Main thread only; runs on the server
 * running the world boss (the event only fires there).
 *
 * <ul>
 *   <li>Stage 1 (75%): personal rain, three lightning flashes around the arena
 *   <li>Stage 2 (50%): plus dusk (personal time 13,000), six flashes
 *   <li>Stage 3 (25%): plus night (18,000), ten flashes and green light columns
 * </ul>
 */
public final class WorldBossSky implements Listener {

    private final EncounterManager mgr;
    private final Set<UUID> touched = new HashSet<>();

    public WorldBossSky(EncounterManager mgr) {
        this.mgr = mgr;
    }

    @EventHandler
    public void onStage(WorldBossProgressEvent event) {
        int stage = event.stage();
        if (stage < 1 || stage > 3) {
            return;
        }
        Encounter e;
        try {
            e = mgr.encounter(UUID.fromString(event.status().eventId())).orElse(null);
        } catch (IllegalArgumentException ex) {
            e = null;
        }
        if (e == null) {
            return;
        }
        Location center = e.center;
        double radius =
                mgr.api()
                        .content()
                        .snapshot()
                        .single(wiki.creeper.harbor.common.content.type.Balance.TYPE)
                        .number("boss.world.sky_radius", 192);
        double r2 = radius * radius;
        for (Player p : center.getWorld().getPlayers()) {
            if (BossItems.loc(p).distanceSquared(center) > r2) {
                continue;
            }
            touched.add(p.getUniqueId());
            p.setPlayerWeather(WeatherType.DOWNFALL);
            if (stage >= 2) {
                p.setPlayerTime(stage >= 3 ? 18_000 : 13_000, false);
            }
            p.playSound(BossItems.loc(p), Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 2f, 0.8f);
        }
        int flashes = stage == 1 ? 3 : stage == 2 ? 6 : 10;
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        double spread = mgr.balance().worldRadius();
        for (int i = 0; i < flashes; i++) {
            double angle = rnd.nextDouble(Math.PI * 2);
            double dist = spread * (0.6 + rnd.nextDouble(0.6));
            Location at = center.clone().add(Math.cos(angle) * dist, 0, Math.sin(angle) * dist);
            at.setY(center.getWorld().getHighestBlockYAt(at) + 1);
            center.getWorld().strikeLightningEffect(at);
            if (stage >= 3) {
                Particle.DustOptions green = new Particle.DustOptions(Color.fromRGB(0x5BFF8A), 3f);
                for (int y = 0; y < 40; y += 3) {
                    center.getWorld()
                            .spawnParticle(
                                    Particle.DUST,
                                    at.clone().add(0, y, 0),
                                    4,
                                    0.3,
                                    1,
                                    0.3,
                                    0,
                                    green);
                }
            }
        }
    }

    @EventHandler
    public void onEnd(BossEncounterEndEvent event) {
        if (event.type() != BossType.WORLD) {
            return;
        }
        for (UUID uuid : touched) {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null) {
                p.resetPlayerWeather();
                p.resetPlayerTime();
            }
        }
        touched.clear();
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player p = event.getPlayer();
        if (touched.remove(p.getUniqueId())) {
            p.resetPlayerWeather();
            p.resetPlayerTime();
        }
    }
}
