package wiki.creeper.harbor.collection.api;

import java.util.concurrent.CompletableFuture;
import org.bukkit.entity.Player;

/** 항구 수족관 (서버 공동 목표). */
public interface AquariumService {

    /** 상태. rate는 이번 시즌 기부된 서로 다른 어종 수 ÷ 기부 가능 어종 수 (신화·release_only 제외). */
    record Status(long points, int species, int donatable, double rate, int stage) {}

    Status status();

    /** 손에 든 어종 아이템을 기부한다 (확인 화면 후). */
    CompletableFuture<Boolean> donateHeld(Player player);
}
