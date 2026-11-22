// Harbor-Fishing: fishing, minigames, traps
plugins {
    id("harbor.paper-module")
}

dependencies {
    compileOnly(project(":harbor-jobs"))
    testImplementation(project(":harbor-jobs"))
    // Optional dependency: clean up trap blocks on season transition (SeasonHook)
    compileOnly(project(":harbor-season"))
}

// JOML (Display transform matrices) uses an old class file format that triggers javac classfile warnings; silence only that warning
tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.add("-Xlint:-classfile")
}
