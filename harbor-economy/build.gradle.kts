// Harbor-Economy: 상점·경매·시세·제작
plugins {
    id("harbor.paper-module")
}

dependencies {
    // docs 20 0.3: Jobs·Fishing 필수, NPC·Season(WeeklyLimitService) 필수 (지시), World·Quest는 이벤트·플래그로만 연결
    compileOnly(project(":harbor-jobs"))
    compileOnly(project(":harbor-fishing"))
    compileOnly(project(":harbor-npc"))
    compileOnly(project(":harbor-season"))
    testImplementation(project(":harbor-jobs"))
    testImplementation(project(":harbor-fishing"))
    testImplementation(project(":harbor-npc"))
    testImplementation(project(":harbor-season"))
}
