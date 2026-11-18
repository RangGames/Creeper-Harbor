package wiki.creeper.harbor.collection.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.slf4j.Logger;
import wiki.creeper.harbor.core.api.HarborApi;
import wiki.creeper.harbor.core.api.MailService;

/**
 * 아이템 돌려주기 (docs 20 0.1 '지급 실패 반환'). 접속 중이면 인벤토리 → 넘치면 우편함, 접속이 끊겼으면 우편함. 우편함(Economy MailService)이
 * 없으면 발밑에 떨어뜨리고, 그것도 안 되면 아이템 정보를 오류 로그에 남겨 관리자가 복구할 수 있게 한다. 메인 스레드 전용.
 */
public final class ItemReturns {

    private final HarborApi api;
    private final Logger log;

    public ItemReturns(HarborApi api, Logger log) {
        this.api = api;
        this.log = log;
    }

    /**
     * @param source 우편 출처 (예: aquarium_return)
     */
    public void giveBack(UUID uuid, List<ItemStack> items, String source) {
        List<ItemStack> left = new ArrayList<>();
        Player player = Bukkit.getPlayer(uuid);
        for (ItemStack item : items) {
            if (item.isEmpty()) {
                continue;
            }
            if (player != null && player.isOnline()) {
                left.addAll(player.getInventory().addItem(item.clone()).values());
            } else {
                left.add(item.clone());
            }
        }
        if (left.isEmpty()) {
            return;
        }
        Optional<MailService> mail = api.mail();
        for (ItemStack item : left) {
            if (mail.isPresent()) {
                String ref = source + ":" + UUID.randomUUID();
                var _ =
                        mail.get()
                                .sendItem(uuid, item, source, ref)
                                .exceptionally(
                                        e -> {
                                            log.error(
                                                    "우편 반환 실패 {} {} {}",
                                                    uuid,
                                                    source,
                                                    item.getType(),
                                                    e);
                                            return null;
                                        });
            } else if (player != null && player.isOnline()) {
                player.getWorld().dropItem(Locs.of(player), item).setOwner(uuid);
            } else {
                log.error("돌려줄 곳이 없는 아이템 (우편함 없음, 오프라인): {} {} {}", uuid, source, item);
            }
        }
        if (player != null && player.isOnline() && mail.isPresent()) {
            api.messages().send(player, "collection.returned_by_mail");
        }
    }
}
