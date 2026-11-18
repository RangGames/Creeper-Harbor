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
 * 서 있는 NPC 하나 = 모습 + 클릭 판정(Interaction) + 이름 Text Display(변형 수만큼) + 퀘스트 표시 Text Display 4개. 모두 저장하지
 * 않는 엔티티다.
 *
 * @param names 0번은 기본 이름, 1번부터 name_variants 순서. 변형이 있으면 모두 기본 숨김이고 플레이어마다 하나만 보인다
 * @param markers 기본 숨김. 플레이어마다 우선순위가 가장 높은 하나만 보인다
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
