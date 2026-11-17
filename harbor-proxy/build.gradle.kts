// Harbor-Proxy: Velocity 플러그인. Velocity에는 PluginLoader가 없어서 Common과 그 런타임 라이브러리
// (Lettuce, HikariCP, MariaDB, Flyway)를 jar에 넣고 패키지를 옮긴다. Netty·SLF4J·Gson·SnakeYAML은 Velocity가 제공한다.
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
    dependencies {
        exclude(dependency("io.netty:.*"))
        exclude(dependency("org.slf4j:.*"))
        exclude(dependency("com.google.code.gson:.*"))
        exclude(dependency("org.yaml:.*"))
        exclude(dependency("org.jspecify:.*"))
    }
    val lib = "wiki.creeper.harbor.proxy.lib"
    relocate("io.lettuce", "$lib.lettuce")
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
