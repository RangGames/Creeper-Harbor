// Purpur에서 도는 Paper 플러그인 공통 설정. paper-plugin.yml의 ${version}, ${apiVersion}을 채운다.
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
