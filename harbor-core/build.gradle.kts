// Harbor-Core: the shared Purpur plugin every game module relies on. Bundles Harbor-Common inside its jar.
plugins {
    id("harbor.paper-plugin")
    id("harbor.shadow")
    id("harbor.run-paper")
}

// Rig playback uses Display transforms (JOML), whose jar's old class format warning (classfile) trips -Werror; silence only that warning
tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.add("-Xlint:-classfile")
}

dependencies {
    api(project(":harbor-common"))
    compileOnly(libs.placeholderapi)
    testImplementation(libs.snakeyaml)
    testImplementation(libs.gson)
}

// External libraries are not bundled; the PluginLoader downloads them from Maven at startup.
// The list is generated from the version catalog (server-runtime bundle) and added as a resource.
val generateLibraryList = tasks.register("generateLibraryList") {
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

// Bundle the repository's content/ into the jar as default content (harbor-content/ plus the file list harbor-content.txt).
// On startup Core extracts only the files missing from plugins/Harbor/content (DefaultContent)
val bundleDefaultContent = tasks.register<Sync>("bundleDefaultContent") {
    val outputRoot = layout.buildDirectory.dir("generated/harbor-default-content")
    from(rootProject.layout.projectDirectory.dir("content")) { into("harbor-content") }
    into(outputRoot)
    doLast {
        val root = outputRoot.get().dir("harbor-content").asFile
        val files = root.walkTopDown()
            .filter { it.isFile }
            .map { it.relativeTo(root).invariantSeparatorsPath }
            .sorted()
            .toList()
        outputRoot.get().file("harbor-content.txt").asFile
            .writeText(files.joinToString("\n", postfix = "\n"))
    }
}

sourceSets.main {
    resources.srcDir(generateLibraryList)
    resources.srcDir(bundleDefaultContent)
}

tasks.shadowJar {
    archiveClassifier = ""
    dependencies {
        // Bundle only Common; the PluginLoader loads the rest
        include(project(":harbor-common"))
    }
}

tasks.jar {
    archiveClassifier = "thin"
}

tasks.assemble {
    dependsOn(tasks.shadowJar)
}

// ---------------------------------------------------------------- local test server
// ./gradlew :harbor-core:runServer → runs a test server with every Purpur module (configure DB/Redis in harbor-infra.yml)
val devPlugins = configurations.create("devPlugins") {
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

val syncDevContent = tasks.register<Sync>("syncDevContent") {
    from(rootProject.layout.projectDirectory.dir("content"))
    into(layout.projectDirectory.dir("run/plugins/Harbor/content"))
}

tasks.runServer {
    minecraftVersion(libs.versions.minecraft.get())
    pluginJars.from(devPlugins)
    dependsOn(syncDevContent)
    jvmArgs("-Dharbor.dev=true", "-Dcom.mojang.eula.agree=true")
}
