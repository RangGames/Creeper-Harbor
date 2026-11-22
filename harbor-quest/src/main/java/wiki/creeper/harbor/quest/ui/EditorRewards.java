package wiki.creeper.harbor.quest.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import wiki.creeper.harbor.core.api.HarborApi;
import wiki.creeper.harbor.core.api.HarborItem;
import wiki.creeper.harbor.core.api.MessageService;
import wiki.creeper.harbor.core.gui.Menu;
import wiki.creeper.harbor.quest.editor.QuestDraft;

/** Reward fields of the quest editor's main screen (numeric rewards and held-item rewards). */
final class EditorRewards {

    private final HarborApi api;
    private final QuestEditor editor;

    EditorRewards(HarborApi api, QuestEditor editor) {
        this.api = api;
        this.editor = editor;
    }

    private MessageService msg() {
        return api.messages();
    }

    private Component text(String key, TagResolver... resolvers) {
        return editor.text(key, resolvers);
    }

    void number(
            Menu menu,
            int slot,
            Player admin,
            QuestDraft d,
            Material icon,
            String key,
            int small,
            int big) {
        int value = QuestDraft.integer(d.section("rewards"), key, 0);
        menu.button(
                slot,
                MenuIcons.icon(
                        icon,
                        text("reward." + key, Placeholder.unparsed("value", String.valueOf(value))),
                        List.of(
                                text(
                                        "hint.number",
                                        Placeholder.unparsed("small", String.valueOf(small)),
                                        Placeholder.unparsed("big", String.valueOf(big)))),
                        false),
                e -> {
                    d.add("rewards", key, QuestEditor.step(e.getClick(), small, big));
                    editor.main(admin, d);
                });
    }

    List<Component> itemsLore(QuestDraft d) {
        List<Component> lore = new ArrayList<>();
        for (Object o : d.list("rewards", "items")) {
            if (o instanceof Map<?, ?> m) {
                lore.add(
                        text(
                                "reward.item_line",
                                Placeholder.unparsed("item", String.valueOf(m.get("item"))),
                                Placeholder.unparsed("amount", String.valueOf(m.get("amount")))));
            }
        }
        lore.add(text("hint.items"));
        return lore;
    }

    void addHeldItem(Player admin, QuestDraft d) {
        ItemStack hand = admin.getInventory().getItemInMainHand();
        Optional<HarborItem> item = hand.isEmpty() ? Optional.empty() : api.items().read(hand);
        if (item.isEmpty() || !(item.get() instanceof HarborItem.Plain plain)) {
            msg().send(admin, "quest.editor.hold_item");
            return;
        }
        Map<String, Object> entry = new java.util.LinkedHashMap<>();
        entry.put("item", plain.id().id().toString());
        entry.put("amount", String.valueOf(hand.getAmount()));
        d.list("rewards", "items").add(entry);
        d.touch();
    }
}
