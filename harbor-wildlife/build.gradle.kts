// Harbor-Wildlife: wild fish AI
plugins {
    id("harbor.paper-module")
}

dependencies {
    // Required: World (regions, sub-areas, fish schools, sea events), Fishing (species, bobber events, claimShadow, ShadowDriver). One direction: Wildlife → Fishing
    compileOnly(project(":harbor-world"))
    compileOnly(project(":harbor-fishing"))
    testImplementation(project(":harbor-fishing"))
}
