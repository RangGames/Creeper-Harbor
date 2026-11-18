// Harbor-Proxy: Velocity 플러그인. Velocity에는 PluginLoader가 없어서 Common과 그 런타임 라이브러리
// (Lettuce, HikariCP, MariaDB, Flyway)를 jar에 넣고 패키지를 옮긴다. Netty(DNS 리졸버 제외)·SLF4J·Gson·SnakeYAML은 Velocity가 제공한다.
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
    // Shadow 9는 중복 파일을 기본으로 버려서 mergeServiceFiles가 첫 jar의 서비스 파일만 본다.
    // Flyway는 flyway-core와 flyway-mysql이 같은 Plugin 서비스 파일을 갖고 있어 둘 다 합쳐야 한다
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
    filesNotMatching(listOf("META-INF/services/**", "META-INF/*.kotlin_module")) { duplicatesStrategy = DuplicatesStrategy.EXCLUDE }
    dependencies {
        // Netty는 Velocity가 제공한다. 단 Lettuce가 쓰는 DNS 리졸버(netty-resolver-dns·netty-codec-dns)는 Velocity에 없어서 넣는다
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
