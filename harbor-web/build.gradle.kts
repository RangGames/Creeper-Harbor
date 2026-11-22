// Harbor-Web: external integration (proposed). Game events → Redis harbor:events, webhook delivery (Discord, JSON), read-only REST API.
// Game module events are subscribed by class name (only when the module is enabled), so the event side depends on Core only.
// Per-module REST queries (market, auctions, records, rankings) live in bridge classes loaded only when that module is enabled.
plugins {
    id("harbor.paper-module")
}

dependencies {
    compileOnly(project(":harbor-economy"))
    compileOnly(project(":harbor-collection"))
    compileOnly(project(":harbor-season"))
    // Tests: verify that the classes in the event list actually exist
    listOf(
        "jobs", "fishing", "collection", "npc", "quest", "economy",
        "world", "boss", "season", "shop", "wildlife",
    ).forEach { testImplementation(project(":harbor-$it")) }
}

tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.add("-Xlint:-classfile")
}
