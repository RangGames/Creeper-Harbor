// Harbor-Tools: content validation CLI (run in CI). Uses the same loader code as the server plugins.
plugins {
    id("harbor.java")
    application
}

dependencies {
    implementation(project(":harbor-common"))
    // Module content types (only classes that do not use the server API are loaded)
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

// ./gradlew validateContent → validates the content/ folder. Fails on errors
val validateContent by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "content/ YAML을 서버와 같은 로더로 검사한다"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = application.mainClass
    args("validate", rootProject.layout.projectDirectory.dir("content").asFile.absolutePath)
}

// ./gradlew packIndex -Ppack=<resource pack folder or zip> → content/resource_pack.yml (item_model and sound lists in the pack)
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

// ./gradlew rigImport -Prigs=<resource pack assets/harbor/rigs/fish folder> [-PrigStep=<ticks>] → content/rigs/fish/<name>.yml (fish rig conversion)
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
