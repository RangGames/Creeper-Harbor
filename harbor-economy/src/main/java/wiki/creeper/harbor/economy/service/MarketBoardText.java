package wiki.creeper.harbor.economy.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import wiki.creeper.harbor.common.content.type.Balance;
import wiki.creeper.harbor.common.content.type.FishDef;
import wiki.creeper.harbor.core.api.HarborApi;
import wiki.creeper.harbor.economy.content.EconomyBalance;

/**
 * Text of the in-world fish market price board (Text Display over market_board stations, docs 20
 * 7.4 step 6 "the fish market board (Text Display) is refreshed too"). Shows the species whose
 * market factor is furthest from 1.0 (balance.yml economy.market.board.display_lines, proposed)
 * with their ★1 price right now. Main thread.
 */
final class MarketBoardText {

    private MarketBoardText() {}

    static Component build(HarborApi api, MarketEngine market) {
        Balance balance = api.content().snapshot().single(Balance.TYPE);
        EconomyBalance eb = market.balance();
        int lines = balance.integer("economy.market.board.display_lines", 10);
        double flat = balance.number("economy.market.board.flat", 0.01);
        List<FishDef> fish = new ArrayList<>();
        for (FishDef f : api.content().snapshot().all(FishDef.TYPE).values()) {
            if (f.sellable() && !f.releaseOnly()) {
                fish.add(f);
            }
        }
        fish.sort(
                Comparator.comparingDouble(
                                (FishDef f) -> -Math.abs(market.coefficient(f.id()) - 1.0))
                        .thenComparingInt(FishDef::no));
        Component text = api.messages().get("economy.market.board.display.title");
        for (int i = 0; i < Math.min(lines, fish.size()); i++) {
            FishDef f = fish.get(i);
            double coef = market.coefficient(f.id());
            long price =
                    (long)
                            Math.floor(
                                    f.basePrice() * coef * eb.regionFactor(f.region().id().name()));
            String dir = coef > 1.0 + flat ? "up" : coef < 1.0 - flat ? "down" : "flat";
            text =
                    text.appendNewline()
                            .append(
                                    api.messages()
                                            .get(
                                                    "economy.market.board.display.line",
                                                    Placeholder.unparsed("name", f.name()),
                                                    Placeholder.unparsed(
                                                            "price", String.valueOf(price)),
                                                    Placeholder.unparsed(
                                                            "coef",
                                                            String.format(
                                                                    Locale.ROOT, "%.2f", coef)),
                                                    Placeholder.component(
                                                            "arrow",
                                                            api.messages()
                                                                    .get(
                                                                            "economy.market.board.display."
                                                                                    + dir))));
        }
        if (fish.isEmpty()) {
            text =
                    text.appendNewline()
                            .append(api.messages().get("economy.market.board.display.empty"));
        }
        return text.appendNewline()
                .append(api.messages().get("economy.market.board.display.footer"));
    }
}
