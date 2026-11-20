// Harbor-Web: 외부 연동 (추가 제안). 게임 이벤트 → Redis harbor:events, 웹훅 발송(디스코드·JSON), 읽기 전용 REST API.
// 게임 모듈 이벤트는 클래스 이름으로 구독하므로(모듈이 켜져 있을 때만) 이벤트 쪽은 Core에만 기댄다.
// REST의 모듈별 조회(시세·경매·기록·랭킹)는 그 모듈이 켜져 있을 때만 불러오는 bridge 클래스에 둔다.
plugins {
    id("harbor.paper-module")
}

dependencies {
    compileOnly(project(":harbor-economy"))
    compileOnly(project(":harbor-collection"))
    compileOnly(project(":harbor-season"))
    // 테스트: 이벤트 목록의 클래스가 실제로 있는지 확인한다
    listOf(
        "jobs", "fishing", "collection", "npc", "quest", "economy",
        "world", "boss", "season", "shop", "wildlife",
    ).forEach { testImplementation(project(":harbor-$it")) }
}

tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.add("-Xlint:-classfile")
}
