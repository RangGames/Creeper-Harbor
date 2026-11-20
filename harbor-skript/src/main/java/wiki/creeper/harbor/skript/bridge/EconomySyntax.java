package wiki.creeper.harbor.skript.bridge;

import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import org.bukkit.Bukkit;
import wiki.creeper.harbor.common.id.FishId;
import wiki.creeper.harbor.core.api.Harbor;
import wiki.creeper.harbor.economy.api.PriceService;
import wiki.creeper.harbor.economy.api.event.AuctionSoldEvent;
import wiki.creeper.harbor.skript.Ids;
import wiki.creeper.harbor.skript.lambda.Args;
import wiki.creeper.harbor.skript.lambda.Registrar;
import wiki.creeper.harbor.skript.lambda.Spec;

/** Harbor-Economy가 켜져 있을 때: 시세 계수(지금·다음 시간 예측), 경매 낙찰 이벤트. */
public final class EconomySyntax {

    private EconomySyntax() {}

    private static Optional<PriceService> prices() {
        return Harbor.api().service(PriceService.class);
    }

    private static Optional<FishId> fish(String raw) {
        return Ids.parse(raw, FishId.PREFIX).map(FishId::new);
    }

    public static void register(Registrar r) {
        r.number(
                Spec.single("harbor market coefficient"),
                (e, x) -> {
                    Optional<FishId> fish = Args.string(e, x, 0).flatMap(EconomySyntax::fish);
                    Optional<PriceService> p = prices();
                    return fish.isPresent() && p.isPresent()
                            ? List.of(p.get().coefficient(fish.get()))
                            : List.of();
                },
                "[the] harbor market (coefficient|rate) of [fish] %string%");
        r.number(
                Spec.single("harbor next market coefficient"),
                (e, x) -> {
                    Optional<FishId> fish = Args.string(e, x, 0).flatMap(EconomySyntax::fish);
                    Optional<PriceService> p = prices();
                    if (fish.isEmpty() || p.isEmpty()) {
                        return List.of();
                    }
                    OptionalDouble next = p.get().nextCoefficient(fish.get());
                    return next.isPresent() ? List.of(next.getAsDouble()) : List.of();
                },
                "[the] harbor next market (coefficient|rate) of [fish] %string%");

        r.event(
                "Harbor Auction Sold",
                AuctionSoldEvent.class,
                "경매 물건이 팔린 뒤 (event-number = 낙찰가, event-string = 아이템 ID, harbor auction buyer · seller)",
                "harbor auction sold",
                "harbor auction sale");
        r.value(AuctionSoldEvent.class, Number.class, AuctionSoldEvent::price);
        r.value(AuctionSoldEvent.class, String.class, AuctionSoldEvent::itemId);
        r.player(
                Spec.in("harbor auction buyer", AuctionSoldEvent.class),
                (e, x) ->
                        e instanceof AuctionSoldEvent s
                                ? List.of(Bukkit.getOfflinePlayer(s.buyer()))
                                : List.of(),
                "[the] harbor auction buyer");
        r.player(
                Spec.in("harbor auction seller", AuctionSoldEvent.class),
                (e, x) ->
                        e instanceof AuctionSoldEvent s
                                ? List.of(Bukkit.getOfflinePlayer(s.seller()))
                                : List.of(),
                "[the] harbor auction seller");
    }
}
