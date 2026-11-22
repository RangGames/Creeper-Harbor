package wiki.creeper.harbor.quest.service;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.Nullable;

/** 보상·반환 아이템 전달: 인벤토리가 차면 우편함, 우편함이 없으면 발밑. 메인 스레드 전용. */
final class ItemDelivery {

    private final QuestEngine engine;

    ItemDelivery(QuestEngine engine) {
        this.engine = engine;
    }

    /** 인벤토리가 차면 우편함 (Core api MailService, 구현 Economy). 우편함이 없으면 발밑에 떨어뜨린다. */
    void deliverItem(Player player, ItemStack stack, String ref) {
        if (engine.api.players().frozen(player.getUniqueId())) {
            // 서버 이동 저장본을 뜬 뒤라 인벤토리에 넣어도 저장되지 않는다
            sendMail(player.getUniqueId(), stack, ref, null);
            return;
        }
        Map<Integer, ItemStack> left = player.getInventory().addItem(stack);
        if (left.isEmpty()) {
            return;
        }
        for (ItemStack rest : left.values()) {
            sendMail(player.getUniqueId(), rest, ref, player.getLocation());
        }
        engine.api.messages().send(player, "quest.reward.mailed");
    }

    void sendMail(UUID uuid, ItemStack stack, String ref, @Nullable Location fallback) {
        Optional<wiki.creeper.harbor.core.api.MailService> mail = engine.api.mail();
        if (mail.isPresent()) {
            var _ = mail.get().sendItem(uuid, stack, "quest", ref);
            return;
        }
        if (fallback != null && fallback.getWorld() != null) {
            fallback.getWorld().dropItemNaturally(fallback, stack);
        } else {
            engine.log.error("우편함이 없어 퀘스트 아이템을 줄 수 없습니다 (관리자 복구 필요): {} {}", uuid, stack);
        }
    }

    /** 실패한 보고에서 뺀 아이템 반환 (오프라인이면 우편함). */
    void giveBack(UUID uuid, List<ItemStack> removed) {
        Player player = Bukkit.getPlayer(uuid);
        // 서버 이동 저장본을 뜬 뒤면 인벤토리에 넣어도 저장되지 않으므로 우편으로
        boolean frozen = player != null && engine.api.players().frozen(uuid);
        for (ItemStack stack : removed) {
            if (player != null && !frozen) {
                player.getInventory()
                        .addItem(stack)
                        .values()
                        .forEach(
                                rest -> sendMail(uuid, rest, "quest_return", player.getLocation()));
            } else {
                sendMail(uuid, stack, "quest_return", null);
            }
        }
    }
}
