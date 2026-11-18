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
    implementation(project(":harbor-wildlife"))
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

// ./gradlew packIndex -Ppack=<리소스팩 폴더 또는 zip> → content/resource_pack.yml (팩에 있는 item_model·소리 목록)
val packIndex by tasks.registering(JavaExec::class) {
    group = "content"
    description = "리소스팩에서 content/resource_pack.yml을 만든다 (-Ppack=<폴더 또는 zip>)"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = application.mainClass
    val pack = providers.gradleProperty("pack")
    val contentDir = rootProject.layout.projectDirectory.dir("content").asFile.absolutePath
    argumentProviders.add(CommandLineArgumentProvider {
        listOf("pack-index", pack.orNull ?: throw GradleException("-Ppack=<리소스팩 폴더 또는 zip>이 필요합니다"), contentDir)
    })
}

// ./gradlew rigImport -Prigs=<리소스팩 assets/harbor/rigs/fish 폴더> [-PrigStep=<틱>] → content/rigs/fish/<이름>.yml (어종 리그 변환)
val rigImport by tasks.registering(JavaExec::class) {
    group = "content"
    description = "리소스팩 어종 리그 JSON(harbor_rig_v1)을 content/rigs/fish/*.yml로 바꾼다 (-Prigs=<폴더>)"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = application.mainClass
    val rigs = providers.gradleProperty("rigs")
    val step = providers.gradleProperty("rigStep")
    val contentDir = rootProject.layout.projectDirectory.dir("content").asFile.absolutePath
    argumentProviders.add(CommandLineArgumentProvider {
        listOf("rig-import", rigs.orNull ?: throw GradleException("-Prigs=<리그 JSON 폴더>가 필요합니다"), contentDir) +
            listOfNotNull(step.orNull)
    })
}
