package wiki.creeper.harbor.boss.encounter;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import wiki.creeper.harbor.boss.api.EncounterInfo;
import wiki.creeper.harbor.boss.data.BossRepository;
import wiki.creeper.harbor.common.redis.Channels;
import wiki.creeper.harbor.core.api.HarborApi;
import wiki.creeper.harbor.season.api.HallOfFameSubmission;
import wiki.creeper.harbor.season.api.SeasonServices;

/**
 * Fastest clear records (boss_records, Season hall of fame) and server-wide clear announcements for
 * a CLEARED encounter. Main thread only.
 */
final class ClearRecords {

    private final EncounterManager mgr;
    private final HarborApi api;

    ClearRecords(EncounterManager mgr) {
        this.mgr = mgr;
        this.api = mgr.api();
    }

    void record(Encounter e, EncounterInfo info, long durationMs, Instant clearedAt) {
        int season = SeasonServices.clock().current().value();
        List<UUID> members = info.members();
        var record =
                new wiki.creeper.harbor.boss.api.BossRecord(
                        e.def.id(), season, durationMs, e.id, members, clearedAt);
        String leaderName =
                Optional.ofNullable(Bukkit.getPlayer(e.leader)).map(Player::getName).orElse("?");
        Component name =
                members.size() <= 1
                        ? Component.text(leaderName)
                        : api.messages()
                                .get(
                                        "boss.record.party_name",
                                        Placeholder.unparsed("leader", leaderName),
                                        Placeholder.unparsed(
                                                "others", String.valueOf(members.size() - 1)));
        String plain = PlainTextComponentSerializer.plainText().serialize(name);
        var _ =
                api.database()
                        .async(c -> BossRepository.insertRecord(c, record))
                        .thenAcceptAsync(
                                rank -> {
                                    if (rank == 1) {
                                        e.broadcast(
                                                "boss.record.new_best",
                                                Placeholder.unparsed(
                                                        "time",
                                                        EncounterManager.formatMs(durationMs)));
                                    } else if (rank > 0) {
                                        e.broadcast(
                                                "boss.record.ranked",
                                                Placeholder.unparsed("rank", String.valueOf(rank)),
                                                Placeholder.unparsed(
                                                        "time",
                                                        EncounterManager.formatMs(durationMs)));
                                    }
                                    var _ =
                                            SeasonServices.hallOfFame()
                                                    .submit(
                                                            HallOfFameSubmission.fastestBoss(
                                                                    e.def.id().id(),
                                                                    e.leader,
                                                                    plain,
                                                                    durationMs,
                                                                    Map.of(
                                                                            "members",
                                                                            BossRepository
                                                                                    .membersJson(
                                                                                            members),
                                                                            "encounter",
                                                                            e.id.toString()),
                                                                    clearedAt));
                                },
                                mgr.tasks().main())
                        .exceptionally(
                                err -> {
                                    mgr.log()
                                            .warn(
                                                    "보스 기록 저장 실패 {}: {}",
                                                    e.id,
                                                    String.valueOf(err.getMessage()));
                                    return null;
                                });
    }

    void announce(Encounter e, EncounterInfo info, long durationMs) {
        String leaderName =
                Optional.ofNullable(Bukkit.getPlayer(e.leader)).map(Player::getName).orElse("?");
        Component msg =
                api.messages()
                        .get(
                                e.def.isWorld()
                                        ? "boss.announce.world_cleared"
                                        : "boss.announce.cleared",
                                Placeholder.component("boss", e.bossName()),
                                Placeholder.unparsed("leader", leaderName),
                                Placeholder.unparsed(
                                        "count", String.valueOf(info.members().size())),
                                Placeholder.unparsed(
                                        "time", EncounterManager.formatMs(durationMs)));
        api.bus()
                .publish(
                        Channels.ANNOUNCEMENTS,
                        new Channels.Announcement(MiniMessage.miniMessage().serialize(msg)));
    }
}
