package wiki.creeper.harbor.quest.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import wiki.creeper.harbor.common.content.type.FishDef;
import wiki.creeper.harbor.core.api.HarborItem;
import wiki.creeper.harbor.quest.content.QuestDef;
import wiki.creeper.harbor.quest.logic.CatchFacts;
import wiki.creeper.harbor.quest.logic.DeliverPlan;

/**
 * Rechecks deliver objectives on turn-in: picks the inventory slots to hand in and reports what is
 * missing. Main thread only.
 */
final class DeliverCheck {

    private final QuestEngine engine;

    DeliverCheck(QuestEngine engine) {
        this.engine = engine;
    }

    DeliverPlan.Result plan(Player player, QuestDef def) {
        List<DeliverPlan.Slot> slots = new ArrayList<>();
        ItemStack[] contents = player.getInventory().getStorageContents();
        for (int i = 0; i < contents.length; i++) {
            ItemStack stack = contents[i];
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            Optional<HarborItem> item = engine.api.items().read(stack);
            if (item.isEmpty()) {
                continue;
            }
            CatchFacts facts;
            boolean precious;
            switch (item.get()) {
                case HarborItem.Fish fish -> {
                    Optional<FishDef> fd = engine.snapshot().get(FishDef.TYPE, fish.species().id());
                    if (fd.isEmpty()) {
                        continue;
                    }
                    facts =
                            CatchFacts.fish(
                                    fish.species(),
                                    fd.get().grade(),
                                    fd.get().region(),
                                    fish.quality().stars(),
                                    fish.sizeCm(),
                                    false);
                    precious = fish.quality().isMax() || fish.uid() != null;
                }
                case HarborItem.Plain plain -> {
                    facts = CatchFacts.item(plain.id());
                    precious = false;
                }
            }
            slots.add(new DeliverPlan.Slot(i, facts, stack.getAmount(), precious));
        }
        return DeliverPlan.plan(def.objectives(), slots);
    }

    void explainMissing(Player player, QuestDef def, DeliverPlan.Result plan) {
        engine.api
                .messages()
                .send(
                        player,
                        "quest.deliver.missing_header",
                        Placeholder.unparsed("name", def.name()));
        plan.missing()
                .forEach(
                        (key, n) ->
                                def.objective(key)
                                        .ifPresent(
                                                o ->
                                                        engine.api
                                                                .messages()
                                                                .send(
                                                                        player,
                                                                        "quest.deliver.missing_line",
                                                                        Placeholder.unparsed(
                                                                                "objective",
                                                                                engine
                                                                                        .objectiveText(
                                                                                                player,
                                                                                                def,
                                                                                                o)),
                                                                        Placeholder.unparsed(
                                                                                "missing",
                                                                                String.valueOf(
                                                                                        n)))));
    }
}
