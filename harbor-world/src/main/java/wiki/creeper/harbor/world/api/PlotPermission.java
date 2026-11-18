package wiki.creeper.harbor.world.api;

/** 하우징 플롯 권한 (docs 20 8.4: 주인, 공동 건축자, 방문객). */
public enum PlotPermission {
    /** 들어가기 (방문 정책). */
    VISIT,
    /** 블록·장식 설치·파괴 (주인·공동 건축자). */
    BUILD,
    /** 개인 어항 전시 편집 (주인·공동 건축자, Collection이 묻는다). */
    EDIT_DISPLAY,
    /** 방문 정책·공동 건축자 설정 (주인만). */
    MANAGE
}
