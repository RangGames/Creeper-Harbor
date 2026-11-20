package wiki.creeper.harbor.world.housing;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import wiki.creeper.harbor.common.account.AccountLinkReader;
import wiki.creeper.harbor.world.WorldKit;
import wiki.creeper.harbor.world.api.PlotInfo;
import wiki.creeper.harbor.world.api.VisitPolicy;
import wiki.creeper.harbor.world.api.event.PlotLikedEvent;
import wiki.creeper.harbor.world.housing.HousingManager.PlotState;

/**
 * 플롯 설정 명령 (/부두 설정·건축자·입구·좋아요). 방문 정책, 공동 건축자, 입장 지점을 바꾸고 좋아요를 센다. 좋아요는 방문객 1명이 플롯 1곳에 하루 1회이고, 주인과
 * 같은 IP 계정(Common AccountLinkReader)의 좋아요는 세지 않는다 (제안). 메인 스레드 전용.
 */
final class PlotActions {

    private final WorldKit kit;
    private final PlotStore store;

    PlotActions(WorldKit kit, PlotStore store) {
        this.kit = kit;
        this.store = store;
    }

    void settings(Player player) {
        Optional<PlotInfo> plot =
                Optional.ofNullable(store.ofOwner(player.getUniqueId())).map(s -> s.info);
        if (plot.isEmpty()) {
            kit.send(player, "world.housing.no_plot");
            return;
        }
        var msg = kit.api().messages();
        List<wiki.creeper.harbor.core.api.Dialogs.Choice> choices = new ArrayList<>();
        List<VisitPolicy> policies =
                List.of(VisitPolicy.PUBLIC, VisitPolicy.MEMBERS, VisitPolicy.PRIVATE);
        for (VisitPolicy p : policies) {
            choices.add(
                    new wiki.creeper.harbor.core.api.Dialogs.Choice(
                            msg.get("world.housing.policy." + p.key()),
                            msg.get("world.housing.policy_tip." + p.key())));
        }
        kit.api()
                .dialogs()
                .choose(
                        player,
                        msg.get("world.housing.settings_title"),
                        List.of(
                                msg.get(
                                        "world.housing.settings_body",
                                        Placeholder.unparsed(
                                                "policy",
                                                plain(
                                                        "world.housing.policy."
                                                                + plot.get()
                                                                        .visitPolicy()
                                                                        .key())))),
                        choices,
                        3,
                        index -> setPolicy(player, policies.get(index)));
    }

    private String plain(String key) {
        return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                .serialize(kit.api().messages().get(key));
    }

    private void setPolicy(Player player, VisitPolicy policy) {
        PlotState s = store.ofOwner(player.getUniqueId());
        if (s == null) {
            return;
        }
        PlotInfo o = s.info;
        s.info =
                new PlotInfo(
                        o.plotId(),
                        o.owner(),
                        o.ownerName(),
                        o.gridX(),
                        o.gridZ(),
                        o.size(),
                        o.theme(),
                        policy,
                        o.likes(),
                        o.world(),
                        o.minX(),
                        o.minZ(),
                        o.maxX(),
                        o.maxZ(),
                        o.spawnX(),
                        o.spawnY(),
                        o.spawnZ(),
                        o.spawnYaw(),
                        o.members());
        long plotId = o.plotId();
        var _ =
                kit.api()
                        .database()
                        .asyncVoid(c -> HousingRepository.updatePolicy(c, plotId, policy));
        kit.send(
                player,
                "world.housing.policy_set",
                Placeholder.unparsed("policy", plain("world.housing.policy." + policy.key())));
    }

    /** /부두 건축자 추가·제거 &lt;이름&gt;. */
    void member(Player player, String name, boolean add) {
        PlotState s = store.ofOwner(player.getUniqueId());
        if (s == null) {
            kit.send(player, "world.housing.no_plot");
            return;
        }
        long plotId = s.info.plotId();
        var _ =
                kit.api()
                        .database()
                        .async(
                                c -> {
                                    Optional<UUID> target = HousingRepository.uuidByName(c, name);
                                    if (target.isPresent()) {
                                        HousingRepository.member(c, plotId, target.get(), add);
                                    }
                                    return target;
                                })
                        .thenAcceptAsync(
                                target -> {
                                    if (target.isEmpty()) {
                                        kit.send(
                                                player,
                                                "world.housing.not_found",
                                                Placeholder.unparsed("name", name));
                                        return;
                                    }
                                    Set<UUID> members = new HashSet<>(s.info.members());
                                    if (add) {
                                        members.add(target.get());
                                    } else {
                                        members.remove(target.get());
                                    }
                                    PlotInfo o = s.info;
                                    s.info =
                                            new PlotInfo(
                                                    o.plotId(),
                                                    o.owner(),
                                                    o.ownerName(),
                                                    o.gridX(),
                                                    o.gridZ(),
                                                    o.size(),
                                                    o.theme(),
                                                    o.visitPolicy(),
                                                    o.likes(),
                                                    o.world(),
                                                    o.minX(),
                                                    o.minZ(),
                                                    o.maxX(),
                                                    o.maxZ(),
                                                    o.spawnX(),
                                                    o.spawnY(),
                                                    o.spawnZ(),
                                                    o.spawnYaw(),
                                                    members);
                                    kit.send(
                                            player,
                                            add
                                                    ? "world.housing.member_added"
                                                    : "world.housing.member_removed",
                                            Placeholder.unparsed("name", name));
                                },
                                kit.tasks().main());
    }

    /** /부두 입구: 내 플롯 안 지금 위치를 입장 지점으로. */
    void setSpawn(Player player) {
        Location at = WorldKit.loc(player);
        Optional<PlotState> s = store.stateAt(at);
        if (s.isEmpty() || !s.get().info.owner().equals(player.getUniqueId())) {
            kit.send(player, "world.housing.not_own_plot");
            return;
        }
        PlotInfo o = s.get().info;
        s.get().info =
                new PlotInfo(
                        o.plotId(),
                        o.owner(),
                        o.ownerName(),
                        o.gridX(),
                        o.gridZ(),
                        o.size(),
                        o.theme(),
                        o.visitPolicy(),
                        o.likes(),
                        o.world(),
                        o.minX(),
                        o.minZ(),
                        o.maxX(),
                        o.maxZ(),
                        at.getX(),
                        at.getY(),
                        at.getZ(),
                        at.getYaw(),
                        o.members());
        long id = o.plotId();
        var _ =
                kit.api()
                        .database()
                        .asyncVoid(
                                c ->
                                        HousingRepository.updateSpawn(
                                                c,
                                                id,
                                                at.getX(),
                                                at.getY(),
                                                at.getZ(),
                                                at.getYaw()));
        kit.send(player, "world.housing.spawn_set");
    }

    /** /부두 좋아요: 지금 서 있는 남의 플롯. */
    void like(Player player) {
        Optional<PlotState> s = store.stateAt(WorldKit.loc(player));
        if (s.isEmpty()) {
            kit.send(player, "world.housing.not_in_plot");
            return;
        }
        PlotInfo plot = s.get().info;
        UUID uuid = player.getUniqueId();
        if (plot.owner().equals(uuid)) {
            kit.send(player, "world.housing.like_self");
            return;
        }
        LocalDate day = kit.api().time().gameDay();
        var _ =
                kit.api()
                        .database()
                        .async(
                                c -> {
                                    if (AccountLinkReader.areLinked(c, plot.owner(), uuid)) {
                                        return -2;
                                    }
                                    return HousingRepository.like(c, plot.plotId(), uuid, day);
                                })
                        .thenAcceptAsync(
                                likes -> {
                                    if (likes == -2) {
                                        kit.send(player, "world.housing.like_linked");
                                        return;
                                    }
                                    if (likes < 0) {
                                        kit.send(player, "world.housing.like_already");
                                        return;
                                    }
                                    PlotInfo o = s.get().info;
                                    s.get().info =
                                            new PlotInfo(
                                                    o.plotId(),
                                                    o.owner(),
                                                    o.ownerName(),
                                                    o.gridX(),
                                                    o.gridZ(),
                                                    o.size(),
                                                    o.theme(),
                                                    o.visitPolicy(),
                                                    likes,
                                                    o.world(),
                                                    o.minX(),
                                                    o.minZ(),
                                                    o.maxX(),
                                                    o.maxZ(),
                                                    o.spawnX(),
                                                    o.spawnY(),
                                                    o.spawnZ(),
                                                    o.spawnYaw(),
                                                    o.members());
                                    kit.send(
                                            player,
                                            "world.housing.liked",
                                            Placeholder.unparsed("name", o.ownerName()),
                                            Placeholder.unparsed("likes", String.valueOf(likes)));
                                    Bukkit.getPluginManager()
                                            .callEvent(
                                                    new PlotLikedEvent(
                                                            player, s.get().info, likes));
                                },
                                kit.tasks().main());
    }
}
