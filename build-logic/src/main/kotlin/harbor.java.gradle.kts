import net.ltgt.gradle.errorprone.errorprone
import net.ltgt.gradle.nullaway.nullaway

// 모든 Harbor 모듈 공통: Java 25, ErrorProne + NullAway, Spotless, JUnit 5, 경고 = 오류
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
        // 한국어 Javadoc에는 영어 요약 문장 규칙이 맞지 않는다
        disable("MissingSummary")
        nullaway {
            // 테스트 코드는 널 검사에서 제외 (본 코드만 강제)
            if (isTestCompile) disable() else error()
        }
    }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

spotless {
    // 주 개발 환경이 Windows라 Java 소스는 CRLF로 저장한다. 다른 OS에서도 같은 줄바꿈으로 고친다
    lineEndings = com.diffplug.spotless.LineEnding.WINDOWS
    java {
        target("src/*/java/**/*.java")
        googleJavaFormat().aosp()
        removeUnusedImports()
        trimTrailingWhitespace()
        endWithNewline()
    }
}
