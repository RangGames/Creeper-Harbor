// Harbor-Collection: 도감·수족관
plugins {
    id("harbor.paper-module")
}

dependencies {
    compileOnly(project(":harbor-fishing"))
    compileOnly(project(":harbor-jobs"))
    testImplementation(project(":harbor-fishing"))
}
