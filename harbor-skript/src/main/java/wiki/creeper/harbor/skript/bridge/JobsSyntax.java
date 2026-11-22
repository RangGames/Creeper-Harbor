package wiki.creeper.harbor.skript.bridge;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import wiki.creeper.harbor.jobs.api.event.JobAdvancedEvent;
import wiki.creeper.harbor.jobs.api.event.JobLevelUpEvent;
import wiki.creeper.harbor.skript.lambda.Registrar;

/** When Harbor-Jobs is enabled: level up and job advancement events. */
public final class JobsSyntax {

    private JobsSyntax() {}

    public static void register(Registrar r) {
        r.event(
                "Harbor Level Up",
                JobLevelUpEvent.class,
                "직업 레벨이 오른 뒤 (event-offlineplayer, event-number = 새 레벨)",
                "harbor level up");
        r.value(
                JobLevelUpEvent.class,
                OfflinePlayer.class,
                e -> Bukkit.getOfflinePlayer(e.player()));
        r.value(JobLevelUpEvent.class, Number.class, JobLevelUpEvent::level);

        r.event(
                "Harbor Job Advance",
                JobAdvancedEvent.class,
                "전직한 뒤 (event-offlineplayer, event-number = 새 단계, event-string = 계열)",
                "harbor job advance");
        r.value(
                JobAdvancedEvent.class,
                OfflinePlayer.class,
                e -> Bukkit.getOfflinePlayer(e.player()));
        r.value(JobAdvancedEvent.class, Number.class, e -> e.tier().level());
        r.value(JobAdvancedEvent.class, String.class, e -> e.line().key());
    }
}
