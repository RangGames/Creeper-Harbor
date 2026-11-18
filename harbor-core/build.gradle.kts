// Harbor-Core: 모든 게임 모듈이 기대는 Purpur 공통 플러그인. Harbor-Common을 jar 안에 품는다.
plugins {
    id("harbor.paper-plugin")
    id("harbor.shadow")
    id("harbor.run-paper")
}

// 리그 재생이 Display 변환(JOML)을 쓰는데, JOML jar의 옛 class 형식 경고(classfile)가 -Werror에 걸린다. 그 경고만 끈다
tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.add("-Xlint:-classfile")
}

dependencies {
    api(project(":harbor-common"))
    testImplementation(libs.snakeyaml)
    testImplementation(libs.gson)
}

// 외부 라이브러리는 jar에 넣지 않고 PluginLoader가 시작할 때 Maven에서 받는다.
// 목록은 버전 카탈로그(server-runtime 묶음)에서 만들어 리소스로 넣는다.
val generateLibraryList by tasks.registering {
    val coordinates = libs.bundles.server.runtime.get().map {
        "${it.module.group}:${it.module.name}:${it.versionConstraint.requiredVersion}"
    }
    val outputDir = layout.buildDirectory.dir("generated/harbor-libraries")
    inputs.property("coordinates", coordinates)
    outputs.dir(outputDir)
    doLast {
        val file = outputDir.get().file("harbor-libraries.txt").asFile
        file.parentFile.mkdirs()
        file.writeText(coordinates.joinToString("\n", postfix = "\n"))
    }
}

sourceSets.main {
    resources.srcDir(generateLibraryList)
}

tasks.shadowJar {
    archiveClassifier = ""
    dependencies {
        // Common만 품는다. 나머지는 PluginLoader가 적재
        include(project(":harbor-common"))
    }
}

tasks.jar {
    archiveClassifier = "thin"
}

tasks.assemble {
    dependsOn(tasks.shadowJar)
}

// ---------------------------------------------------------------- 로컬 테스트 서버
// ./gradlew :harbor-core:runServer → 모든 Purpur 모듈을 넣은 테스트 서버 실행 (DB·Redis는 harbor-infra.yml에 설정)
val devPlugins by configurations.creating {
    isCanBeConsumed = false
    isTransitive = false
    attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
        attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.JAR))
    }
}

dependencies {
    listOf(
        "jobs", "fishing", "collection", "npc", "quest", "economy",
        "world", "boss", "season", "shop", "wildlife",
    ).forEach { devPlugins(project(":harbor-$it")) }
}

val syncDevContent by tasks.registering(Sync::class) {
    from(rootProject.layout.projectDirectory.dir("content"))
    into(layout.projectDirectory.dir("run/plugins/Harbor/content"))
}

tasks.runServer {
    minecraftVersion(libs.versions.minecraft.get())
    pluginJars.from(devPlugins)
    dependsOn(syncDevContent)
    jvmArgs("-Dharbor.dev=true", "-Dcom.mojang.eula.agree=true")
}
