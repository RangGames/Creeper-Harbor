// Harbor-Skript: Skript addon (proposed). Registers syntax only when Skript is present. Currency and items go through the Harbor API (ledger etc.) only.
// Game module syntax lives in bridge classes (bridge/*Syntax) loaded only when that module is enabled, so module apis are compileOnly.
plugins {
    id("harbor.paper-module")
}

dependencies {
    compileOnly(libs.skript) { isTransitive = false }
    listOf("jobs", "fishing", "collection", "npc", "quest", "economy", "world", "season", "boss", "shop", "wildlife")
        .forEach { compileOnly(project(":harbor-$it")) }
}

// Missing FindBugs annotations in the Skript jar cause class format warnings (classfile) that trip -Werror; silence only that warning
tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.add("-Xlint:-classfile")
}
