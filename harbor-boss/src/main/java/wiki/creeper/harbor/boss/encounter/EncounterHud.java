package wiki.creeper.harbor.boss.encounter;

import java.time.Duration;
import java.util.UUID;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import wiki.creeper.harbor.boss.api.EncounterState;
import wiki.creeper.harbor.boss.content.BossDef;

/** 조우 화면 표시: 보스바·역할 힌트 액션 바와 문구 전송(개인·전체·액션 바·타이틀). 메인 스레드 전용. */
final class EncounterHud {

    private final Encounter e;

    EncounterHud(Encounter e) {
        this.e = e;
    }

    // ------------------------------------------------------------------ UI

    void update(long now) {
        var fight = e.fight;
        float progress = (float) Math.clamp(fight.hpRatio(), 0.0, 1.0);
        String key =
                switch (fight.state()) {
                    case HOOK -> "boss.bar.hook";
                    case FINAL_TUG -> "boss.bar.tug";
                    default ->
                            e.def.goal() == BossDef.Goal.CALM ? "boss.bar.calm" : "boss.bar.fight";
                };
        Component name =
                msg(
                        key,
                        Placeholder.component("boss", bossName()),
                        Placeholder.unparsed(
                                "hp", String.valueOf(Math.round(fight.hpRatio() * 100))),
                        Placeholder.unparsed(
                                "gauge", String.valueOf(Math.round(fight.hookGauge() * 100))),
                        Placeholder.unparsed("hull", String.valueOf(fight.hull())),
                        Placeholder.unparsed("max", String.valueOf(fight.hullMax())),
                        Placeholder.unparsed("time", time(fight.remainingTicks())));
        float bar = fight.state() == EncounterState.HOOK ? (float) fight.hookGauge() : progress;
        for (Member m : e.members.values()) {
            BossBar b = m.bar;
            if (b != null && m.present()) {
                b.name(name);
                b.progress(Math.clamp(bar, 0f, 1f));
            }
        }
        if (e.def.isWorld()) {
            e.mgr.worldUi(e, name, progress);
        }
        if (fight.state() == EncounterState.COUNTER && now % 20 == 0) {
            for (Member m : e.members.values()) {
                Player p = Bukkit.getPlayer(m.uuid);
                if (p == null || !m.present() || m.session != null) {
                    continue;
                }
                String hint =
                        switch (m.role) {
                            case HARPOONER ->
                                    e.combat.weakExposed()
                                            ? "boss.hint.harpoon_weak"
                                            : "boss.hint.harpoon";
                            case CAPTAIN -> "boss.hint.captain";
                            case MECHANIC -> "boss.hint.mechanic";
                            case QUARTERMASTER -> "boss.hint.quartermaster";
                            case ANCHOR -> "boss.hint.anchor";
                        };
                e.mgr.api()
                        .ui()
                        .actionBar(
                                p,
                                msg(
                                        hint,
                                        Placeholder.unparsed("hull", String.valueOf(fight.hull())),
                                        Placeholder.unparsed(
                                                "max", String.valueOf(fight.hullMax()))));
            }
        }
        // 파손 지점 연기
        if (now % 10 == 0) {
            e.stations.smoke();
        }
    }

    private static String time(long ticks) {
        long s = ticks / 20;
        return String.format("%d:%02d", s / 60, s % 60);
    }

    // ------------------------------------------------------------------ 문구

    Component bossName() {
        return MiniMessage.miniMessage().deserialize(e.def.name());
    }

    Component msg(String key, TagResolver... resolvers) {
        return e.mgr.api().messages().get(key, resolvers);
    }

    void send(UUID player, String key, TagResolver... resolvers) {
        Player p = Bukkit.getPlayer(player);
        if (p != null) {
            e.mgr.api().messages().send(p, key, resolvers);
        }
    }

    void broadcast(String key, TagResolver... resolvers) {
        Component c = msg(key, resolvers);
        for (Player p : e.def.isWorld() ? e.viewers() : e.onlinePlayers()) {
            p.sendMessage(c);
        }
    }

    void broadcastBar(String key, TagResolver... resolvers) {
        Component c = msg(key, resolvers);
        for (Player p : e.def.isWorld() ? e.viewers() : e.onlinePlayers()) {
            e.mgr.api().ui().actionBar(p, c);
        }
    }

    void titleAll(String title, String subtitle) {
        Component t = msg(title, Placeholder.component("boss", bossName()));
        Component s = msg(subtitle, Placeholder.component("boss", bossName()));
        for (Player p : e.def.isWorld() ? e.viewers() : e.onlinePlayers()) {
            e.mgr.api()
                    .ui()
                    .title(
                            p,
                            t,
                            s,
                            Duration.ofMillis(200),
                            Duration.ofSeconds(2),
                            Duration.ofMillis(400));
        }
    }
}
