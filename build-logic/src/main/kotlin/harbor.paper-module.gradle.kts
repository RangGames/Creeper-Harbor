// Game modules that rely on Harbor-Core (Jobs, Fishing, ...). They share Core's classes (including Common).
plugins {
    id("harbor.paper-plugin")
}

dependencies {
    compileOnly(project(":harbor-core"))
    testImplementation(project(":harbor-core"))
}
