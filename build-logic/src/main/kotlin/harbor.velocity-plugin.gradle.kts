// Velocity 플러그인 공통 설정. Velocity에는 PluginLoader가 없어서 필요한 라이브러리를 jar에 넣는다.
plugins {
    id("harbor.java")
    id("com.gradleup.shadow")
    id("xyz.jpenilla.run-velocity")
}

val libs = the<VersionCatalogsExtension>().named("libs")

dependencies {
    compileOnly(libs.findLibrary("velocity-api").get())
    annotationProcessor(libs.findLibrary("velocity-api").get())
}

tasks.runVelocity {
    velocityVersion(libs.findVersion("velocity").get().requiredVersion)
}

tasks.assemble {
    dependsOn(tasks.shadowJar)
}
