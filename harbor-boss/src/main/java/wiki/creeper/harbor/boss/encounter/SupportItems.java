package wiki.creeper.harbor.boss.encounter;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityRegainHealthEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import wiki.creeper.harbor.boss.content.BossBalance;
import wiki.creeper.harbor.common.id.ItemId;
import wiki.creeper.harbor.core.api.HarborApi;
import wiki.creeper.harbor.core.api.WaypointService;

/**
 * Boss fight support items ('3. item list' 7, proposed behaviour; numbers are balance.yml
 * boss.support). Right-click with the item in the main hand. Main thread only.
 *
 * <ul>
 *   <li>First-aid bandage (consumed): heals, anywhere.
 *   <li>Rescue flare (consumed, only during a boss fight): a green flare above the user, and every
 *       player in the same world within the radius who is not in that fight gets a message and a
 *       locator bar waypoint to the arena for a while.
 * </ul>
 */
public final class SupportItems implements Listener {

    static final ItemId BANDAGE = ItemId.parse("item/tool/first_aid_bandage");
    static final ItemId FLARE = ItemId.parse("item/tool/rescue_flare");
    private static final String WAYPOINT_OWNER = "rescue_flare";

    private final HarborApi api;
    private final EncounterManager mgr;
    private final Map<UUID, Long> bandageUntil = new HashMap<>();
    private final Map<UUID, Long> flareUntil = new HashMap<>();

    /** Waypoint generation per viewer (a newer flare replaces the clear timer). */
    private final Map<UUID, Integer> waypointGen = new HashMap<>();

    public SupportItems(HarborApi api, EncounterManager mgr) {
        this.api = api;
        this.mgr = mgr;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onUse(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND
                || (event.getAction() != Action.RIGHT_CLICK_AIR
                        && event.getAction() != Action.RIGHT_CLICK_BLOCK)) {
            return;
        }
        Optional<ItemId> id = mgr.items().idOf(event.getItem());
        if (id.isEmpty()) {
            return;
        }
        if (id.get().equals(BANDAGE)) {
            event.setCancelled(true);
            bandage(event.getPlayer());
        } else if (id.get().equals(FLARE)) {
            // Also stops the vanilla firework launch and elytra boost
            event.setCancelled(true);
            flare(event.getPlayer());
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        waypointGen.remove(uuid);
        long now = System.currentTimeMillis();
        bandageUntil.values().removeIf(t -> t <= now);
        flareUntil.values().removeIf(t -> t <= now);
    }

    private void bandage(Player player) {
        BossBalance bb = mgr.balance();
        UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();
        if (bandageUntil.getOrDefault(uuid, 0L) > now) {
            return;
        }
        AttributeInstance max = player.getAttribute(Attribute.MAX_HEALTH);
        double cap = max == null ? 20 : max.getValue();
        if (player.getHealth() >= cap) {
            api.messages().send(player, "boss.support.bandage_full");
            return;
        }
        ItemStack shown = player.getInventory().getItemInMainHand().clone();
        if (!mgr.items().takeOne(player, BANDAGE)) {
            return;
        }
        player.heal(bb.bandageHeal(), EntityRegainHealthEvent.RegainReason.CUSTOM);
        int ticks = bb.bandageCooldownTicks();
        bandageUntil.put(uuid, now + ticks * 50L);
        player.setCooldown(shown, ticks);
        player.getWorld()
                .playSound(BossItems.loc(player), Sound.ITEM_ARMOR_EQUIP_LEATHER, 1f, 1.2f);
        api.messages().send(player, "boss.support.bandage_used");
        api.players().saveSoon(uuid);
    }

    private void flare(Player player) {
        UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();
        if (flareUntil.getOrDefault(uuid, 0L) > now) {
            return;
        }
        Encounter e = mgr.encounterOfPlayer(uuid).orElse(null);
        if (e == null || e.finished()) {
            api.messages().send(player, "boss.support.flare_no_fight");
            return;
        }
        ItemStack shown = player.getInventory().getItemInMainHand().clone();
        if (!mgr.items().takeOne(player, FLARE)) {
            return;
        }
        BossBalance bb = mgr.balance();
        int cooldown = bb.flareCooldownTicks();
        flareUntil.put(uuid, now + cooldown * 50L);
        player.setCooldown(shown, cooldown);
        api.players().saveSoon(uuid);
        Location at = BossItems.loc(player);
        Particle.DustOptions green = new Particle.DustOptions(Color.fromRGB(0x3CFF6E), 2.5f);
        for (int y = 2; y <= 24; y += 2) {
            at.getWorld()
                    .spawnParticle(
                            Particle.DUST, at.clone().add(0, y, 0), 6, 0.2, 0.4, 0.2, 0, green);
        }
        at.getWorld()
                .spawnParticle(
                        Particle.FIREWORK, at.clone().add(0, 25, 0), 80, 1.5, 1.5, 1.5, 0.15);
        at.getWorld().playSound(at, Sound.ENTITY_FIREWORK_ROCKET_LARGE_BLAST_FAR, 4f, 0.9f);

        Location center = e.center;
        double r2 = bb.flareRadius() * bb.flareRadius();
        long ticks = bb.flareSeconds() * 20L;
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (e.isMember(p.getUniqueId())
                    || !at.getWorld().equals(p.getWorld())
                    || BossItems.loc(p).distanceSquared(at) > r2) {
                continue;
            }
            api.messages()
                    .send(
                            p,
                            "boss.support.flare_alert",
                            Placeholder.unparsed("player", player.getName()),
                            Placeholder.component("boss", e.bossName()),
                            Placeholder.unparsed("x", String.valueOf(center.getBlockX())),
                            Placeholder.unparsed("z", String.valueOf(center.getBlockZ())));
            api.waypoints()
                    .set(
                            p,
                            WAYPOINT_OWNER,
                            List.of(
                                    new WaypointService.Target(
                                            e.id.toString(),
                                            center,
                                            "#3cff6e",
                                            null,
                                            bb.flareRadius() * 2)));
            UUID viewer = p.getUniqueId();
            int gen = waypointGen.merge(viewer, 1, Integer::sum);
            var _ =
                    mgr.tasks()
                            .later(
                                    ticks,
                                    () -> {
                                        Player still = Bukkit.getPlayer(viewer);
                                        if (still != null
                                                && waypointGen.getOrDefault(viewer, 0) == gen) {
                                            api.waypoints().clear(still, WAYPOINT_OWNER);
                                        }
                                    });
        }
        e.broadcast("boss.support.flare_sent", Placeholder.unparsed("player", player.getName()));
    }
}
