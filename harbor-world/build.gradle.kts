// Harbor-World: regions, sea charts, ships, housing, sea events
plugins {
    id("harbor.paper-module")
}

dependencies {
    // Required: Jobs (level, tier), Fishing (RegionKeyResolver and TreasureDropHook implementations, SeaEventMessage)
    compileOnly(project(":harbor-jobs"))
    compileOnly(project(":harbor-fishing"))
    // Optional: Economy (ShopService.charge, RepairService), NPC (dialog actions, best reputation), Season (tournament ranking source)
    compileOnly(project(":harbor-economy"))
    compileOnly(project(":harbor-npc"))
    compileOnly(project(":harbor-season"))
    // Does not depend on Quest, Collection or Shop (docs 20 0.3); they use the World api instead (registering RegionProgressProvider, ShipSkinProvider)
    testImplementation(project(":harbor-fishing"))
    testImplementation(project(":harbor-jobs"))
}

// JOML (Display transform matrices) uses an old class file format that triggers javac classfile warnings; silence only that warning
tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.add("-Xlint:-classfile")
}
