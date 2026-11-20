package wiki.creeper.harbor.boss.encounter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import wiki.creeper.harbor.boss.api.Contribution;
import wiki.creeper.harbor.boss.api.EncounterState;
import wiki.creeper.harbor.core.api.HarborApi;

/** 종료 연출: 결과 화면 Dialog(순위·기여도)와 클리어 컷씬. 메인 스레드 전용. */
final class EncounterResults {

    private final HarborApi api;

    EncounterResults(HarborApi api) {
        this.api = api;
    }

    /** 클리어 컷씬 (presentation.outro, NPC 모듈이 있을 때만). */
    void playOutro(Encounter e, EncounterState result) {
        var outro = e.def.presentation().outro();
        if (outro == null || result != EncounterState.CLEARED) {
            return;
        }
        api.service(wiki.creeper.harbor.npc.api.CutsceneService.class)
                .filter(c -> c.exists(outro))
                .ifPresent(
                        c -> {
                            List<Player> players = new ArrayList<>();
                            for (Member m : e.memberList()) {
                                Player p = Bukkit.getPlayer(m.uuid);
                                if (p != null && !m.dropped) {
                                    players.add(p);
                                }
                            }
                            var _ = c.playGroup(players, outro);
                        });
    }

    /** 결과 화면 Dialog (순위·기여도). 전리품은 지급될 때 채팅으로 따로 알린다. */
    void showResult(Encounter e, EncounterState result, List<Contribution> contributions) {
        List<Component> body = new ArrayList<>();
        body.add(
                api.messages()
                        .get(
                                "boss.result." + result.key(),
                                Placeholder.component("boss", e.bossName()),
                                Placeholder.unparsed(
                                        "time",
                                        EncounterManager.formatMs(e.fight.elapsed() * 50L))));
        int shown = 0;
        Map<UUID, String> names = new HashMap<>();
        for (Member m : e.memberList()) {
            names.put(m.uuid, m.name);
        }
        for (Contribution c : contributions) {
            if (shown++ >= 10) {
                break;
            }
            body.add(
                    api.messages()
                            .get(
                                    "boss.result.line",
                                    Placeholder.unparsed("rank", String.valueOf(c.rank())),
                                    Placeholder.unparsed(
                                            "player", names.getOrDefault(c.player(), "?")),
                                    Placeholder.component(
                                            "role",
                                            api.messages().get("boss.role." + c.role().key())),
                                    Placeholder.unparsed(
                                            "score", String.valueOf(Math.round(c.score() * 100))),
                                    Placeholder.unparsed(
                                            "damage", String.valueOf(Math.round(c.damage()))),
                                    Placeholder.unparsed(
                                            "tension", String.valueOf(Math.round(c.tension()))),
                                    Placeholder.unparsed(
                                            "repair", String.valueOf(Math.round(c.repair()))),
                                    Placeholder.unparsed(
                                            "evasion", String.valueOf(Math.round(c.evasion()))),
                                    Placeholder.unparsed("top", c.top() ? "★" : "")));
        }
        Component title =
                api.messages()
                        .get("boss.result.title", Placeholder.component("boss", e.bossName()));
        List<Player> viewers = e.def.isWorld() ? e.viewers() : e.onlinePlayers();
        for (Player p : viewers) {
            if (e.def.isWorld() && !e.members.containsKey(p.getUniqueId())) {
                continue;
            }
            api.dialogs().notice(p, title, body);
        }
    }
}
