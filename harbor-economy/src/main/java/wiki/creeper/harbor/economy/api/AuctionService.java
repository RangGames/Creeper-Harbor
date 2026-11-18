package wiki.creeper.harbor.economy.api;

import java.util.List;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** 경매장 조회 (docs 20 7.2, 조회 전용). 등록·구매는 플레이어가 /경매 화면에서만 한다. 결과 future는 메인 스레드에서 완료된다. */
public interface AuctionService {

    /** 진행 중인 등록 (최신순). category가 null이면 전체. */
    CompletableFuture<List<AuctionListingInfo>> active(
            @org.jspecify.annotations.Nullable String category, int limit);

    /** 이 아이템(어종) ID의 진행 중 최저가. */
    CompletableFuture<OptionalLong> lowestPrice(String itemId);

    /** 판매자의 진행 중 등록 수. */
    CompletableFuture<Integer> activeCount(UUID seller);
}
