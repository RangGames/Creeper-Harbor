package wiki.creeper.harbor.npc.api;

/** 컷씬 결과 (docs 20 5.2). Quest는 CANCELLED·BUSY면 단계를 넘기지 않는다. */
public enum CutsceneResult {
    FINISHED,
    /** 이미 본 컷씬을 웅크리기 두 번으로 건너뜀. */
    SKIPPED,
    /** 퇴장·관리자 중단·오류·없는 컷씬. */
    CANCELLED,
    /** 미니게임·보스전·다른 컷씬 중이라 시작하지 않음. */
    BUSY;

    /** 끝까지 봤거나 건너뛰었는지 (다음 단계로 넘어가도 되는지). */
    public boolean completed() {
        return this == FINISHED || this == SKIPPED;
    }
}
