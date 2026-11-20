package wiki.creeper.harbor.quest.service;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.content.ContentSnapshot;
import wiki.creeper.harbor.common.content.type.FishDef;
import wiki.creeper.harbor.common.content.type.ItemDef;
import wiki.creeper.harbor.common.content.type.RegionDef;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.common.id.NpcId;
import wiki.creeper.harbor.quest.content.Objective;
import wiki.creeper.harbor.quest.content.QuestDef;

/** 사람이 읽는 목표·대상 이름 (액션바·목록·GUI). 메인 스레드 전용 (NPC 이름 변형을 읽는다). */
final class QuestTexts {

    private final QuestEngine engine;

    QuestTexts(QuestEngine engine) {
        this.engine = engine;
    }

    static String plain(Component c) {
        return PlainTextComponentSerializer.plainText().serialize(c);
    }

    /** 목표 문구: text가 있으면 그것, 없으면 quest.objective.<유형> 키에 대상 이름·수량. */
    String objectiveText(Player viewer, QuestDef def, Objective o) {
        String text = o.text();
        if (text != null) {
            return text;
        }
        String target = targetName(viewer, def, o);
        return plain(
                engine.api
                        .messages()
                        .get(
                                "quest.objective." + o.type(),
                                Placeholder.unparsed("target", target),
                                Placeholder.unparsed("count", String.valueOf(o.count()))));
    }

    String targetName(Player viewer, QuestDef def, Objective o) {
        var t = o.target();
        var messages = engine.api.messages();
        return switch (t.kind()) {
            case ANY -> {
                if (o.type().equals("talk") || o.type().equals("deliver")) {
                    yield QuestEngine.npcOf(def, o)
                            .map(n -> plain(engine.deps.npcs().displayName(viewer, n)))
                            .orElse("");
                }
                yield plain(messages.get("quest.target.any"));
            }
            case GRADE -> {
                var grade = t.grade();
                String g = grade == null ? "" : plain(messages.get("quest.grade." + grade.key()));
                yield t.orAbove()
                        ? plain(
                                messages.get(
                                        "quest.target.grade_above",
                                        Placeholder.unparsed("grade", g)))
                        : g;
            }
            case REGION -> {
                HarborId id = t.id();
                yield id == null
                        ? t.raw()
                        : engine.snapshot()
                                .get(RegionDef.TYPE, id)
                                .map(RegionDef::name)
                                .orElse(id.toString());
            }
            case POINT -> plain(messages.get("quest.target.point"));
            case NAME -> t.raw();
            case ID -> idName(viewer, t.id());
        };
    }

    String idName(Player viewer, @Nullable HarborId id) {
        if (id == null) {
            return "";
        }
        ContentSnapshot s = engine.snapshot();
        return switch (id.category()) {
            case "fish" -> s.get(FishDef.TYPE, id).map(FishDef::name).orElse(id.toString());
            case "item" -> s.get(ItemDef.TYPE, id).map(ItemDef::name).orElse(id.toString());
            case "npc" -> plain(engine.deps.npcs().displayName(viewer, new NpcId(id)));
            default -> id.name();
        };
    }
}
