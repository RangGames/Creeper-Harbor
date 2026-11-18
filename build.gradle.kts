// 서버에 넣을 jar만 한 폴더로 모은다.
//   ./gradlew dist   (./gradlew shadowJar, ./gradlew build도 마지막에 dist를 실행)
// 결과:
//   build/dist/purpur/plugins/    Harbor-Core(Common 포함) + 게임 모듈 11개 + Harbor/content/
//   build/dist/velocity/plugins/  Harbor-Proxy(Common·Lettuce 등 포함)
// harbor-common은 Core·Proxy jar 안에 들어 있으므로 따로 넣지 않는다. harbor-tools는 CI용 CLI라 제외한다.
// 게임 모듈은 Core가 런타임을 제공해서 품을 라이브러리가 없으므로 shadowJar가 없고 일반 jar가 곧 플러그인이다.
plugins {
    base
}

val gameModules =
    listOf(
        "jobs", "fishing", "collection", "npc", "quest", "economy",
        "world", "boss", "season", "shop", "wildlife",
    )

fun Configuration.pluginJarAttributes(shadowed: Boolean) {
    isCanBeConsumed = false
    isTransitive = false
    attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
        attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.LIBRARY))
        attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.JAR))
        attribute(
            Bundling.BUNDLING_ATTRIBUTE,
            objects.named(if (shadowed) Bundling.SHADOWED else Bundling.EXTERNAL),
        )
    }
}

// Core·Proxy: shadow 플러그인이 내놓는 shadowJar 결과물
val shadedPurpurPlugins by configurations.creating { pluginJarAttributes(shadowed = true) }
val shadedVelocityPlugins by configurations.creating { pluginJarAttributes(shadowed = true) }

// 게임 모듈: 일반 jar
val modulePlugins by configurations.creating { pluginJarAttributes(shadowed = false) }

dependencies {
    shadedPurpurPlugins(project(":harbor-core"))
    shadedVelocityPlugins(project(":harbor-proxy"))
    gameModules.forEach { modulePlugins(project(":harbor-$it")) }
}

// harbor-fishing-0.1.0-SNAPSHOT.jar → harbor-fishing.jar (서버에서 덮어쓰기만 하면 되게)
val versionSuffix = Regex("-\\d[\\w.]*(-SNAPSHOT)?\\.jar$")

val dist by tasks.registering(Sync::class) {
    group = "distribution"
    description = "서버에 넣을 플러그인 jar를 build/dist/{purpur,velocity}/plugins에 모은다"
    into(layout.buildDirectory.dir("dist"))
    into("purpur/plugins") {
        from(shadedPurpurPlugins, modulePlugins)
        rename { it.replace(versionSuffix, ".jar") }
    }
    into("purpur/plugins/Harbor/content") {
        from(layout.projectDirectory.dir("content"))
    }
    into("velocity/plugins") {
        from(shadedVelocityPlugins)
        rename { it.replace(versionSuffix, ".jar") }
    }
}

tasks.assemble { dependsOn(dist) }

// ./gradlew shadowJar 로도 같은 폴더가 만들어지게 한다
tasks.register("shadowJar") {
    group = "distribution"
    description = "모든 하위 프로젝트 shadowJar 뒤에 dist 실행"
    dependsOn(dist)
}
