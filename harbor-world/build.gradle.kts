// Harbor-World: 지역·해도·선박·하우징·해상 이벤트
plugins {
    id("harbor.paper-module")
}

dependencies {
    // 필수: Jobs(레벨·단계), Fishing(RegionKeyResolver·TreasureDropHook 구현, SeaEventMessage)
    compileOnly(project(":harbor-jobs"))
    compileOnly(project(":harbor-fishing"))
    // 선택: Economy(ShopService.charge·RepairService), NPC(대화 액션·최고 평판), Season(대회 랭킹 원천)
    compileOnly(project(":harbor-economy"))
    compileOnly(project(":harbor-npc"))
    compileOnly(project(":harbor-season"))
    // 선택(나중에 적재): Quest RegionProgressProvider를 더 높은 우선순위로 등록
    compileOnly(project(":harbor-quest"))
    // 선택(나중에 적재): Shop 선박 스킨 장착 여부
    compileOnly(project(":harbor-shop"))
    testImplementation(project(":harbor-fishing"))
    testImplementation(project(":harbor-jobs"))
}

// JOML(Display 변환 행렬)은 옛 클래스 파일 형식이라 javac classfile 경고가 난다. 그 경고만 끈다
tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.add("-Xlint:-classfile")
}
