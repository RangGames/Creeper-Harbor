// Harbor-Tools: 콘텐츠 검증 CLI (CI에서 실행). 서버 플러그인과 같은 로더 코드를 쓴다.
plugins {
    id("harbor.java")
    application
}

dependencies {
    implementation(project(":harbor-common"))
    // 모듈 콘텐츠 형식 (서버 API를 쓰지 않는 클래스만 불러온다)
    implementation(project(":harbor-core"))
    implementation(project(":harbor-jobs"))
    implementation(project(":harbor-fishing"))
    implementation(project(":harbor-collection"))
    implementation(project(":harbor-economy"))
    implementation(project(":harbor-season"))
    implementation(project(":harbor-npc"))
    implementation(project(":harbor-quest"))
    implementation(project(":harbor-shop"))
    implementation(project(":harbor-boss"))
    implementation(project(":harbor-world"))
    implementation(libs.snakeyaml)
    implementation(libs.gson)
}

application {
    mainClass = "wiki.creeper.harbor.tools.HarborTools"
}

// ./gradlew validateContent → content/ 폴더를 검사. 오류가 있으면 실패
val validateContent by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "content/ YAML을 서버와 같은 로더로 검사한다"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = application.mainClass
    args("validate", rootProject.layout.projectDirectory.dir("content").asFile.absolutePath)
}
