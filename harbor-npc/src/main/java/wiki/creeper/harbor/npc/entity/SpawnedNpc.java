package wiki.creeper.harbor.npc.entity;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.TextDisplay;
import wiki.creeper.harbor.npc.api.QuestMarker;
import wiki.creeper.harbor.npc.content.NpcDef;

/**
 * One standing NPC = body + click hitbox (Interaction) + name Text Displays (one per variant) + 4
 * quest marker Text Displays. None of these entities are saved.
 *
 * @param names index 0 is the base name, then name_variants in order from 1. With variants, all are
 *     hidden by default and each player sees only one
 * @param markers hidden by default. Each player sees only the highest-priority one
 */
record SpawnedNpc(
        NpcDef def,
        Location home,
        NpcBody body,
        Interaction hitbox,
        List<TextDisplay> names,
        Map<QuestMarker, TextDisplay> markers) {

    SpawnedNpc {
        names = List.copyOf(names);
        markers = new EnumMap<>(markers);
    }

    List<Entity> entities() {
        List<Entity> all = new ArrayList<>();
        all.add(body.entity());
        all.add(hitbox);
        all.addAll(names);
        all.addAll(markers.values());
        return all;
    }

    void remove() {
        body.remove();
        hitbox.remove();
        names.forEach(Entity::remove);
        markers.values().forEach(Entity::remove);
    }

    long chunkKey() {
        return chunkKey(home.getBlockX() >> 4, home.getBlockZ() >> 4);
    }

    static long chunkKey(int cx, int cz) {
        return ((long) cx << 32) ^ (cz & 0xFFFFFFFFL);
    }
}
