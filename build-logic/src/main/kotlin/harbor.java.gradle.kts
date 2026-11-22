import net.ltgt.gradle.errorprone.errorprone
import net.ltgt.gradle.nullaway.nullaway

// Shared by all Harbor modules: Java 25, ErrorProne + NullAway, Spotless, JUnit 5, warnings = errors
plugins {
    `java-library`
    id("net.ltgt.errorprone")
    id("net.ltgt.nullaway")
    id("com.diffplug.spotless")
}

val libs = the<VersionCatalogsExtension>().named("libs")

fun lib(alias: String) = libs.findLibrary(alias).get()

group = "wiki.creeper.harbor"
version = "0.1.0-SNAPSHOT"

java {
    toolchain.languageVersion = JavaLanguageVersion.of(libs.findVersion("java").get().requiredVersion)
}

dependencies {
    errorprone(lib("errorprone-core"))
    errorprone(lib("nullaway"))
    compileOnlyApi(lib("jspecify"))
    testCompileOnly(lib("jspecify"))
    testImplementation(platform(lib("junit-bom")))
    testImplementation(lib("junit-jupiter"))
    testRuntimeOnly(lib("junit-launcher"))
}

nullaway {
    annotatedPackages.add("wiki.creeper.harbor")
}

tasks.withType<JavaCompile>().configureEach {
    val isTestCompile = name.contains("Test")
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(
        listOf("-Xlint:all,-processing,-serial,-this-escape", "-Werror", "-parameters"),
    )
    options.errorprone {
        disableWarningsInGeneratedCode = true
        // The English summary-sentence rule does not fit Korean Javadoc
        disable("MissingSummary")
        nullaway {
            // Test code is excluded from null checks (enforced on main code only)
            if (isTestCompile) disable() else error()
        }
    }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

spotless {
    // The main dev environment is Windows, so Java sources are stored as CRLF. Normalize to the same line endings on other OSes too
    lineEndings = com.diffplug.spotless.LineEnding.WINDOWS
    java {
        target("src/*/java/**/*.java")
        googleJavaFormat().aosp()
        removeUnusedImports()
        trimTrailingWhitespace()
        endWithNewline()
    }
}
