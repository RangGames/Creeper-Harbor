package wiki.creeper.harbor.web.relay;

import java.util.ArrayList;
import java.util.List;

/**
 * Game events relayed externally (module api event class → external type). Classes are listed by
 * name only, so they load only when their module is enabled. Event data is built from getters by
 * {@link EventSerializer} (field name = snake_case of the getter name).
 *
 * <p>Types never change (receivers' rules depend on them). Frequent events (bites, movement,
 * objective progress, etc.) are left out.
 */
public final class EventCatalog {

    /**
     * @param plugin name of the plugin owning the event (always enabled for Harbor-Core)
     * @param className fully qualified event class name
     * @param type external type
     */
    public record Entry(String plugin, String className, String type) {}

    private static final String H = "wiki.creeper.harbor.";

    public static final List<Entry> ENTRIES = build();

    private EventCatalog() {}

    private static List<Entry> build() {
        List<Entry> e = new ArrayList<>();
        module(e, "Harbor-Core", "core", "economy.currency_changed", "CurrencyChangedEvent");
        module(e, "Harbor-Core", "core", "server.content_reloaded", "ContentReloadedEvent");

        module(e, "Harbor-Quest", "quest", "quest.accepted", "QuestAcceptedEvent");
        module(e, "Harbor-Quest", "quest", "quest.completed", "QuestCompletedEvent");
        module(e, "Harbor-Quest", "quest", "quest.abandoned", "QuestAbandonedEvent");
        module(e, "Harbor-Quest", "quest", "quest.chapter_completed", "ChapterCompletedEvent");
        module(e, "Harbor-Quest", "quest", "quest.chronicle_piece", "ChroniclePieceEvent");

        module(e, "Harbor-Economy", "economy", "economy.auction_listed", "AuctionListedEvent");
        module(e, "Harbor-Economy", "economy", "economy.auction_sold", "AuctionSoldEvent");
        module(e, "Harbor-Economy", "economy", "economy.fish_sold", "FishSoldEvent");
        module(e, "Harbor-Economy", "economy", "economy.item_crafted", "ItemCraftedEvent");
        module(e, "Harbor-Economy", "economy", "economy.shop_purchase", "ShopPurchaseEvent");
        module(e, "Harbor-Economy", "economy", "economy.trade_flagged", "TradeFlaggedEvent");
        module(e, "Harbor-Economy", "economy", "economy.market_updated", "MarketUpdatedEvent");

        module(
                e,
                "Harbor-Collection",
                "collection",
                "collection.registered",
                "CollectionRegisteredEvent");
        module(
                e,
                "Harbor-Collection",
                "collection",
                "collection.milestone",
                "CollectionMilestoneEvent");
        module(
                e,
                "Harbor-Collection",
                "collection",
                "collection.record_broken",
                "RecordBrokenEvent");
        module(
                e,
                "Harbor-Collection",
                "collection",
                "collection.aquarium_donated",
                "AquariumDonatedEvent");
        module(
                e,
                "Harbor-Collection",
                "collection",
                "collection.aquarium_stage",
                "AquariumStageChangedEvent");
        module(
                e,
                "Harbor-Collection",
                "collection",
                "collection.fish_released",
                "FishReleasedEvent");

        module(e, "Harbor-Fishing", "fishing", "fishing.caught", "FishCaughtEvent");
        module(e, "Harbor-Fishing", "fishing", "fishing.trap_harvest", "TrapHarvestEvent");

        module(e, "Harbor-Jobs", "jobs", "jobs.advanced", "JobAdvancedEvent");
        module(e, "Harbor-Jobs", "jobs", "jobs.level_up", "JobLevelUpEvent");
        module(e, "Harbor-Jobs", "jobs", "jobs.line_changed", "JobLineChangedEvent");
        module(e, "Harbor-Jobs", "jobs", "jobs.capstone_used", "CapstoneUsedEvent");

        module(e, "Harbor-Npc", "npc", "npc.reputation_tier", "ReputationTierChangedEvent");
        module(e, "Harbor-Npc", "npc", "npc.affinity_level_up", "AffinityLevelUpEvent");
        module(e, "Harbor-Npc", "npc", "npc.gift_given", "GiftGivenEvent");

        module(e, "Harbor-Season", "season", "season.title_granted", "TitleGrantedEvent");
        module(e, "Harbor-Season", "season", "season.week_changed", "SeasonWeekChangedEvent");
        module(e, "Harbor-Season", "season", "season.daily_reset", "DailyResetEvent");
        module(e, "Harbor-Season", "season", "season.weekly_reset", "WeeklyResetEvent");
        module(e, "Harbor-Season", "season", "season.hall_of_fame", "HallOfFameUpdatedEvent");
        module(e, "Harbor-Season", "season", "season.player_entered", "PlayerSeasonEnteredEvent");

        module(e, "Harbor-Shop", "shop", "shop.cosmetic_granted", "CosmeticGrantedEvent");
        module(e, "Harbor-Shop", "shop", "shop.donor_tier_changed", "DonorTierChangedEvent");
        module(e, "Harbor-Shop", "shop", "shop.season_pass_level_up", "SeasonPassLevelUpEvent");
        module(e, "Harbor-Shop", "shop", "shop.festival_progress", "FestivalProgressEvent");

        module(e, "Harbor-Boss", "boss", "boss.encounter_start", "BossEncounterStartEvent");
        module(e, "Harbor-Boss", "boss", "boss.encounter_end", "BossEncounterEndEvent");
        module(e, "Harbor-Boss", "boss", "boss.world_progress", "WorldBossProgressEvent");

        module(e, "Harbor-World", "world", "world.sea_event_warn", "SeaEventWarnEvent");
        module(e, "Harbor-World", "world", "world.sea_event_start", "SeaEventStartEvent");
        module(e, "Harbor-World", "world", "world.sea_event_end", "SeaEventEndEvent");
        module(e, "Harbor-World", "world", "world.treasure_claimed", "TreasureClaimedEvent");
        module(e, "Harbor-World", "world", "world.ship_wrecked", "ShipWreckedEvent");
        module(e, "Harbor-World", "world", "world.region_first_visit", "RegionFirstVisitEvent");
        module(e, "Harbor-World", "world", "world.plot_liked", "PlotLikedEvent");
        module(e, "Harbor-World", "world", "world.chart_progress", "ChartProgressEvent");
        return List.copyOf(e);
    }

    private static void module(
            List<Entry> out, String plugin, String pkg, String type, String simple) {
        out.add(new Entry(plugin, H + pkg + ".api.event." + simple, type));
    }

    /** Events Harbor-Web emits directly from Bukkit events (join, quit, server start/stop). */
    public static final List<String> SERVER_TYPES =
            List.of(
                    "server.started",
                    "server.stopping",
                    "server.player_join",
                    "server.player_quit");
}
