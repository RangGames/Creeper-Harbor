package wiki.creeper.harbor.npc.content;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import wiki.creeper.harbor.common.content.type.Balance;
import wiki.creeper.harbor.npc.api.AffinityLevel;
import wiki.creeper.harbor.npc.api.DialogStyle;
import wiki.creeper.harbor.npc.api.ReputationSource;
import wiki.creeper.harbor.npc.api.ReputationTier;

/**
 * balance.yml의 reputation 절(docs 20 5.3)과 npc 절(추가 제안). 값이 없으면 docs 20 5.4의 제안값을 쓴다. 모든 수치는 CBT 지표로
 * 확정한다.
 */
public record NpcBalance(Balance balance) {

    // ------------------------------------------------------------------ 평판

    /** 등급표 (1부터, 누적 평판 오름차순). balance.yml {@code reputation.tiers.<등급>: {points, name}}. */
    public List<ReputationTier> reputationTiers() {
        List<ReputationTier> tiers = new ArrayList<>();
        for (String key : balance.keys("reputation.tiers")) {
            int tier;
            try {
                tier = Integer.parseInt(key);
            } catch (NumberFormatException e) {
                continue;
            }
            String base = "reputation.tiers." + key;
            tiers.add(
                    new ReputationTier(
                            tier,
                            balance.integer(base + ".points", 0),
                            balance.string(base + ".name").orElse(String.valueOf(tier))));
        }
        if (tiers.isEmpty()) {
            // docs 20 5.4 평판 표 (제안값)
            tiers.add(new ReputationTier(1, 0, "이방인"));
            tiers.add(new ReputationTier(2, 300, "이웃"));
            tiers.add(new ReputationTier(3, 1_000, "단골"));
            tiers.add(new ReputationTier(4, 2_000, "항구의 일원"));
            tiers.add(new ReputationTier(5, 3_500, "항구의 기둥"));
        }
        tiers.sort(Comparator.comparingInt(ReputationTier::tier));
        return List.copyOf(tiers);
    }

    /** 출처별 기본 평판 (docs 20 5.4 제안값). */
    public int sourceValue(ReputationSource source) {
        int fallback =
                switch (source) {
                    case MAIN_STORY -> 150;
                    case REGION_QUEST -> 40;
                    case DAILY_REQUEST -> 10;
                    case WEEKLY_REQUEST -> 30;
                    case COLLECT_REQUEST -> 20;
                    case BOSS_FIRST_KILL -> 50;
                    case WORLD_BOSS -> 500;
                    case RELEASE -> 3;
                    case ADMIN, OTHER -> 0;
                };
        return balance.integer("reputation.sources." + source.key(), fallback);
    }

    /** 출처별 하루 상한 (0이면 없음). 방류 30 (제안값). */
    public int dailyCap(ReputationSource source) {
        int fallback = source == ReputationSource.RELEASE ? 30 : 0;
        return balance.integer("reputation.daily_caps." + source.key(), fallback);
    }

    /** /평판 최근 내역 줄 수. */
    public int recentSize() {
        return balance.integer("reputation.recent_size", 10);
    }

    // ------------------------------------------------------------------ 호감도

    /**
     * 기본 단계표 (npcs.yml affinity_levels가 없을 때). {@code npc.affinity.levels.<단계>: {points, name,
     * discount, unlocks}}.
     */
    public List<AffinityLevel> affinityLevels() {
        List<AffinityLevel> levels = new ArrayList<>();
        List<String> keys = new ArrayList<>(balance.keys("npc.affinity.levels"));
        keys.sort(Comparator.comparingInt(NpcBalance::intOrMax));
        for (String key : keys) {
            String base = "npc.affinity.levels." + key;
            levels.add(
                    new AffinityLevel(
                            levels.size(),
                            balance.integer(base + ".points", 0),
                            balance.string(base + ".name").orElse(key),
                            balance.integer(base + ".discount", 0),
                            balance.string(base + ".unlocks")
                                    .map(s -> List.of(s.split(",")))
                                    .orElse(List.of())));
        }
        if (levels.isEmpty()) {
            levels.add(new AffinityLevel(0, 0, "낯섦", 0, List.of()));
            levels.add(new AffinityLevel(1, 100, "아는 사이", 0, List.of()));
            levels.add(new AffinityLevel(2, 300, "친구", 3, List.of()));
            levels.add(new AffinityLevel(3, 600, "단짝", 5, List.of()));
            levels.add(new AffinityLevel(4, 1_000, "신뢰", 8, List.of()));
        }
        return List.copyOf(levels);
    }

    /** 하루 첫 대화 호감도 (제안값 +2). */
    public int greetPoints() {
        return balance.integer("npc.affinity.greet", 2);
    }

    /** gifts 표에 없는 아이템의 호감도 (기본 +1). */
    public int giftDefault() {
        return balance.integer("npc.affinity.gift_default", 1);
    }

    /** 어종 선물 품질 배율 × (1 + bonus × (★ − 1)), 제안값 0.25. */
    public double giftQualityBonus() {
        return balance.number("npc.affinity.gift_quality_bonus", 0.25);
    }

    /** NPC당 하루 선물 횟수 (1). */
    public int giftsPerDay() {
        return balance.integer("npc.affinity.gifts_per_day", 1);
    }

    public int reactionLove() {
        return balance.integer("npc.affinity.reactions.love", 15);
    }

    public int reactionLike() {
        return balance.integer("npc.affinity.reactions.like", 5);
    }

    // ------------------------------------------------------------------ 대화·엔티티

    /** 대화 세션 유효 시간 (5분). */
    public int dialogTimeoutSeconds() {
        return balance.integer("npc.dialog.timeout_seconds", 300);
    }

    /** 선택지 클릭 때 NPC와의 최대 거리 (6블록). */
    public double optionDistance() {
        return balance.number("npc.dialog.option_distance", 6);
    }

    /** 같은 플레이어의 중복 클릭 무시 (10틱). */
    public int clickCooldownTicks() {
        return balance.integer("npc.dialog.click_cooldown_ticks", 10);
    }

    // ------------------------------------------------------------------ 대화 방식 (추가 제안, 모두 제안값)

    /** NPC에 dialog_style이 없을 때 방식 (dialog). */
    public DialogStyle defaultStyle() {
        return balance.string("npc.dialog.default_style")
                .flatMap(DialogStyle::parse)
                .orElse(DialogStyle.DIALOG);
    }

    /** DIALOG 밖의 세션: 거리·말풍선 확인 간격 (10틱). */
    public int watchTicks() {
        return Math.max(2, balance.integer("npc.dialog.watch_ticks", 10));
    }

    /** 말풍선 높이 (NPC 발 기준, 이름·표시보다 위). */
    public double hologramOffset() {
        return balance.number("npc.dialog.hologram.offset", 2.9);
    }

    /** 말풍선 한 줄 너비 (픽셀, TextDisplay line width). */
    public int hologramLineWidth() {
        return Math.max(40, balance.integer("npc.dialog.hologram.line_width", 180));
    }

    /** 한마디(say) 말풍선·타이틀이 떠 있는 시간 (초). */
    public double barkSeconds() {
        return Math.max(1, balance.number("npc.dialog.hologram.bark_seconds", 4));
    }

    /** 타자 효과: 틱당 글자 수 (0이면 한 번에). */
    public int typewriterCharsPerTick() {
        return Math.max(0, balance.integer("npc.dialog.hologram.typewriter_chars_per_tick", 2));
    }

    /** 말풍선 보이는 거리 배율 (TextDisplay view range, 1 = 64블록). */
    public float hologramViewRange() {
        return (float) balance.number("npc.dialog.hologram.view_range", 0.5);
    }

    /** 말풍선 배경 불투명도 (0~255). */
    public int hologramBackgroundAlpha() {
        return Math.clamp(balance.integer("npc.dialog.hologram.background_alpha", 96), 0, 255);
    }

    /** TITLE 방식에서 서브타이틀이 떠 있는 시간 (초). auto가 있으면 그 값. */
    public double titleStaySeconds() {
        return Math.max(1, balance.number("npc.dialog.title.stay_seconds", 8));
    }

    /** 서브타이틀이 이 글자 수를 넘으면 채팅에도 같이 보낸다 (잘려 보일 수 있어서). */
    public int titleMaxChars() {
        return Math.max(8, balance.integer("npc.dialog.title.max_chars", 40));
    }

    /** ACTIONBAR 방식: 액션바가 사라지지 않게 다시 보내는 간격 (40틱). */
    public int actionBarRefreshTicks() {
        return Math.max(10, balance.integer("npc.dialog.actionbar.refresh_ticks", 40));
    }

    public int lookIntervalTicks() {
        return Math.max(1, balance.integer("npc.entity.look_interval_ticks", 4));
    }

    public double interactionWidth() {
        return balance.number("npc.entity.interaction_width", 0.8);
    }

    public double interactionHeight() {
        return balance.number("npc.entity.interaction_height", 2.0);
    }

    /** 이름 Text Display 높이 (발 기준). */
    public double nameOffset() {
        return balance.number("npc.entity.name_offset", 2.15);
    }

    public double markerOffset() {
        return balance.number("npc.entity.marker_offset", 2.5);
    }

    // ------------------------------------------------------------------ 떠돌이

    public int wandererSlotHours() {
        return balance.integer("npc.wanderer.slot_hours", 3);
    }

    /** 조건부 후보 확인 간격 (1분). */
    public int wandererCheckTicks() {
        return Math.max(20, balance.integer("npc.wanderer.check_interval_ticks", 1_200));
    }

    /** 대화 중인 플레이어가 있을 때 이동을 미루는 최대 시간 (60초). */
    public int wandererMaxDelaySeconds() {
        return balance.integer("npc.wanderer.max_delay_seconds", 60);
    }

    // ------------------------------------------------------------------ 컷씬

    /** 복구 정보 쓰기를 기다리는 최대 시간 (1초). */
    public int cutsceneRestoreTimeoutMillis() {
        return balance.integer("npc.cutscene.restore_timeout_ms", 1_000);
    }

    /** 웅크리기 두 번 간격 (10틱). */
    public int cutsceneSkipWindowTicks() {
        return balance.integer("npc.cutscene.skip_window_ticks", 10);
    }

    /** 플레이어당 컷씬 엔티티 상한 (20). */
    public int cutsceneMaxEntities() {
        return balance.integer("npc.cutscene.max_entities", 20);
    }

    /** 카메라 보간 한 번의 최대 길이 (teleport_duration 상한 59틱 안). */
    public int cameraStepTicks() {
        return Math.clamp(balance.integer("npc.cutscene.camera_step_ticks", 40), 1, 59);
    }

    private static int intOrMax(String key) {
        try {
            return Integer.parseInt(key);
        } catch (NumberFormatException e) {
            return Integer.MAX_VALUE;
        }
    }
}
