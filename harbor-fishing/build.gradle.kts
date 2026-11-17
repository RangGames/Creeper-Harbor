// Harbor-Fishing: 낚시·미니게임·통발
plugins {
    id("harbor.paper-module")
}

dependencies {
    compileOnly(project(":harbor-jobs"))
    testImplementation(project(":harbor-jobs"))
}
