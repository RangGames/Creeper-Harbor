// Harbor-Collection: 도감·수족관·개인 어항·방류
plugins {
    id("harbor.paper-module")
}

dependencies {
    compileOnly(project(":harbor-fishing"))
    compileOnly(project(":harbor-jobs"))
    // 필수: 칭호·랭킹·명예의 전당·시즌 훅 (Season api)
    compileOnly(project(":harbor-season"))
    // 선택: 개인 어항 DisplayAnchor·편집 권한 (World api). Shop은 Shop → Collection 의존이라 AquariumThemeOwnership 서비스로만 묻는다
    compileOnly(project(":harbor-world"))
    // 선택: 대화 액션(aquarium:donate·codex:hint), 최고 평판 등급(어항 칸), 방류 평판
    compileOnly(project(":harbor-npc"))
    testImplementation(project(":harbor-fishing"))
    testImplementation(project(":harbor-season"))
}

// JOML(Display 변환 행렬)은 옛 클래스 파일 형식이라 javac classfile 경고가 난다. 그 경고만 끈다
tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.add("-Xlint:-classfile")
}
