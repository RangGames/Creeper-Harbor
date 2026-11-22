// Shared settings for Paper plugins running on Purpur. Fills ${version} and ${apiVersion} in paper-plugin.yml.
plugins {
    id("harbor.java")
}

val libs = the<VersionCatalogsExtension>().named("libs")

dependencies {
    compileOnly(libs.findLibrary("purpur-api").get())
    testImplementation(libs.findLibrary("purpur-api").get())
}

tasks.processResources {
    val props = mapOf(
        "version" to project.version.toString(),
        "apiVersion" to libs.findVersion("minecraft").get().requiredVersion,
    )
    inputs.properties(props)
    filesMatching("paper-plugin.yml") { expand(props) }
}
