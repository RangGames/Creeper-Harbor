package wiki.creeper.harbor.npc.entity;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.entity.Display;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.TextDisplay;
import wiki.creeper.harbor.core.api.HarborApi;
import wiki.creeper.harbor.npc.NpcKeys;
import wiki.creeper.harbor.npc.api.QuestMarker;
import wiki.creeper.harbor.npc.content.NpcBalance;
import wiki.creeper.harbor.npc.content.NpcDef;

/** Builds the entities of one spawned NPC: body, hitbox, name lines, and quest markers. */
final class SpawnedNpcFactory {

    private SpawnedNpcFactory() {}

    static SpawnedNpc create(HarborApi api, NpcDef def, Location at, NpcBalance balance) {
        String tag = def.id().toString();
        boolean hasVariants = !def.nameVariants().isEmpty();
        NpcBody body =
                MannequinBody.spawn(
                        at, def.skin(), def.pose(), m -> NpcKeys.tag(m, NpcKeys.NPC, tag));
        Interaction hitbox =
                at.getWorld()
                        .spawn(
                                at,
                                Interaction.class,
                                i -> {
                                    i.setPersistent(false);
                                    i.setInteractionWidth((float) balance.interactionWidth());
                                    i.setInteractionHeight((float) balance.interactionHeight());
                                    i.setResponsive(true);
                                    NpcKeys.tag(i, NpcKeys.NPC, tag);
                                });
        List<TextDisplay> names = new ArrayList<>();
        Location nameAt = at.clone().add(0, balance.nameOffset(), 0);
        names.add(text(nameAt, NpcManager.mini(def.name()), !hasVariants, tag));
        for (NpcDef.NameVariant v : def.nameVariants()) {
            names.add(text(nameAt, NpcManager.mini(v.name()), false, tag));
        }
        Map<QuestMarker, TextDisplay> markers = new EnumMap<>(QuestMarker.class);
        Location markerAt = at.clone().add(0, balance.markerOffset(), 0);
        for (QuestMarker m : QuestMarker.values()) {
            if (m != QuestMarker.NONE) {
                markers.put(
                        m,
                        text(
                                markerAt,
                                api.messages()
                                        .get(
                                                "npc.marker."
                                                        + m.name()
                                                                .toLowerCase(
                                                                        java.util.Locale.ROOT)),
                                false,
                                tag));
            }
        }
        return new SpawnedNpc(def, at.clone(), body, hitbox, names, markers);
    }

    private static TextDisplay text(Location at, Component text, boolean visible, String tag) {
        return at.getWorld()
                .spawn(
                        at,
                        TextDisplay.class,
                        t -> {
                            t.setPersistent(false);
                            t.text(text);
                            t.setBillboard(Display.Billboard.CENTER);
                            t.setShadowed(true);
                            t.setDefaultBackground(false);
                            t.setBackgroundColor(org.bukkit.Color.fromARGB(0, 0, 0, 0));
                            t.setVisibleByDefault(visible);
                            NpcKeys.tag(t, NpcKeys.NPC, tag);
                        });
    }
}
