package wiki.creeper.harbor.fishing.rod;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.function.Predicate;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import wiki.creeper.harbor.common.ledger.LedgerError;
import wiki.creeper.harbor.common.ledger.LedgerReason;
import wiki.creeper.harbor.common.model.Currency;
import wiki.creeper.harbor.common.util.Result;
import wiki.creeper.harbor.core.api.Dialogs;
import wiki.creeper.harbor.core.api.HarborApi;
import wiki.creeper.harbor.core.api.MessageService;
import wiki.creeper.harbor.fishing.content.RodSettings;

/**
 * Rod-only enchants at the tackle shop NPC ("8. System design" 6, dialog action {@code
 * fishing:enchant}). Lists the enchant levels the rod in the main hand can still take with their
 * prices ("13. Crafting and shop prices" 5.1, a higher level costs the difference from the current
 * one), confirms, deducts gold (ledger ROD_ENCHANT) and then writes the level to the rod's PDC.
 *
 * <p>The rod is marked with a nonce before the deduction so the level lands on the same rod even if
 * it moved in the inventory meanwhile. If it is gone when the deduction commits (dropped, player
 * left), the gold is refunded with the same reason. Main thread only.
 */
public final class RodEnchanter {

    private record Offer(RodSettings.Enchant enchant, int current, int target, long price) {}

    private final HarborApi api;
    private final RodParts rods;
    private final Predicate<Player> busy;
    private final Logger log;
    private final Executor main;
    private final NamespacedKey pendingKey;
    private final Set<UUID> pending = new HashSet<>();

    /**
     * @param main main thread executor (Tasks.main)
     */
    public RodEnchanter(
            Plugin plugin,
            HarborApi api,
            RodParts rods,
            Predicate<Player> busy,
            Executor main,
            Logger log) {
        this.api = api;
        this.rods = rods;
        this.busy = busy;
        this.main = main;
        this.log = log;
        this.pendingKey = new NamespacedKey(plugin, "rod_enchant_pending");
    }

    /**
     * Opens the enchant screen. Returns the message key of the reason when it cannot open (no rod,
     * fishing), otherwise null.
     */
    public @Nullable String open(Player player) {
        MessageService msg = api.messages();
        PlayerInventory inv = player.getInventory();
        ItemStack rod = inv.getItemInMainHand();
        if (!RodParts.isRod(rod)) {
            return "fishing.rod.enchant.no_rod";
        }
        if (busy.test(player)) {
            return "fishing.rod.busy";
        }
        List<Offer> offers = new ArrayList<>();
        for (RodSettings.Enchant e : rods.settings().enchants().values()) {
            int current = Math.min(rods.level(rod, e.id()), e.maxLevel());
            for (int level = current + 1; level <= e.maxLevel(); level++) {
                offers.add(new Offer(e, current, level, e.upgradePrice(current, level)));
            }
        }
        List<Component> body = new ArrayList<>();
        body.add(
                msg.get(
                        "fishing.rod.menu.rod",
                        Placeholder.component("name", rod.effectiveName())));
        rods.enchants(rod)
                .forEach(
                        (e, level) ->
                                body.add(
                                        msg.get(
                                                "fishing.rod.lore.enchant",
                                                Placeholder.component("name", rods.enchantName(e)),
                                                Placeholder.component(
                                                        "level", rods.levelName(level)))));
        if (offers.isEmpty()) {
            body.add(msg.get("fishing.rod.enchant.maxed"));
            api.dialogs().notice(player, msg.get("fishing.rod.enchant.title"), body);
            return null;
        }
        body.add(msg.get("fishing.rod.enchant.body"));
        List<Dialogs.Choice> choices = new ArrayList<>();
        for (Offer o : offers) {
            choices.add(
                    new Dialogs.Choice(
                            msg.get(
                                    "fishing.rod.enchant.option",
                                    Placeholder.component("name", rods.enchantName(o.enchant())),
                                    Placeholder.component("level", rods.levelName(o.target())),
                                    Placeholder.unparsed("price", gold(o.price()))),
                            effect(o.enchant(), o.target())));
        }
        choices.add(new Dialogs.Choice(msg.get("fishing.rod.menu.close"), Component.empty()));
        int heldSlot = inv.getHeldItemSlot();
        ItemStack snapshot = rod.clone();
        api.dialogs()
                .choose(
                        player,
                        msg.get("fishing.rod.enchant.title"),
                        body,
                        choices,
                        1,
                        index -> {
                            if (index >= 0 && index < offers.size() && player.isOnline()) {
                                confirm(player, heldSlot, snapshot, offers.get(index));
                            }
                        });
        return null;
    }

    private void confirm(Player player, int heldSlot, ItemStack snapshot, Offer offer) {
        MessageService msg = api.messages();
        TagResolver tags =
                TagResolver.resolver(
                        Placeholder.component("rod", snapshot.effectiveName()),
                        Placeholder.component("name", rods.enchantName(offer.enchant())),
                        Placeholder.component("level", rods.levelName(offer.target())),
                        Placeholder.unparsed("price", gold(offer.price())));
        api.dialogs()
                .confirm(
                        player,
                        msg.get("fishing.rod.enchant.confirm_title"),
                        List.of(
                                msg.get("fishing.rod.enchant.confirm_body", tags),
                                effect(offer.enchant(), offer.target())),
                        msg.get("fishing.rod.enchant.yes"),
                        msg.get("fishing.rod.enchant.no"),
                        yes -> {
                            if (yes && player.isOnline()) {
                                buy(player, heldSlot, snapshot, offer, tags);
                            }
                        });
    }

    private void buy(
            Player player, int heldSlot, ItemStack snapshot, Offer offer, TagResolver tags) {
        MessageService msg = api.messages();
        PlayerInventory inv = player.getInventory();
        ItemStack rod = inv.getItemInMainHand();
        if (inv.getHeldItemSlot() != heldSlot
                || !rod.isSimilar(snapshot)
                || rods.level(rod, offer.enchant().id()) != offer.current()) {
            msg.send(player, "fishing.rod.changed");
            return;
        }
        if (busy.test(player)) {
            msg.send(player, "fishing.rod.busy");
            return;
        }
        UUID uuid = player.getUniqueId();
        if (!pending.add(uuid)) {
            msg.send(player, "fishing.rod.enchant.pending");
            return;
        }
        String nonce = UUID.randomUUID().toString();
        rod.editPersistentDataContainer(
                pdc -> pdc.set(pendingKey, PersistentDataType.STRING, nonce));
        inv.setItemInMainHand(rod);
        String ref = "rodench:" + uuid + ":" + nonce;
        var _ =
                api.currency()
                        .take(uuid, Currency.GOLD, offer.price(), LedgerReason.ROD_ENCHANT, ref)
                        .whenCompleteAsync(
                                (result, error) -> {
                                    pending.remove(uuid);
                                    if (error != null) {
                                        log.warn(
                                                "낚싯대 인챈트 결제 실패 ({}): {}",
                                                uuid,
                                                String.valueOf(error));
                                    }
                                    finish(uuid, nonce, ref, offer, tags, result, error == null);
                                },
                                main);
    }

    /** After the deduction (main thread): applies the level to the marked rod or refunds. */
    private void finish(
            UUID uuid,
            String nonce,
            String ref,
            Offer offer,
            TagResolver tags,
            @Nullable Result<Long, LedgerError> result,
            boolean completed) {
        Player player = Bukkit.getPlayer(uuid);
        int slot = player == null ? -1 : markedSlot(player, nonce);
        boolean paid = completed && result instanceof Result.Ok<Long, LedgerError>;
        if (player != null && slot >= 0) {
            PlayerInventory inv = player.getInventory();
            ItemStack rod = inv.getItem(slot);
            if (rod != null) {
                rod.editPersistentDataContainer(pdc -> pdc.remove(pendingKey));
                if (paid) {
                    rods.setLevel(rod, offer.enchant().id(), offer.target());
                }
                inv.setItem(slot, rod);
            }
        }
        if (!paid) {
            if (player != null) {
                boolean noGold =
                        result instanceof Result.Err<Long, LedgerError> err
                                && err.error() == LedgerError.INSUFFICIENT_FUNDS;
                api.messages()
                        .send(
                                player,
                                noGold
                                        ? "fishing.rod.enchant.no_gold"
                                        : "fishing.rod.enchant.failed");
            }
            return;
        }
        if (player == null || slot < 0) {
            var _ =
                    api.currency()
                            .give(
                                    uuid,
                                    Currency.GOLD,
                                    offer.price(),
                                    LedgerReason.ROD_ENCHANT,
                                    ref + ":refund");
            if (player != null) {
                api.messages().send(player, "fishing.rod.enchant.rod_gone");
            }
            return;
        }
        api.messages().send(player, "fishing.rod.enchant.done", tags);
    }

    /** Inventory slot of the rod carrying the purchase nonce (main hand first), or -1. */
    private int markedSlot(Player player, String nonce) {
        PlayerInventory inv = player.getInventory();
        if (marked(inv.getItemInMainHand(), nonce)) {
            return inv.getHeldItemSlot();
        }
        ItemStack[] contents = inv.getContents();
        for (int i = 0; i < contents.length; i++) {
            ItemStack stack = contents[i];
            if (stack != null && marked(stack, nonce)) {
                return i;
            }
        }
        return -1;
    }

    private boolean marked(ItemStack stack, String nonce) {
        return RodParts.isRod(stack)
                && nonce.equals(
                        stack.getPersistentDataContainer()
                                .get(pendingKey, PersistentDataType.STRING));
    }

    private Component effect(RodSettings.Enchant e, int level) {
        double v = e.value(level);
        String percent = String.format(Locale.ROOT, "%+.0f", v * 100).replace("-", "−");
        String value =
                (v == Math.rint(v)
                                ? String.format(Locale.ROOT, "%+d", (long) v)
                                : String.format(Locale.ROOT, "%+.2f", v))
                        .replace("-", "−");
        return api.messages()
                .get(
                        "fishing.rod.enchant.desc." + e.id(),
                        Placeholder.unparsed("percent", percent),
                        Placeholder.unparsed("value", value));
    }

    private static String gold(long amount) {
        return String.format(Locale.ROOT, "%,d", amount);
    }
}
