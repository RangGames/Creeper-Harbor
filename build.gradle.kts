// Collects only the jars that go on the servers into one folder.
//   ./gradlew dist [-Ppack=<resource pack zip>]   (./gradlew shadowJar and ./gradlew build also run dist at the end)
// Output:
//   build/dist/purpur/plugins/    Harbor-Core (with Common and default content) + 11 game modules + Harbor-Skript (Skript integration, optional) + Harbor-Web (webhooks/REST, optional)
//   build/dist/velocity/plugins/  Harbor-Proxy (with Common, Lettuce, etc.)
// harbor-common is not added separately since it is inside the Core and Proxy jars. harbor-tools is a CI CLI and is excluded.
// Game modules have no libraries to bundle (Core provides the runtime), so they have no shadowJar and the plain jar is the plugin.
plugins {
    base
}

val gameModules =
    listOf(
        "jobs", "fishing", "collection", "npc", "quest", "economy",
        "world", "boss", "season", "shop", "wildlife", "skript", "web",
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

// Core/Proxy: the shadowJar output of the shadow plugin
val shadedPurpurPlugins by configurations.creating { pluginJarAttributes(shadowed = true) }
val shadedVelocityPlugins by configurations.creating { pluginJarAttributes(shadowed = true) }

// Game modules: plain jar
val modulePlugins by configurations.creating { pluginJarAttributes(shadowed = false) }

dependencies {
    shadedPurpurPlugins(project(":harbor-core"))
    shadedVelocityPlugins(project(":harbor-proxy"))
    gameModules.forEach { modulePlugins(project(":harbor-$it")) }
}

// harbor-fishing-0.1.0-SNAPSHOT.jar → harbor-fishing.jar (so the server only needs an overwrite)
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

// Builds the resource pack zip. Bundles the pack folder outside the repo (assets/harbor/{items,models,textures,sounds,lang}, sounds.json)
// together with pack.mcmeta and pack.png into one zip (including the chapter 5 sounds of '15. Sound').
//   ./gradlew resourcePack -PpackDir=<pack folder> [-PpackMeta=<zip or folder with pack.mcmeta/pack.png>] [-PpackName=<name>] [-PpackFormat=97.1]
//   packDir    folder with assets directly below it (or inside a folder one level down). Server-side rig data (assets/*/rigs) is left out of the client pack
//   packMeta   where to take pack.mcmeta/pack.png from (e.g. a previously built pack zip). If absent, packDir's copy; if that is absent too, a default pack.mcmeta is generated
//   packFormat min/max_format of the default pack.mcmeta (26.3 = 97.1)
// Output: build/resourcepack/<name>.zip + <name>.zip.sha1. File timestamps and order are fixed, so the same input gives the same SHA-1.
// Then regenerate content/resource_pack.yml with ./gradlew :harbor-tools:packIndex -Ppack=<that zip>.
val packDir = providers.gradleProperty("packDir").map { file(it) }
val packMeta = providers.gradleProperty("packMeta").map { file(it) }
val packName = providers.gradleProperty("packName").orElse("Creeper-Harbor-RP")
val packFormat = providers.gradleProperty("packFormat").orElse("97.1")

/** Finds the assets folder in the pack folder (directly below, otherwise inside a folder one level down). */
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
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE   // whatever is added first (packMeta) wins
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
            exclude("*/rigs/**")                                          // server-side rig data
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

// With -Ppack=<resource pack zip>, puts the pack and its SHA-1 in build/dist/resourcepack/ (the values for lobby harbor-infra.yml resource_packs).
// With -PpackDir=<pack folder> and no -Ppack, builds the zip via resourcePack and puts it in the same place.
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

// Make ./gradlew shadowJar produce the same folder as well
tasks.register("shadowJar") {
    group = "distribution"
    description = "모든 하위 프로젝트 shadowJar 뒤에 dist 실행"
    dependsOn(dist)
}
