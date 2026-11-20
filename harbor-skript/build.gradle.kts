// Harbor-Skript: Skript 애드온 (추가 제안). Skript가 있을 때만 문법을 등록한다. 재화·아이템은 Harbor API(원장 등)로만 다룬다.
// 게임 모듈 문법은 그 모듈이 켜져 있을 때만 불러오는 bridge 클래스(bridge/*Syntax)에 있어서, 모듈 api는 compileOnly로만 쓴다.
plugins {
    id("harbor.paper-module")
}

dependencies {
    compileOnly(libs.skript) { isTransitive = false }
    listOf("jobs", "fishing", "collection", "npc", "quest", "economy", "world", "season")
        .forEach { compileOnly(project(":harbor-$it")) }
}

// Skript jar의 FindBugs 애너테이션이 없어서 나는 class 형식 경고(classfile)가 -Werror에 걸린다. 그 경고만 끈다
tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.add("-Xlint:-classfile")
}
