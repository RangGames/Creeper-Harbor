package wiki.creeper.harbor.quest.service;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.ItemLore;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.advancement.Advancement;
import org.bukkit.advancement.AdvancementProgress;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.common.ledger.LedgerReason;
import wiki.creeper.harbor.common.model.Currency;
import wiki.creeper.harbor.core.api.FlagService;
import wiki.creeper.harbor.core.api.HarborPlayer;
import wiki.creeper.harbor.core.gui.PagedMenu;
import wiki.creeper.harbor.npc.api.ReputationSource;
import wiki.creeper.harbor.quest.api.ChronicleService;
import wiki.creeper.harbor.quest.api.QuestState;
import wiki.creeper.harbor.quest.api.event.ChapterCompletedEvent;
import wiki.creeper.harbor.quest.api.event.ChroniclePieceEvent;
import wiki.creeper.harbor.quest.content.ChronicleDef;
import wiki.creeper.harbor.quest.data.QuestEntry;
import wiki.creeper.harbor.quest.logic.RefIds;
import wiki.creeper.harbor.season.api.TitleId;

/**
 * "Harbor Chronicle" and chronicle recaps (docs 20 6.4, '5. Server Story' ch. 6, D9).
 *
 * <ul>
 *   <li>Pieces: permanent player flag {@code chronicle/<id>} + custom advancement criterion + /연대기
 *       GUI. Collecting the full chronicle (chronicle.yml required_pieces, chapters 1~7) gives the
 *       chronicle title
 *   <li>Recap: entering the region of an already completed (carried-over) chapter for the first
 *       time in a new season gives 3 mulbineul + 150 reputation (mulbineul capped at 27 per season,
 *       reference ID recap:&lt;season&gt;:&lt;uuid&gt;:&lt;chapter&gt;). Only once, via the
 *       season-scoped flag quest/recap/ch&lt;N&gt; (also counted in the ch. 7 clear count for the
 *       climax)
 * </ul>
 */
public final class ChronicleManager implements ChronicleService {

    static final String PIECE_FLAG = "chronicle/";
    static final String RECAP_FLAG = "quest/recap/ch";

    private final QuestEngine engine;
    private final Set<String> recapping = new HashSet<>();

    ChronicleManager(QuestEngine engine) {
        this.engine = engine;
    }

    @Override
    public Set<String> pieces(UUID player) {
        Set<String> owned = new LinkedHashSet<>();
        for (ChronicleDef.Piece p : engine.chronicleDef().pieces()) {
            if (engine.api.flags().hasPlayer(player, PIECE_FLAG + p.id())) {
                owned.add(p.id());
            }
        }
        return owned;
    }

    /** Grants a piece (idempotent: if the flag exists, only the title is rechecked). */
    CompletableFuture<Boolean> award(UUID uuid, String pieceId) {
        ChronicleDef def = engine.chronicleDef();
        Optional<ChronicleDef.Piece> piece = def.piece(pieceId);
        if (piece.isEmpty()) {
            engine.log.warn("연대기 조각 정의가 없습니다: {}", pieceId);
            return CompletableFuture.completedFuture(true);
        }
        if (engine.api.flags().hasPlayer(uuid, PIECE_FLAG + pieceId)) {
            return completeTitle(uuid);
        }
        return engine.api
                .flags()
                .setPlayer(uuid, FlagService.Scope.PERM, PIECE_FLAG + pieceId)
                .thenComposeAsync(
                        v -> {
                            Set<String> owned = pieces(uuid);
                            owned.add(pieceId);
                            Bukkit.getPluginManager()
                                    .callEvent(
                                            new ChroniclePieceEvent(
                                                    uuid,
                                                    pieceId,
                                                    owned.size(),
                                                    def.totalPieces()));
                            Player player = Bukkit.getPlayer(uuid);
                            if (player != null) {
                                advancement(player, piece.get().advancement());
                                engine.api
                                        .ui()
                                        .title(
                                                player,
                                                engine.api
                                                        .messages()
                                                        .get("quest.chronicle.piece_title"),
                                                engine.api
                                                        .messages()
                                                        .get(
                                                                "quest.chronicle.piece_subtitle",
                                                                Placeholder.unparsed(
                                                                        "name",
                                                                        piece.get().name())),
                                                java.time.Duration.ofMillis(500),
                                                java.time.Duration.ofSeconds(3),
                                                java.time.Duration.ofSeconds(1));
                                player.playSound(player, Sound.ITEM_BOOK_PAGE_TURN, 1f, 0.8f);
                            }
                            return completeTitle(uuid);
                        },
                        engine.tasks.main());
    }

    private CompletableFuture<Boolean> completeTitle(UUID uuid) {
        ChronicleDef def = engine.chronicleDef();
        String title = def.completeTitle();
        // The full chronicle (chapters 1~7): every defined piece and at least required_pieces
        if (title == null
                || def.pieces().isEmpty()
                || pieces(uuid).size() < def.pieces().size()
                || def.pieces().size() < def.totalPieces()) {
            return CompletableFuture.completedFuture(true);
        }
        return engine.deps
                .titles()
                .grant(uuid, TitleId.of(title), "chronicle:complete")
                .thenApply(g -> g.owned());
    }

    /**
     * Awards the "Harbor Chronicle" custom advancement criterion (skipped without the datapack).
     */
    private void advancement(Player player, @Nullable String key) {
        if (key == null) {
            return;
        }
        NamespacedKey nk = NamespacedKey.fromString(key);
        Advancement adv = nk == null ? null : Bukkit.getAdvancement(nk);
        if (adv == null) {
            return;
        }
        AdvancementProgress progress = player.getAdvancementProgress(adv);
        for (String criterion : List.copyOf(progress.getRemainingCriteria())) {
            progress.awardCriteria(criterion);
        }
    }

    // ------------------------------------------------------------------ Chronicle recap (D9)

    /**
     * On location change (Tracker): grants if inside the region/area of a chapter eligible for
     * recap.
     */
    void onMove(
            Player player,
            HarborPlayer hp,
            @Nullable HarborId region,
            double x,
            double z,
            boolean entered) {
        if (region == null) {
            return;
        }
        int season = engine.season();
        UUID uuid = hp.uuid();
        for (ChronicleDef.Chapter chapter : engine.chronicleDef().chapters()) {
            var chapterRegion = chapter.region();
            if (chapterRegion == null || !chapterRegion.id().equals(region)) {
                continue;
            }
            ChronicleDef.Area area = chapter.area();
            if (area != null && !area.contains(x, z)) {
                continue;
            }
            QuestEntry fin = hp.get(QuestEngine.COMPONENT).entries().get(chapter.finalQuest());
            if (fin == null || fin.state() != QuestState.COMPLETED || fin.season() >= season) {
                continue;
            }
            String flag = RECAP_FLAG + chapter.chapter();
            String key = uuid + "|" + flag;
            if (engine.api.flags().hasPlayer(uuid, flag) || !recapping.add(key)) {
                continue;
            }
            recap(player, chapter, flag, key);
        }
    }

    private void recap(Player player, ChronicleDef.Chapter chapter, String flag, String key) {
        UUID uuid = player.getUniqueId();
        ChronicleDef def = engine.chronicleDef();
        int season = engine.season();
        long given = 0;
        for (ChronicleDef.Chapter c : def.chapters()) {
            if (engine.api.flags().hasPlayer(uuid, RECAP_FLAG + c.chapter())) {
                given += def.recapMulbineul();
            }
        }
        long mulbineul = Math.max(0, Math.min(def.recapMulbineul(), def.recapMax() - given));
        var _ =
                engine.api
                        .flags()
                        .setPlayer(uuid, FlagService.Scope.SEASON, flag)
                        .whenCompleteAsync(
                                (v, error) -> {
                                    recapping.remove(key);
                                    if (error != null) {
                                        engine.log.warn("연대기 회상 플래그 저장 실패: {} {}", uuid, flag);
                                        return;
                                    }
                                    if (mulbineul > 0) {
                                        var _ =
                                                engine.api
                                                        .currency()
                                                        .give(
                                                                uuid,
                                                                Currency.MULBINEUL,
                                                                mulbineul,
                                                                LedgerReason.QUEST_REWARD,
                                                                RefIds.recap(
                                                                        season,
                                                                        uuid,
                                                                        chapter.chapter()));
                                    }
                                    if (def.recapReputation() > 0) {
                                        var _ =
                                                engine.deps
                                                        .reputation()
                                                        .add(
                                                                uuid,
                                                                def.recapReputation(),
                                                                ReputationSource.MAIN_STORY,
                                                                "recap:"
                                                                        + season
                                                                        + ":"
                                                                        + chapter.chapter());
                                    }
                                    Bukkit.getPluginManager()
                                            .callEvent(
                                                    new ChapterCompletedEvent(
                                                            uuid, chapter.chapter(), true));
                                    if (player.isOnline()) {
                                        engine.api
                                                .messages()
                                                .send(
                                                        player,
                                                        "quest.chronicle.recap",
                                                        Placeholder.unparsed(
                                                                "chapter", chapter.name()),
                                                        Placeholder.unparsed(
                                                                "mulbineul",
                                                                String.valueOf(mulbineul)),
                                                        Placeholder.unparsed(
                                                                "reputation",
                                                                String.valueOf(
                                                                        def.recapReputation())));
                                        player.playSound(
                                                player, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1f, 1f);
                                    }
                                },
                                engine.tasks.main());
    }

    // ------------------------------------------------------------------ GUI

    @Override
    public void open(Player player) {
        ChronicleDef def = engine.chronicleDef();
        UUID uuid = player.getUniqueId();
        Set<String> owned = pieces(uuid);
        List<ChronicleDef.Chapter> chapters = new ArrayList<>(def.chapters());
        chapters.sort(java.util.Comparator.comparingInt(ChronicleDef.Chapter::chapter));
        PagedMenu<ChronicleDef.Chapter> menu =
                new PagedMenu<>(
                        engine.api
                                .messages()
                                .get(
                                        "quest.chronicle.title",
                                        Placeholder.unparsed("owned", String.valueOf(owned.size())),
                                        Placeholder.unparsed(
                                                "total", String.valueOf(def.totalPieces()))),
                        3,
                        chapters,
                        c -> new PagedMenu.Entry(icon(player, c, owned), e -> replay(player, c)),
                        named(Material.ARROW, engine.api.messages().get("quest.menu.previous")),
                        named(Material.ARROW, engine.api.messages().get("quest.menu.next")));
        menu.show(player, 0);
    }

    private boolean cleared(UUID uuid, ChronicleDef.Chapter chapter) {
        return engine.data(uuid)
                .entry(chapter.finalQuest())
                .map(e -> e.state() == QuestState.COMPLETED)
                .orElse(false);
    }

    private ItemStack icon(Player player, ChronicleDef.Chapter chapter, Set<String> owned) {
        UUID uuid = player.getUniqueId();
        boolean cleared = cleared(uuid, chapter);
        Optional<ChronicleDef.Piece> piece =
                engine.chronicleDef().pieces().stream()
                        .filter(p -> p.chapter() == chapter.chapter())
                        .findFirst();
        Material material = Material.GRAY_DYE;
        List<Component> lore = new ArrayList<>();
        if (piece.isPresent() && owned.contains(piece.get().id())) {
            Material m = Material.matchMaterial(piece.get().icon());
            material = m != null && m.isItem() ? m : Material.PAPER;
            lore.add(
                    engine.api
                            .messages()
                            .get(
                                    "quest.chronicle.piece",
                                    Placeholder.unparsed("name", piece.get().name())));
            for (String line : piece.get().text()) {
                lore.add(
                        engine.api
                                .messages()
                                .get("quest.chronicle.text", Placeholder.unparsed("text", line)));
            }
        } else if (cleared) {
            material = Material.BOOK;
        } else {
            lore.add(engine.api.messages().get("quest.chronicle.locked"));
        }
        if (cleared && chapter.cutscene() != null) {
            lore.add(engine.api.messages().get("quest.chronicle.replay"));
        }
        ItemStack stack = ItemStack.of(material);
        stack.setData(
                DataComponentTypes.ITEM_NAME,
                engine.api
                        .messages()
                        .get(
                                cleared
                                        ? "quest.chronicle.chapter_cleared"
                                        : "quest.chronicle.chapter",
                                Placeholder.unparsed("name", chapter.name())));
        stack.setData(
                DataComponentTypes.LORE,
                ItemLore.lore(
                        lore.stream()
                                .map(
                                        c ->
                                                c.decorationIfAbsent(
                                                        TextDecoration.ITALIC,
                                                        TextDecoration.State.FALSE))
                                .toList()));
        return stack;
    }

    /** Replays a chapter (no reward). */
    void replay(Player player, ChronicleDef.Chapter chapter) {
        HarborId cutscene = chapter.cutscene();
        if (cutscene == null || !cleared(player.getUniqueId(), chapter)) {
            return;
        }
        player.closeInventory();
        engine.playCutscene(player, cutscene);
    }

    static ItemStack named(Material material, Component name) {
        ItemStack stack = ItemStack.of(material);
        stack.setData(DataComponentTypes.ITEM_NAME, name);
        return stack;
    }
}
