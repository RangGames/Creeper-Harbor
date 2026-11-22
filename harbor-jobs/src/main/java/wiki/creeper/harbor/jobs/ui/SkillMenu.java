package wiki.creeper.harbor.jobs.ui;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.ItemLore;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import wiki.creeper.harbor.common.model.Currency;
import wiki.creeper.harbor.common.model.JobLine;
import wiki.creeper.harbor.core.api.HarborApi;
import wiki.creeper.harbor.core.api.HarborPlayer;
import wiki.creeper.harbor.core.api.MessageService;
import wiki.creeper.harbor.core.gui.PagedMenu;
import wiki.creeper.harbor.jobs.content.SkillNode;
import wiki.creeper.harbor.jobs.data.JobsData;
import wiki.creeper.harbor.jobs.service.InvestResult;
import wiki.creeper.harbor.jobs.service.JobsManager;

/**
 * Skill tree screen (/스킬, docs 20 2.4). Shows nodes in a chest GUI; clicking a node opens a Dialog
 * to confirm its effect and cost before investing. Custom font node art will switch to icon models
 * once the resource pack is ready (vanilla items for now: invested = enchanted book, available =
 * book, locked = gray dye).
 */
public final class SkillMenu {

    private final HarborApi api;
    private final JobsManager jobs;

    public SkillMenu(HarborApi api, JobsManager jobs) {
        this.api = api;
        this.jobs = jobs;
    }

    public void open(Player player, int page) {
        HarborPlayer hp = api.players().get(player);
        JobLine line = hp.jobLine().orElse(null);
        List<SkillNode> visible =
                jobs.nodes().values().stream()
                        .filter(
                                n ->
                                        n.kind() == SkillNode.Kind.COMMON
                                                || n.line() == line
                                                || (n.kind() == SkillNode.Kind.LINE_SECOND
                                                        && hp.jobTier().level() >= 3))
                        .sorted(
                                Comparator.comparing(SkillNode::kind)
                                        .thenComparing(SkillNode::tree)
                                        .thenComparingInt(SkillNode::tier)
                                        .thenComparing(n -> n.id().toString()))
                        .toList();
        MessageService msg = api.messages();
        PagedMenu<SkillNode> menu =
                new PagedMenu<>(
                        msg.get(
                                "jobs.skill.title",
                                Placeholder.unparsed(
                                        "mulbineul",
                                        String.valueOf(hp.balance(Currency.MULBINEUL)))),
                        6,
                        visible,
                        node -> new PagedMenu.Entry(icon(hp, node), e -> confirm(player, node)),
                        named(Material.ARROW, msg.get("jobs.skill.previous")),
                        named(Material.ARROW, msg.get("jobs.skill.next")));
        menu.show(player, page);
    }

    private ItemStack icon(HarborPlayer hp, SkillNode node) {
        JobsData data = jobs.data(hp);
        int rank = data.rank(node.id());
        InvestResult check = jobs.checkInvest(hp, node.id());
        Material material =
                rank > 0
                        ? Material.ENCHANTED_BOOK
                        : check == InvestResult.OK || check == InvestResult.NOT_ENOUGH_MULBINEUL
                                ? Material.BOOK
                                : Material.GRAY_DYE;
        MessageService msg = api.messages();
        List<Component> lore = new ArrayList<>();
        lore.add(
                msg.get(
                        "jobs.skill.lore.rank",
                        Placeholder.unparsed("rank", String.valueOf(rank)),
                        Placeholder.unparsed("max", String.valueOf(node.maxRank())),
                        Placeholder.unparsed("cost", String.valueOf(node.cost())),
                        Placeholder.unparsed("tree", node.tree()),
                        Placeholder.unparsed("tier", String.valueOf(node.tier()))));
        for (SkillNode.Effect effect : node.effects()) {
            lore.add(
                    msg.get(
                            "jobs.skill.lore.effect",
                            Placeholder.unparsed("stat", effect.stat().key()),
                            Placeholder.unparsed(
                                    "value", JobsManager.signed(effect.valuePerRank()))));
        }
        if (node.isActive()) {
            lore.add(
                    msg.get(
                            "jobs.skill.lore.active",
                            Placeholder.unparsed("minutes", String.valueOf(node.cooldown() / 60))));
        }
        if (check != InvestResult.OK && rank < node.maxRank()) {
            lore.add(msg.get(check.messageKey()));
        }
        ItemStack stack = named(material, Component.text(node.name()));
        stack.setData(
                DataComponentTypes.LORE,
                ItemLore.lore(
                        lore.stream()
                                .map(
                                        c ->
                                                c.decorationIfAbsent(
                                                        TextDecoration.ITALIC,
                                                        TextDecoration.State.FALSE))
                                .toList()));
        return stack;
    }

    private void confirm(Player player, SkillNode node) {
        MessageService msg = api.messages();
        player.closeInventory();
        api.dialogs()
                .confirm(
                        player,
                        Component.text(node.name()),
                        List.of(
                                msg.get(
                                        "jobs.skill.confirm",
                                        Placeholder.unparsed("cost", String.valueOf(node.cost())))),
                        msg.get("jobs.skill.yes"),
                        msg.get("jobs.skill.no"),
                        ok -> {
                            if (!ok) {
                                open(player, 0);
                                return;
                            }
                            var _ =
                                    jobs.invest(player, node.id())
                                            .thenAccept(
                                                    result -> {
                                                        msg.send(player, result.messageKey());
                                                        if (player.isOnline()) {
                                                            open(player, 0);
                                                        }
                                                    });
                        });
    }

    private static ItemStack named(Material material, Component name) {
        ItemStack stack = ItemStack.of(material);
        stack.setData(DataComponentTypes.ITEM_NAME, name);
        return stack;
    }
}
