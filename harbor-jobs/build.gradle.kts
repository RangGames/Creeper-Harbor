// Harbor-Jobs: 직업·전직·스킬 트리
plugins {
    id("harbor.paper-module")
}

dependencies {
    // 선택 의존 (docs 20 0.3): NPC 대화 액션 job:change_line · job:reset_skill, 전직 컷씬 (CutsceneService)
    compileOnly(project(":harbor-npc"))
}
