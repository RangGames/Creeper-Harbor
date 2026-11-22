// Harbor-Boss: boss fights (docs 20 ch. 10)
plugins {
    id("harbor.paper-module")
}

dependencies {
    compileOnly(project(":harbor-fishing"))
    compileOnly(project(":harbor-season"))
    compileOnly(project(":harbor-npc"))
    compileOnly(project(":harbor-shop"))
    testImplementation(project(":harbor-fishing"))
    testImplementation(project(":harbor-season"))
}

// JOML (Display transform matrices, telegraph floor panels) uses an old class file format that triggers javac classfile warnings; silence only that warning
tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.add("-Xlint:-classfile")
}
