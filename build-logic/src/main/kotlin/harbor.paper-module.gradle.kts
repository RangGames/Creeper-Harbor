// Harbor-Core에 기대는 게임 모듈 (Jobs, Fishing, ...). Core의 클래스(Common 포함)를 같이 쓴다.
plugins {
    id("harbor.paper-plugin")
}

dependencies {
    compileOnly(project(":harbor-core"))
    testImplementation(project(":harbor-core"))
}
