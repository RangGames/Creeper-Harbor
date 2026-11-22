// Harbor-Economy: shops, auctions, market prices, crafting
plugins {
    id("harbor.paper-module")
}

dependencies {
    // docs 20 0.3: Jobs and Fishing required, NPC and Season (WeeklyLimitService) required (as directed); World and Quest are connected only via events and flags
    compileOnly(project(":harbor-jobs"))
    compileOnly(project(":harbor-fishing"))
    compileOnly(project(":harbor-npc"))
    compileOnly(project(":harbor-season"))
    testImplementation(project(":harbor-jobs"))
    testImplementation(project(":harbor-fishing"))
    testImplementation(project(":harbor-npc"))
    testImplementation(project(":harbor-season"))
}
