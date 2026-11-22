// Harbor-Collection: collection, aquarium, personal tanks, release
plugins {
    id("harbor.paper-module")
}

dependencies {
    compileOnly(project(":harbor-fishing"))
    compileOnly(project(":harbor-jobs"))
    // Required: titles, rankings, hall of fame, season hooks (Season api)
    compileOnly(project(":harbor-season"))
    // Optional: personal tank DisplayAnchor and edit permission (World api). Shop depends on Collection, so it is only queried via the AquariumThemeOwnership service
    compileOnly(project(":harbor-world"))
    // Optional: dialog actions (aquarium:donate, collection:hint), best reputation tier (tank slots), release reputation
    compileOnly(project(":harbor-npc"))
    testImplementation(project(":harbor-fishing"))
    testImplementation(project(":harbor-season"))
}

// JOML (Display transform matrices) uses an old class file format that triggers javac classfile warnings; silence only that warning
tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.add("-Xlint:-classfile")
}
