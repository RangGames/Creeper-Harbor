package wiki.creeper.harbor.common.content.type;

import java.util.ArrayList;
import java.util.List;
import wiki.creeper.harbor.common.content.ParseContext;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.common.id.ItemId;
import wiki.creeper.harbor.common.id.NpcId;
import wiki.creeper.harbor.common.id.QuestId;
import wiki.creeper.harbor.common.id.RegionId;
import wiki.creeper.harbor.common.yaml.YamlNode;

/** ID 목록을 읽으면서 참조를 등록하는 도우미. */
final class Refs {

    private Refs() {}

    static List<ItemId> items(List<YamlNode> nodes, ParseContext ctx) {
        List<ItemId> result = new ArrayList<>();
        for (YamlNode n : nodes) {
            n.asId(ItemId.PREFIX)
                    .ifPresent(
                            id -> {
                                result.add(new ItemId(id));
                                ctx.ref("item", id, n.pos());
                            });
        }
        return List.copyOf(result);
    }

    static List<RegionId> regions(List<YamlNode> nodes, ParseContext ctx) {
        List<RegionId> result = new ArrayList<>();
        for (YamlNode n : nodes) {
            n.asId(RegionId.PREFIX)
                    .ifPresent(
                            id -> {
                                result.add(new RegionId(id));
                                ctx.ref("region", id, n.pos());
                            });
        }
        return List.copyOf(result);
    }

    static List<NpcId> npcs(List<YamlNode> nodes, ParseContext ctx) {
        List<NpcId> result = new ArrayList<>();
        for (YamlNode n : nodes) {
            n.asId(NpcId.PREFIX)
                    .ifPresent(
                            id -> {
                                result.add(new NpcId(id));
                                ctx.ref("npc", id, n.pos());
                            });
        }
        return List.copyOf(result);
    }

    static QuestId quest(YamlNode n, ParseContext ctx) {
        HarborId id = n.asId(QuestId.PREFIX).orElse(HarborId.parse("quest/invalid"));
        ctx.ref("quest", id, n.pos());
        return new QuestId(id);
    }
}
