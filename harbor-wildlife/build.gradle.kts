// Harbor-Wildlife: 야생 물고기 AI
plugins {
    id("harbor.paper-module")
}

dependencies {
    // 필수: World(지역·세부 구역·물고기 떼·해상 이벤트), Fishing(어종·찌 이벤트, claimShadow·ShadowDriver). Wildlife → Fishing 한 방향
    compileOnly(project(":harbor-world"))
    compileOnly(project(":harbor-fishing"))
    testImplementation(project(":harbor-fishing"))
}
