package wiki.creeper.harbor.world.bridge;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import wiki.creeper.harbor.npc.api.ActionResult;
import wiki.creeper.harbor.npc.api.DialogService;
import wiki.creeper.harbor.npc.api.ReputationService;

/**
 * NPC 연결 (선택). 시즌 간 최고 평판 등급(ReputationService.bestTier, D11: 하우징 크기·장식 조건)과 대화 액션 키를 등록한다. 대화
 * 파일(NPC content/dialogs)에 아래 키를 넣으면 선택지가 보인다: {@code world:shipyard}(조선소), {@code world:housing}(내
 * 부두), {@code world:decor_shop}(장식 상점), {@code world:airship}(비행선 노선), {@code world:chart}(해도).
 * Harbor-NPC가 있을 때만 이 클래스를 불러온다.
 */
public final class NpcBridge {

    private NpcBridge() {}

    /** 최고 평판 등급 (접속 중이면 캐시, 아니면 DB). 서비스가 없으면 0. */
    public static CompletableFuture<Integer> bestTier(UUID player) {
        ReputationService rep = Bukkit.getServicesManager().load(ReputationService.class);
        if (rep == null) {
            return CompletableFuture.completedFuture(0);
        }
        if (Bukkit.getPlayer(player) != null) {
            return CompletableFuture.completedFuture(rep.bestTier(player));
        }
        return rep.loadBestTier(player);
    }

    /** 대화 액션 등록 (화면을 여는 액션은 대화 세션만 끝낸다). */
    public static void registerActions(
            Plugin plugin,
            Consumer<Player> shipyard,
            Consumer<Player> housing,
            Consumer<Player> decorShop,
            Consumer<Player> airship,
            Consumer<Player> chart) {
        Optional<DialogService> dialogs =
                Optional.ofNullable(Bukkit.getServicesManager().load(DialogService.class));
        if (dialogs.isEmpty()) {
            return;
        }
        register(plugin, dialogs.get(), "world:shipyard", shipyard);
        register(plugin, dialogs.get(), "world:housing", housing);
        register(plugin, dialogs.get(), "world:decor_shop", decorShop);
        register(plugin, dialogs.get(), "world:airship", airship);
        register(plugin, dialogs.get(), "world:chart", chart);
    }

    private static void register(
            Plugin plugin, DialogService dialogs, String key, Consumer<Player> open) {
        if (dialogs.hasAction(key)) {
            return;
        }
        dialogs.registerAction(
                plugin,
                key,
                (ctx, args) -> {
                    open.accept(ctx.player());
                    return ActionResult.HANDOFF;
                });
    }
}
