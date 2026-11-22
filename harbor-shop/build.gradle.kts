// Harbor-Shop: pearls (paid currency), cosmetics, season pass, donor tiers, Tebex payment intake (docs 20 ch. 12)
plugins {
    id("harbor.paper-module")
}

dependencies {
    // Required: catch presentation (Fishing api), commemorative skin conditions and collection cosmetics (Collection api), season clock, transition hooks and titles (Season api)
    compileOnly(project(":harbor-fishing"))
    compileOnly(project(":harbor-jobs"))
    compileOnly(project(":harbor-collection"))
    compileOnly(project(":harbor-season"))
    // Optional: World api (registers equipped ship skins via ShipSkinProvider)
    compileOnly(project(":harbor-world"))
    testImplementation(project(":harbor-fishing"))
    testImplementation(project(":harbor-jobs"))
    testImplementation(project(":harbor-collection"))
    testImplementation(project(":harbor-season"))
}

// JOML (Display transform matrices) uses an old class file format that triggers javac classfile warnings; silence only that warning
tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.add("-Xlint:-classfile")
}
