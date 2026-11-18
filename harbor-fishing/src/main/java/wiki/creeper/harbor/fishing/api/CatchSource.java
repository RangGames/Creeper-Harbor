package wiki.creeper.harbor.fishing.api;

/** 어종 아이템이 어디서 나왔는지 (FishCaughtEvent, 통발 감시용 custom_data). */
public enum CatchSource {
    /** 낚싯대 미니게임. */
    ROD,
    /** 통발 수확. */
    TRAP,
    /** 보스전 확정 포획 ({@link FishingService#createCatch}). */
    BOSS
}
