package wiki.creeper.harbor.skript.bridge;

import ch.njol.skript.lang.Expression;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.inventory.ItemStack;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.common.model.Keyed;
import wiki.creeper.harbor.core.api.Harbor;
import wiki.creeper.harbor.shop.api.CosmeticService;
import wiki.creeper.harbor.shop.api.DonorService;
import wiki.creeper.harbor.shop.api.SeasonPassService;
import wiki.creeper.harbor.shop.api.event.CosmeticEquipEvent;
import wiki.creeper.harbor.shop.api.event.CosmeticGrantedEvent;
import wiki.creeper.harbor.shop.api.event.DonorTierChangedEvent;
import wiki.creeper.harbor.shop.api.event.FestivalProgressEvent;
import wiki.creeper.harbor.shop.api.event.SeasonPassLevelUpEvent;
import wiki.creeper.harbor.shop.content.CosmeticDef;
import wiki.creeper.harbor.skript.Ids;
import wiki.creeper.harbor.skript.lambda.Args;
import wiki.creeper.harbor.skript.lambda.Registrar;
import wiki.creeper.harbor.skript.lambda.Spec;

/**
 * When Harbor-Shop is enabled: cosmetics (owned, equipped, equip and unequip, admin grant and
 * revoke), rod skin of an item, donor tier, season pass (level, exp, premium, admin exp grant
 * through a pass exp source), and the shop api events.
 *
 * <p>Pearls (paid currency) are deliberately not exposed: no balance, no spending, no granting, and
 * no {@code PearlBalanceChangedEvent} (README rule: scripts never touch paid currency). The
 * festival event exposes only the gauge percent, not money amounts.
 */
public final class ShopSyntax {

    /** cosmetics_owned.source for script grants (traceable through refId skript:…). */
    private static final String GRANT_SOURCE = "ADMIN";

    private ShopSyntax() {}

    private static <T> Optional<T> service(Class<T> type) {
        return Harbor.api().service(type);
    }

    private static Optional<HarborId> cosmetic(Event e, Expression<?>[] x, int index) {
        return Args.string(e, x, index).flatMap(s -> Ids.parse(s, CosmeticDef.PREFIX));
    }

    private static Optional<CosmeticDef.Category> slot(Event e, Expression<?>[] x, int index) {
        return Args.string(e, x, index)
                .map(s -> s.strip().toLowerCase(Locale.ROOT).replace(' ', '_'))
                .flatMap(s -> Keyed.fromKey(CosmeticDef.Category.class, s));
    }

    private static OfflinePlayer offline(UUID uuid) {
        return Bukkit.getOfflinePlayer(uuid);
    }

    public static void register(Registrar r) {
        cosmetics(r);
        pass(r);
        events(r);
    }

    // ------------------------------------------------------------------ cosmetics and donor

    private static void cosmetics(Registrar r) {
        r.text(
                Spec.single("harbor cosmetics"),
                (e, x) -> {
                    Optional<CosmeticService> c = service(CosmeticService.class);
                    if (c.isEmpty() || !(Args.single(e, x, 0) instanceof Player p)) {
                        return List.of();
                    }
                    return c.get().owned(p.getUniqueId()).stream()
                            .map(HarborId::toString)
                            .sorted()
                            .toList();
                },
                "[the] [owned] harbor cosmetics of %player%");
        r.condition(
                Spec.of("owns harbor cosmetic"),
                (e, x) -> {
                    Optional<HarborId> id = cosmetic(e, x, 1);
                    Optional<CosmeticService> c = service(CosmeticService.class);
                    return id.isPresent()
                            && c.isPresent()
                            && Args.all(e, x, p -> c.get().owns(p.getUniqueId(), id.get()));
                },
                "%players% (own|owns) harbor cosmetic %string%",
                "%players% (doesn't|does not|don't|do not) own harbor cosmetic %string%");
        r.text(
                Spec.of("equipped harbor cosmetic"),
                (e, x) -> {
                    Optional<CosmeticDef.Category> slot = slot(e, x, 1);
                    Optional<CosmeticService> c = service(CosmeticService.class);
                    if (slot.isEmpty() || c.isEmpty()) {
                        return List.of();
                    }
                    return Args.perPlayer(
                            e,
                            x,
                            p ->
                                    c.get()
                                            .equipped(p.getUniqueId(), slot.get())
                                            .map(HarborId::toString)
                                            .orElse(null));
                },
                "[the] equipped harbor cosmetic of %players% in [slot] %string%");
        r.effect(
                Spec.of("equip harbor cosmetic"),
                (e, x) -> {
                    Optional<HarborId> id = cosmetic(e, x, 0);
                    Optional<CosmeticService> c = service(CosmeticService.class);
                    if (id.isEmpty() || c.isEmpty()) {
                        return;
                    }
                    for (Player p : Args.players(e, x, 1)) {
                        c.get().equip(p, id.get());
                    }
                },
                "equip harbor cosmetic %string% (on|for|to) %players%");
        r.effect(
                Spec.of("unequip harbor cosmetic"),
                (e, x) -> {
                    Optional<CosmeticDef.Category> slot = slot(e, x, 0);
                    Optional<CosmeticService> c = service(CosmeticService.class);
                    if (slot.isEmpty() || c.isEmpty()) {
                        return;
                    }
                    for (Player p : Args.players(e, x, 1)) {
                        c.get().unequip(p, slot.get());
                    }
                },
                "unequip harbor cosmetic [slot] %string% (of|for|from) %players%");
        r.effect(
                Spec.of("admin grant harbor cosmetic"),
                (e, x) -> {
                    Optional<HarborId> id = cosmetic(e, x, 0);
                    Optional<CosmeticService> c = service(CosmeticService.class);
                    if (id.isEmpty() || c.isEmpty() || x.length < 2 || x[1] == null) {
                        return;
                    }
                    for (Object o : x[1].getArray(e)) {
                        if (o instanceof OfflinePlayer p) {
                            var _ =
                                    c.get()
                                            .grant(
                                                    p.getUniqueId(),
                                                    id.get(),
                                                    GRANT_SOURCE,
                                                    "skript:" + UUID.randomUUID());
                        }
                    }
                },
                "harbor admin grant cosmetic %string% to %offlineplayers%");
        r.effect(
                Spec.of("admin revoke harbor cosmetic"),
                (e, x) -> {
                    Optional<HarborId> id = cosmetic(e, x, 0);
                    Optional<CosmeticService> c = service(CosmeticService.class);
                    if (id.isEmpty() || c.isEmpty() || x.length < 2 || x[1] == null) {
                        return;
                    }
                    for (Object o : x[1].getArray(e)) {
                        if (o instanceof OfflinePlayer p) {
                            var _ = c.get().revoke(p.getUniqueId(), id.get());
                        }
                    }
                },
                "harbor admin revoke cosmetic %string% from %offlineplayers%");
        r.text(
                Spec.of("harbor rod skin"),
                (e, x) -> {
                    Optional<CosmeticService> c = service(CosmeticService.class);
                    if (c.isEmpty() || x.length == 0 || x[0] == null) {
                        return List.of();
                    }
                    List<String> out = new java.util.ArrayList<>();
                    for (Object o : x[0].getArray(e)) {
                        if (o instanceof ItemStack s) {
                            c.get().skinOf(s).ifPresent(id -> out.add(id.toString()));
                        }
                    }
                    return out;
                },
                "[the] harbor [rod] skin of %itemstacks%");
        r.text(
                Spec.of("harbor donor tier"),
                (e, x) ->
                        service(DonorService.class)
                                .map(
                                        d ->
                                                Args.perPlayer(
                                                        e,
                                                        x,
                                                        p ->
                                                                d.tier(p.getUniqueId())
                                                                        .name()
                                                                        .toLowerCase(Locale.ROOT)))
                                .orElse(List.of()),
                "[the] harbor donor tier of %players%");
    }

    // ------------------------------------------------------------------ season pass

    private static void pass(Registrar r) {
        r.number(
                Spec.of("harbor pass level"),
                (e, x) ->
                        service(SeasonPassService.class)
                                .map(
                                        s ->
                                                Args.<Number>perPlayer(
                                                        e, x, p -> s.level(p.getUniqueId())))
                                .orElse(List.of()),
                "[the] harbor [season] pass level of %players%");
        r.number(
                Spec.of("harbor pass exp"),
                (e, x) ->
                        service(SeasonPassService.class)
                                .map(s -> Args.<Number>perPlayer(e, x, p -> s.exp(p.getUniqueId())))
                                .orElse(List.of()),
                "[the] harbor [season] pass (exp|experience) of %players%");
        r.number(
                Spec.of("harbor pass premium"),
                (e, x) ->
                        service(SeasonPassService.class)
                                .map(
                                        s ->
                                                Args.<Number>perPlayer(
                                                        e, x, p -> s.premium(p.getUniqueId())))
                                .orElse(List.of()),
                "[the] harbor [season] pass premium (tier|level) of %players%");
        r.text(
                Spec.single("harbor pass sources"),
                (e, x) ->
                        service(SeasonPassService.class)
                                .map(s -> s.sources().stream().sorted().toList())
                                .orElse(List.of()),
                "[all] [the] harbor [season] pass (exp|experience) sources");
        r.effect(
                Spec.of("admin give harbor pass exp"),
                (e, x) -> {
                    Optional<SeasonPassService> s = service(SeasonPassService.class);
                    int units = Args.number(e, x, 0).map(Number::intValue).orElse(0);
                    String source = Args.string(e, x, 1).orElse("").strip();
                    if (s.isEmpty() || units <= 0 || source.isEmpty()) {
                        return;
                    }
                    for (Player p : Args.players(e, x, 2)) {
                        s.get().addExp(p.getUniqueId(), source, units);
                    }
                },
                "harbor admin give %number% [season] pass (exp|experience) unit[s] (from|of)"
                        + " source %string% to %players%");
    }

    // ------------------------------------------------------------------ api events

    private static void events(Registrar r) {
        r.event(
                "Harbor Cosmetic Equip",
                CosmeticEquipEvent.class,
                "치장 슬롯이 바뀐 뒤 (event-player, event-string = 새 치장 ID (해제면 없음), harbor cosmetic"
                        + " slot · previous cosmetic)",
                "harbor cosmetic equip");
        r.value(
                CosmeticEquipEvent.class,
                String.class,
                e -> {
                    HarborId cur = e.current();
                    return cur == null ? null : cur.toString();
                });
        r.text(
                Spec.in("harbor cosmetic slot", CosmeticEquipEvent.class),
                (e, x) -> e instanceof CosmeticEquipEvent c ? List.of(c.slot().key()) : List.of(),
                "[the] harbor cosmetic slot");
        r.text(
                Spec.in("harbor previous cosmetic", CosmeticEquipEvent.class),
                (e, x) -> {
                    if (!(e instanceof CosmeticEquipEvent c)) {
                        return List.of();
                    }
                    HarborId prev = c.previous();
                    return prev == null ? List.of() : List.of(prev.toString());
                },
                "[the] harbor previous cosmetic");

        r.event(
                "Harbor Cosmetic Grant",
                CosmeticGrantedEvent.class,
                "치장을 새로 얻은 뒤 (event-offlineplayer, event-string = 치장 ID, harbor cosmetic grant"
                        + " source)",
                "harbor cosmetic grant[ed]");
        r.value(CosmeticGrantedEvent.class, OfflinePlayer.class, e -> offline(e.player()));
        r.value(CosmeticGrantedEvent.class, String.class, e -> e.cosmetic().toString());
        r.text(
                Spec.in("harbor cosmetic grant source", CosmeticGrantedEvent.class),
                (e, x) -> e instanceof CosmeticGrantedEvent c ? List.of(c.source()) : List.of(),
                "[the] harbor cosmetic grant source");

        r.event(
                "Harbor Donor Tier Change",
                DonorTierChangedEvent.class,
                "후원 등급이 바뀐 뒤 (event-offlineplayer, event-string = 새 등급)",
                "harbor donor tier change");
        r.value(DonorTierChangedEvent.class, OfflinePlayer.class, e -> offline(e.player()));
        r.value(
                DonorTierChangedEvent.class,
                String.class,
                e -> e.current().name().toLowerCase(Locale.ROOT));

        r.event(
                "Harbor Festival Progress",
                FestivalProgressEvent.class,
                "항구 축제 게이지가 바뀐 뒤 (event-string = 축제 ID, event-number = 달성률 %, harbor festival"
                        + " goal reached)",
                "harbor festival progress");
        r.value(FestivalProgressEvent.class, String.class, e -> e.festival().toString());
        r.value(
                FestivalProgressEvent.class,
                Number.class,
                e -> e.goalKrw() <= 0 ? 0 : Math.min(100.0, e.totalKrw() * 100.0 / e.goalKrw()));
        r.condition(
                Spec.in("harbor festival goal reached", FestivalProgressEvent.class),
                (e, x) -> e instanceof FestivalProgressEvent f && f.reached(),
                "[the] harbor festival goal (is|was) reached",
                "[the] harbor festival goal (isn't|is not|wasn't|was not) reached");

        r.event(
                "Harbor Pass Level Up",
                SeasonPassLevelUpEvent.class,
                "시즌 패스 레벨이 오른 뒤 (event-offlineplayer, event-number = 새 레벨, harbor pass previous"
                        + " level)",
                "harbor [season] pass level up");
        r.value(SeasonPassLevelUpEvent.class, OfflinePlayer.class, e -> offline(e.player()));
        r.value(SeasonPassLevelUpEvent.class, Number.class, SeasonPassLevelUpEvent::level);
        r.number(
                Spec.in("harbor pass previous level", SeasonPassLevelUpEvent.class),
                (e, x) ->
                        e instanceof SeasonPassLevelUpEvent s
                                ? List.of(s.previousLevel())
                                : List.of(),
                "[the] harbor [season] pass previous level");
    }
}
