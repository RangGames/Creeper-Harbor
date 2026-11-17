// Harbor-Economy: 상점·경매·시세
plugins {
    id("harbor.paper-module")
}

dependencies {
    compileOnly(project(":harbor-jobs"))
    testImplementation(project(":harbor-jobs"))
}
