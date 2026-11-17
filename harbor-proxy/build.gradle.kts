// Harbor-Proxy: Velocity 플러그인. Common과 Redis 클라이언트(Lettuce)를 jar에 품는다.
plugins {
    id("harbor.velocity-plugin")
}

dependencies {
    implementation(project(":harbor-common"))
}

tasks.shadowJar {
    archiveClassifier = ""
    dependencies {
        include(project(":harbor-common"))
        include(dependency("io.lettuce:lettuce-core"))
        include(dependency("io.projectreactor:reactor-core"))
        include(dependency("org.reactivestreams:reactive-streams"))
        // Netty는 Velocity가 제공한다
    }
    relocate("io.lettuce", "wiki.creeper.harbor.lib.lettuce")
    relocate("reactor", "wiki.creeper.harbor.lib.reactor")
    relocate("org.reactivestreams", "wiki.creeper.harbor.lib.reactivestreams")
}

tasks.jar {
    archiveClassifier = "thin"
}
