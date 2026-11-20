package wiki.creeper.harbor.fishing.session;

import static wiki.creeper.harbor.fishing.session.FishingManager.COMPONENT;
import static wiki.creeper.harbor.fishing.session.FishingManager.loc;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.random.RandomGenerator;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import wiki.creeper.harbor.common.content.type.FishDef;
import wiki.creeper.harbor.common.content.type.ItemDef;
import wiki.creeper.harbor.common.id.FishId;
import wiki.creeper.harbor.common.id.RegionId;
import wiki.creeper.harbor.common.model.Grade;
import wiki.creeper.harbor.common.model.Quality;
import wiki.creeper.harbor.common.redis.Channels;
import wiki.creeper.harbor.core.api.HarborApi;
import wiki.creeper.harbor.core.api.HarborItem;
import wiki.creeper.harbor.core.api.HarborPlayer;
import wiki.creeper.harbor.fishing.api.CatchOptions;
import wiki.creeper.harbor.fishing.api.CatchPresentationOverride;
import wiki.creeper.harbor.fishing.api.CatchResult;
import wiki.creeper.harbor.fishing.api.CatchSource;
import wiki.creeper.harbor.fishing.api.CodexProvider;
import wiki.creeper.harbor.fishing.api.TreasureDropHook;
import wiki.creeper.harbor.fishing.api.event.FishCaughtEvent;
import wiki.creeper.harbor.fishing.api.event.QualityRolledEvent;
import wiki.creeper.harbor.fishing.content.FishingBalance;
import wiki.creeper.harbor.fishing.content.JunkTable;
import wiki.creeper.harbor.fishing.data.FishingRepository;
import wiki.creeper.harbor.fishing.present.CatchPresenter;
import wiki.creeper.harbor.fishing.roll.JunkRoller;
import wiki.creeper.harbor.fishing.roll.QualityRoller;
import wiki.creeper.harbor.jobs.api.ExpGrant;
import wiki.creeper.harbor.jobs.api.ExpSource;
import wiki.creeper.harbor.jobs.api.JobsService;

/**
 * 포획 결과 (docs 20 3.4 RESULT): 크기·품질, 어종 아이템, 전설 천장 초기화, 잡템·보물 지도, 직업 EXP, 축하 연출·전체 공지. Boss용
 * createCatch도 여기서 만든다. 메인 스레드 전용.
 */
final class CatchRewards {

    private final HarborApi api;
    private final JobsService jobs;
    private final Logger log;
    private final CastContext ctx;
    private final FishingGear gear;
    private final CatchPresenter presenter;
    private final RandomGenerator random;
    private @Nullable TreasureDropHook treasureHook;
    private @Nullable CatchPresentationOverride presentation;

    CatchRewards(
            HarborApi api,
            JobsService jobs,
            Logger log,
            CastContext ctx,
            FishingGear gear,
            CatchPresenter presenter,
            RandomGenerator random) {
        this.api = api;
        this.jobs = jobs;
        this.log = log;
        this.ctx = ctx;
        this.gear = gear;
        this.presenter = presenter;
        this.random = random;
    }

    void registerTreasureHook(TreasureDropHook hook) {
        this.treasureHook = hook;
    }

    void registerPresentation(CatchPresentationOverride override) {
        this.presentation = override;
    }

    /** FishingService.createCatch (Boss 등 미니게임 밖 포획). */
    Optional<CatchResult> createCatch(Player player, FishId fishId, CatchOptions options) {
        Optional<FishDef> def = api.content().snapshot().get(FishDef.TYPE, fishId.id());
        if (def.isEmpty()) {
            return Optional.empty();
        }
        FishDef fish = def.get();
        UUID uuid = player.getUniqueId();
        HarborPlayer hp = api.players().get(player);
        FishingBalance b = ctx.balance();
        Quality cap = jobs.qualityCap(uuid);
        QualityRoller.Rolled rolled =
                QualityRoller.roll(
                        fish,
                        ctx.stat(uuid, "size_luck") + options.extraLuck(),
                        cap,
                        b.qualityBounds(),
                        random);
        int stars = Math.min(cap.stars(), rolled.quality().stars() + options.qualityBonusStars());
        QualityRolledEvent qualityEvent =
                new QualityRolledEvent(
                        player, fish, rolled.sizeCm(), Quality.of(stars), rolled.capped());
        Bukkit.getPluginManager().callEvent(qualityEvent);
        Instant now = Instant.now();
        ItemStack stack = null;
        HarborItem.Fish info;
        if (fish.releaseOnly()) {
            info =
                    new HarborItem.Fish(
                            fish.id(),
                            qualityEvent.sizeCm(),
                            qualityEvent.quality(),
                            hp.ref(),
                            hp.season(),
                            now,
                            null,
                            1);
        } else {
            stack =
                    api.items()
                            .createFish(
                                    fish,
                                    qualityEvent.sizeCm(),
                                    qualityEvent.quality(),
                                    hp.ref(),
                                    now);
            info = readFish(stack, fish, qualityEvent, hp, now);
        }
        if (options.fireCaughtEvent()) {
            CodexProvider provider = ctx.codex();
            boolean firstCatch = provider != null && !provider.hasCaught(uuid, fish.id());
            Bukkit.getPluginManager()
                    .callEvent(
                            new FishCaughtEvent(
                                    player,
                                    fish,
                                    info,
                                    fish.region(),
                                    fish.releaseOnly(),
                                    firstCatch,
                                    rolled.capped(),
                                    CatchSource.BOSS));
        }
        return Optional.of(new CatchResult(stack, info, rolled.capped()));
    }

    private HarborItem.Fish readFish(
            ItemStack stack, FishDef fish, QualityRolledEvent q, HarborPlayer hp, Instant now) {
        return api.items()
                .read(stack)
                .filter(i -> i instanceof HarborItem.Fish)
                .map(i -> (HarborItem.Fish) i)
                .orElse(
                        new HarborItem.Fish(
                                fish.id(),
                                q.sizeCm(),
                                q.quality(),
                                hp.ref(),
                                hp.season(),
                                now,
                                null,
                                1));
    }

    // ------------------------------------------------------------------ 결과 (성공)

    void catchFish(
            Player player,
            FishingSession s,
            FishDef fish,
            RegionId region,
            @Nullable Location hookLoc) {
        UUID uuid = player.getUniqueId();
        HarborPlayer hp = api.players().get(player);
        FishingBalance b = ctx.balance();
        Quality cap = jobs.qualityCap(uuid);
        QualityRoller.Rolled rolled =
                QualityRoller.roll(
                        fish, ctx.stat(uuid, "size_luck"), cap, b.qualityBounds(), random);
        Quality forced = s.forcedQuality;
        if (forced != null) {
            rolled = new QualityRoller.Rolled(rolled.sizeCm(), forced, false);
            s.forcedQuality = null;
        }
        if (rolled.capped()) {
            s.missedBig++;
            api.ui().actionBar(player, api.messages().get("fishing.missed_big"));
        }
        QualityRolledEvent qualityEvent =
                new QualityRolledEvent(
                        player, fish, rolled.sizeCm(), rolled.quality(), rolled.capped());
        Bukkit.getPluginManager().callEvent(qualityEvent);

        CodexProvider provider = ctx.codex();
        boolean firstCatch = provider != null && !provider.hasCaught(uuid, fish.id());
        Instant now = Instant.now();
        HarborItem.Fish caught;
        if (fish.releaseOnly()) {
            caught =
                    new HarborItem.Fish(
                            fish.id(),
                            qualityEvent.sizeCm(),
                            qualityEvent.quality(),
                            hp.ref(),
                            hp.season(),
                            now,
                            null,
                            1);
            api.messages()
                    .send(
                            player,
                            "fishing.result.released",
                            Placeholder.unparsed("name", fish.name()));
        } else {
            ItemStack stack =
                    api.items()
                            .createFish(
                                    fish,
                                    qualityEvent.sizeCm(),
                                    qualityEvent.quality(),
                                    hp.ref(),
                                    now);
            caught = readFish(stack, fish, qualityEvent, hp, now);
            gear.give(player, stack);
        }
        if (fish.grade() == Grade.LEGENDARY) {
            // 전설 포획: 그 어종의 천장 카운터를 즉시 0으로
            hp.set(COMPONENT, hp.get(COMPONENT).withPity(fish.id(), 0));
            var _ =
                    api.database()
                            .asyncVoid(
                                    c ->
                                            FishingRepository.resetPity(
                                                    c, uuid, hp.season(), fish.id()));
        }
        gear.damageRod(player);
        junk(player, region, hookLoc);

        Bukkit.getPluginManager()
                .callEvent(
                        new FishCaughtEvent(
                                player,
                                fish,
                                caught,
                                region,
                                fish.releaseOnly(),
                                firstCatch,
                                rolled.capped()));

        // EXP: 등급 기본 × 지역 배율 × 첫 포획 배율 (첫 만남 +1·랭크)
        double exp = b.gradeExp(fish.grade()) * b.regionExpMult(region.id().name());
        if (firstCatch) {
            exp *= b.firstCatchMult(fish.grade()) + ctx.stat(uuid, "first_catch_exp_bonus");
        }
        jobs.grantExp(uuid, new ExpGrant(ExpSource.FISHING, exp, region, null));
        if (qualityEvent.quality().stars() >= 4) {
            jobs.claimTierFirstQuality(uuid, qualityEvent.quality());
        }
        celebrate(player, s, fish, caught, firstCatch);
    }

    /** 잡템: 지역 표로 따로 굴려 추가 지급, 보물 지도는 World 확장 지점으로. */
    private void junk(Player player, RegionId region, @Nullable Location hookLoc) {
        JunkTable table = api.content().snapshot().single(JunkTable.TYPE);
        TreasureDropHook hook = treasureHook;
        JunkRoller.Drops drops =
                JunkRoller.roll(
                        table,
                        region,
                        ctx.stat(player.getUniqueId(), "treasure_map_chance"),
                        hook != null,
                        random);
        for (JunkTable.Entry entry : drops.items()) {
            api.content()
                    .snapshot()
                    .get(ItemDef.TYPE, entry.item().id())
                    .ifPresent(
                            def -> {
                                gear.give(player, api.items().create(def, entry.amount()));
                                api.messages()
                                        .send(
                                                player,
                                                "fishing.junk",
                                                Placeholder.unparsed("name", def.name()),
                                                Placeholder.unparsed(
                                                        "amount", String.valueOf(entry.amount())));
                            });
        }
        if (drops.treasure() && hook != null) {
            try {
                hook.onTreasureMap(player, hookLoc == null ? loc(player) : hookLoc, region);
            } catch (RuntimeException e) {
                log.warn("TreasureDropHook 오류: {}", String.valueOf(e));
            }
        }
    }

    private void celebrate(
            Player player,
            FishingSession s,
            FishDef fish,
            HarborItem.Fish caught,
            boolean firstCatch) {
        String size = String.format(Locale.ROOT, "%.1f", caught.sizeCm());
        Component line =
                api.messages()
                        .get(
                                "fishing.result.caught",
                                Placeholder.unparsed("name", fish.name()),
                                Placeholder.unparsed("size", size),
                                Placeholder.unparsed("quality", caught.quality().display()));
        player.sendMessage(line);
        if (fish.grade().isAtLeast(Grade.EPIC)) {
            api.ui()
                    .title(
                            player,
                            api.messages()
                                    .get(
                                            "item.name." + fish.grade().key(),
                                            Placeholder.unparsed("name", fish.name())),
                            Component.text(size + "cm " + caught.quality().display()),
                            Duration.ofMillis(300),
                            Duration.ofSeconds(3),
                            Duration.ofSeconds(1));
            // 소리는 presets.yml 등급 포획음(catch.*)이 낸다
        }
        presenter.caught(
                player,
                fish,
                caught.sizeCm(),
                caught.quality(),
                firstCatch,
                s.settings.intensity(),
                s.settings.dialog(),
                presentation);
        if (fish.grade().isAtLeast(Grade.LEGENDARY)) {
            String key =
                    fish.grade() == Grade.MYTHIC
                            ? "fishing.announce.mythic"
                            : "fishing.announce.legendary";
            Component announce =
                    api.messages()
                            .get(
                                    key,
                                    Placeholder.unparsed("player", player.getName()),
                                    Placeholder.unparsed("name", fish.name()),
                                    Placeholder.unparsed("size", size));
            api.bus()
                    .publish(
                            Channels.ANNOUNCEMENTS,
                            new Channels.Announcement(
                                    MiniMessage.miniMessage().serialize(announce)));
        }
    }
}
