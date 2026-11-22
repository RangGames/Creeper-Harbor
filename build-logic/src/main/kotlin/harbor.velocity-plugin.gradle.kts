// Shared settings for Velocity plugins. Velocity has no PluginLoader, so required libraries go into the jar.
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
