// Harbor-Quest: quests, request board, harbor chronicle, server progress
plugins {
    id("harbor.paper-module")
}

dependencies {
    // Required dependencies (docs 20 6.2, 0.3): only each module's api package is used
    compileOnly(project(":harbor-npc"))
    compileOnly(project(":harbor-fishing"))
    compileOnly(project(":harbor-collection"))
    compileOnly(project(":harbor-jobs"))
    compileOnly(project(":harbor-season"))
    // Optional: World api (registers the highest unlocked region using World's check). Boss is connected by event name through OptionalBridges
    compileOnly(project(":harbor-world"))
}

// The Fishing api pulls in JOML (Display transform matrices), which triggers javac classfile warnings; silence only that warning
tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.add("-Xlint:-classfile")
}
