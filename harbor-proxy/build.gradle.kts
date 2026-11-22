// Harbor-Proxy: Velocity plugin. Velocity has no PluginLoader, so Common and its runtime libraries
// (Lettuce, HikariCP, MariaDB, Flyway) go into the jar with relocated packages. Netty (except the DNS resolver), SLF4J, Gson and SnakeYAML are provided by Velocity.
plugins {
    id("harbor.velocity-plugin")
}

dependencies {
    implementation(project(":harbor-common"))
    testImplementation(libs.snakeyaml)
    testImplementation(libs.gson)
}

tasks.shadowJar {
    archiveClassifier = ""
    mergeServiceFiles()
    // Shadow 9 drops duplicate files by default, so mergeServiceFiles only sees the first jar's service file.
    // flyway-core and flyway-mysql both have the same Plugin service file, so both must be merged
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
    filesNotMatching(listOf("META-INF/services/**", "META-INF/*.kotlin_module")) { duplicatesStrategy = DuplicatesStrategy.EXCLUDE }
    dependencies {
        // Netty is provided by Velocity. But the DNS resolver Lettuce uses (netty-resolver-dns, netty-codec-dns) is not in Velocity, so it is bundled
        exclude {
            it.moduleGroup == "io.netty" &&
                it.moduleName !in setOf("netty-resolver-dns", "netty-codec-dns")
        }
        exclude(dependency("org.slf4j:.*"))
        exclude(dependency("com.google.code.gson:.*"))
        exclude(dependency("org.yaml:.*"))
        exclude(dependency("org.jspecify:.*"))
    }
    val lib = "wiki.creeper.harbor.proxy.lib"
    relocate("io.lettuce", "$lib.lettuce")
    relocate("io.netty.resolver.dns", "$lib.netty.resolver.dns")
    relocate("io.netty.handler.codec.dns", "$lib.netty.codec.dns")
    relocate("reactor", "$lib.reactor")
    relocate("org.reactivestreams", "$lib.reactivestreams")
    relocate("com.zaxxer.hikari", "$lib.hikari")
    relocate("org.mariadb", "$lib.mariadb")
    relocate("org.flywaydb", "$lib.flywaydb")
    relocate("com.fasterxml", "$lib.fasterxml")
    relocate("redis.clients", "$lib.redisclients")
}

tasks.jar {
    archiveClassifier = "thin"
}
