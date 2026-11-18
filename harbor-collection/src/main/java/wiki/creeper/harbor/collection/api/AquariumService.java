package wiki.creeper.harbor.collection.api;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/** 항구 수족관 (서버 공동 목표, docs 20 4.4). 메인 스레드에서 부르고 결과도 메인 스레드에서 완료된다. */
public interface AquariumService {

    /** 상태. rate는 이번 시즌 기부된 서로 다른 어종 수 ÷ 기부 가능 어종 수 (신화·release_only 제외). */
    record Status(long points, int species, int donatable, double rate, int stage) {}

    /**
     * 여러 마리 기부 결과.
     *
     * @param donated 기부한 마리 수
     * @param points 얻은 점수 합
     * @param returned 돌려준 아이템 수 (어종이 아니거나 기부할 수 없는 아이템, 실패한 기부)
     * @param failed 트랜잭션이 실패해 전부 돌려줬는지
     */
    record DonationResult(int donated, int points, int returned, boolean failed) {

        public static final DonationResult NONE = new DonationResult(0, 0, 0, false);

        public boolean ok() {
            return donated > 0 && !failed;
        }
    }

    Status status();

    /** 손에 든 어종 아이템을 기부한다 (확인 화면 후). */
    CompletableFuture<Boolean> donateHeld(Player player);

    /** 기부함(상자 GUI)을 연다 (이슬 대화 액션 aquarium:donate, 수족관 접수대). 닫으면 확인 화면 → 기부. */
    void openDonationBox(Player player);

    /**
     * 이미 인벤토리에서 꺼낸 아이템을 확인 화면 없이 한 트랜잭션으로 기부한다 (Quest 수집 의뢰 donate_delivered). 넘긴 아이템의 소유는 이 서비스로
     * 넘어온다: 어종이 아니거나 기부할 수 없는 아이템, 실패한 기부는 플레이어에게 돌려주고 인벤토리가 차 있거나 접속이 끊겼으면 우편함 (MailService)으로
     * 보낸다.
     *
     * <p>고유 ID 아이템의 장부는 ACTIVE → DONATED로 바꾼다. source가 {@code quest:}로 시작하면 Quest가 같은 트랜잭션 전에 이미
     * DONATED로 바꾼 경우도 받는다.
     *
     * @param source 출처 (예: {@code quest:<퀘스트 ID>}, {@code npc}, {@code box})
     */
    CompletableFuture<DonationResult> donateItems(
            Player player, List<ItemStack> items, String source);
}
