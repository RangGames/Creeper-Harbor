// 서버에 넣을 jar만 한 폴더로 모은다.
//   ./gradlew dist [-Ppack=<리소스팩 zip>]   (./gradlew shadowJar, ./gradlew build도 마지막에 dist를 실행)
// 결과:
//   build/dist/purpur/plugins/    Harbor-Core(Common·기본 콘텐츠 포함) + 게임 모듈 11개 + Harbor-Skript(Skript 연동, 선택)
//   build/dist/velocity/plugins/  Harbor-Proxy(Common·Lettuce 등 포함)
// harbor-common은 Core·Proxy jar 안에 들어 있으므로 따로 넣지 않는다. harbor-tools는 CI용 CLI라 제외한다.
// 게임 모듈은 Core가 런타임을 제공해서 품을 라이브러리가 없으므로 shadowJar가 없고 일반 jar가 곧 플러그인이다.
plugins {
    base
}

val gameModules =
    listOf(
        "jobs", "fishing", "collection", "npc", "quest", "economy",
        "world", "boss", "season", "shop", "wildlife", "skript",
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
    into("velocity/plugins") {
        from(shadedVelocityPlugins)
        rename { it.replace(versionSuffix, ".jar") }
    }
}

fun sha1Of(file: File): String =
    java.security.MessageDigest.getInstance("SHA-1")
        .digest(file.readBytes())
        .joinToString("") { "%02x".format(it) }

// 리소스팩 zip 만들기. 저장소 밖에 있는 팩 폴더(assets/harbor/{items,models,textures,sounds,lang}, sounds.json)를
// pack.mcmeta·pack.png와 함께 한 zip으로 묶는다 ('15. 사운드' 5장 소리 포함).
//   ./gradlew resourcePack -PpackDir=<팩 폴더> [-PpackMeta=<pack.mcmeta·pack.png가 든 zip 또는 폴더>] [-PpackName=<이름>] [-PpackFormat=97.1]
//   packDir    assets가 바로 아래(또는 한 단계 아래 폴더 안)에 있는 폴더. 서버용 리그 데이터(assets/*/rigs)는 클라이언트 팩에서 뺀다
//   packMeta   pack.mcmeta·pack.png를 가져올 곳 (예: 예전에 만든 팩 zip). 없으면 packDir의 것, 그것도 없으면 기본 pack.mcmeta를 만든다
//   packFormat 기본 pack.mcmeta의 min/max_format (26.3 = 97.1)
// 결과: build/resourcepack/<이름>.zip + <이름>.zip.sha1. 파일 시각·순서를 고정해서 같은 입력이면 SHA-1이 같다.
// 그 뒤 ./gradlew :harbor-tools:packIndex -Ppack=<그 zip>으로 content/resource_pack.yml을 다시 만든다.
val packDir = providers.gradleProperty("packDir").map { file(it) }
val packMeta = providers.gradleProperty("packMeta").map { file(it) }
val packName = providers.gradleProperty("packName").orElse("Creeper-Harbor-RP")
val packFormat = providers.gradleProperty("packFormat").orElse("97.1")

/** 팩 폴더에서 assets 폴더 찾기 (바로 아래, 없으면 한 단계 아래 폴더 안). */
fun assetsOf(dir: File): File? =
    dir.resolve("assets").takeIf { it.isDirectory }
        ?: dir.listFiles()?.sortedBy { it.name }?.map { it.resolve("assets") }?.firstOrNull { it.isDirectory }

val packMetaDefault = tasks.register("packMetaDefault") {
    description = "기본 pack.mcmeta를 만든다 (resourcePack에 pack.mcmeta가 없을 때)"
    val out = layout.buildDirectory.dir("generated/resourcepack")
    val format = packFormat
    inputs.property("format", format)
    outputs.dir(out)
    doLast {
        val (major, minor) = format.get().split('.').let { it[0].toInt() to (it.getOrNull(1)?.toInt() ?: 0) }
        val file = out.get().file("pack.mcmeta").asFile
        file.parentFile.mkdirs()
        file.writeText(
            """
            |{
            |  "pack": {
            |    "description": {"text": "크리퍼 항구", "color": "aqua"},
            |    "min_format": [$major, $minor],
            |    "max_format": [$major, $minor]
            |  }
            |}
            |""".trimMargin(),
        )
    }
}

val resourcePack = tasks.register<Zip>("resourcePack") {
    group = "distribution"
    description = "리소스팩 zip을 만든다 (-PpackDir=<팩 폴더> [-PpackMeta=<zip 또는 폴더>]) → build/resourcepack/"
    archiveFileName = packName.map { "$it.zip" }
    destinationDirectory = layout.buildDirectory.dir("resourcepack")
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE   // 먼저 넣은 것(packMeta)이 이긴다
    val dir = packDir.orNull
    val meta = packMeta.orNull
    if (dir != null) {
        val assets = assetsOf(dir) ?: throw GradleException("-PpackDir에서 assets 폴더를 찾지 못했습니다: ${dir.name}")
        val metaFiles = listOf("pack.mcmeta", "pack.png")
        when {
            meta == null -> {}
            meta.isFile -> from(zipTree(meta)) { include(metaFiles) }
            meta.isDirectory -> from(meta) { include(metaFiles) }
            else -> throw GradleException("-PpackMeta가 zip이나 폴더가 아닙니다: ${meta.name}")
        }
        from(assets.parentFile) { include(metaFiles) }
        val hasMeta = (meta != null && (meta.isFile || meta.resolve("pack.mcmeta").isFile)) ||
            assets.parentFile.resolve("pack.mcmeta").isFile
        if (!hasMeta) {
            from(packMetaDefault)
        }
        from(assets) {
            into("assets")
            exclude("*/rigs/**")                                          // 서버용 리그 데이터
            exclude("**/Thumbs.db", "**/desktop.ini", "**/.DS_Store", "**/*_source.*")
        }
    }
    doFirst {
        if (dir == null) {
            throw GradleException("-PpackDir=<리소스팩 폴더>가 필요합니다")
        }
    }
    doLast {
        val zip = archiveFile.get().asFile
        val sha1 = sha1Of(zip)
        zip.resolveSibling("${zip.name}.sha1").writeText(sha1 + "\n")
        logger.lifecycle("리소스팩 ${zip.name} SHA-1: $sha1")
    }
}

// -Ppack=<리소스팩 zip>을 주면 build/dist/resourcepack/에 팩과 SHA-1을 같이 둔다 (lobby harbor-infra.yml resource_packs에 넣을 값).
// -Ppack 없이 -PpackDir=<팩 폴더>를 주면 resourcePack으로 zip을 만들어 같은 자리에 둔다.
val packFile = providers.gradleProperty("pack").map { file(it) }
dist {
    val pack: Any? =
        when {
            packFile.isPresent -> packFile.get().takeIf { it.isFile }
            packDir.isPresent -> resourcePack
            else -> null
        }
    if (pack != null) {
        into("resourcepack") { from(pack) }
        doLast {
            destinationDir.resolve("resourcepack").listFiles { f -> f.name.endsWith(".zip") }?.forEach { zip ->
                val sha1 = sha1Of(zip)
                zip.resolveSibling("${zip.name}.sha1").writeText(sha1 + "\n")
                logger.lifecycle("리소스팩 ${zip.name} SHA-1: $sha1")
            }
        }
    }
}

tasks.assemble { dependsOn(dist) }

// ./gradlew shadowJar 로도 같은 폴더가 만들어지게 한다
tasks.register("shadowJar") {
    group = "distribution"
    description = "모든 하위 프로젝트 shadowJar 뒤에 dist 실행"
    dependsOn(dist)
}
