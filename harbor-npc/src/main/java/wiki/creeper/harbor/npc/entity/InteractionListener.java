package wiki.creeper.harbor.npc.entity;

import io.papermc.paper.event.player.PrePlayerAttackEntityEvent;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import wiki.creeper.harbor.common.id.NpcId;
import wiki.creeper.harbor.core.api.HarborApi;
import wiki.creeper.harbor.npc.Worlds;
import wiki.creeper.harbor.npc.affinity.AffinityManager;
import wiki.creeper.harbor.npc.api.event.NpcInteractEvent;
import wiki.creeper.harbor.npc.content.NpcBalance;
import wiki.creeper.harbor.npc.content.NpcDef;
import wiki.creeper.harbor.npc.dialog.DialogManager;

/**
 * NPC 클릭 (docs 20 5.4 상호작용 1~4단계): 10틱 중복 클릭 무시 → interact_radius·상태 검사 → NpcInteractEvent → 하루 첫
 * 대화 호감도 → 대화 열기.
 */
public final class InteractionListener implements Listener {

    private final HarborApi api;
    private final NpcManager npcs;
    private final DialogManager dialogs;
    private final AffinityManager affinity;
    private final Map<UUID, Integer> lastClick = new HashMap<>();

    public InteractionListener(
            HarborApi api, NpcManager npcs, DialogManager dialogs, AffinityManager affinity) {
        this.api = api;
        this.npcs = npcs;
        this.dialogs = dialogs;
        this.affinity = affinity;
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onInteract(PlayerInteractEntityEvent event) {
        Optional<NpcId> npc = npcs.npcOf(event.getRightClicked());
        if (npc.isEmpty()) {
            return;
        }
        event.setCancelled(true);
        if (event.getHand() == EquipmentSlot.HAND) {
            click(event.getPlayer(), npc.get(), true);
        }
    }

    /** 갑옷 거치대식 정밀 클릭도 같은 NPC 클릭으로 본다 (중복은 10틱 제한으로 걸러짐). */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onInteractAt(PlayerInteractAtEntityEvent event) {
        if (npcs.npcOf(event.getRightClicked()).isPresent()) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onAttack(PrePlayerAttackEntityEvent event) {
        Optional<NpcId> npc = npcs.npcOf(event.getAttacked());
        if (npc.isEmpty()) {
            return;
        }
        event.setCancelled(true);
        click(event.getPlayer(), npc.get(), false);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        lastClick.remove(event.getPlayer().getUniqueId());
    }

    private void click(Player player, NpcId id, boolean right) {
        Optional<NpcDef> def = npcs.def(id);
        if (def.isEmpty()) {
            return;
        }
        NpcBalance balance = npcs.balance();
        int tick = Bukkit.getCurrentTick();
        Integer last = lastClick.get(player.getUniqueId());
        if (last != null && tick - last < balance.clickCooldownTicks()) {
            return;
        }
        lastClick.put(player.getUniqueId(), tick);
        Location home = npcs.home(id);
        double r = def.get().interactRadius();
        if (home == null
                || !home.getWorld().equals(player.getWorld())
                || home.distanceSquared(Worlds.loc(player)) > r * r) {
            return;
        }
        Optional<String> busy = npcs.busyReason(player);
        if (busy.isPresent()) {
            String key = "npc.busy." + busy.get();
            api.messages().send(player, api.messages().has(key) ? key : "npc.busy.default");
            return;
        }
        NpcInteractEvent event = new NpcInteractEvent(player, id, right);
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled()) {
            return;
        }
        affinity.greet(player, def.get());
        if (def.get().dialog() != null && !dialogs.open(player, id)) {
            api.messages().send(player, "npc.dialog.nothing");
        }
    }
}
