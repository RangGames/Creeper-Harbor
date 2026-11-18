// Harbor-Quest: 퀘스트·의뢰 게시판·항구 연대기·서버 진행도
plugins {
    id("harbor.paper-module")
}

dependencies {
    // 필수 의존 (docs 20 6.2, 0.3): 각 모듈의 api 패키지만 쓴다
    compileOnly(project(":harbor-npc"))
    compileOnly(project(":harbor-fishing"))
    compileOnly(project(":harbor-collection"))
    compileOnly(project(":harbor-jobs"))
    compileOnly(project(":harbor-season"))
    // 선택: World api (해금한 가장 높은 지역을 World 판정으로 등록). Boss는 OptionalBridges가 이벤트 이름으로 연결
    compileOnly(project(":harbor-world"))
}

// Fishing api가 JOML(Display 변환 행렬)을 끌고 와서 javac classfile 경고가 난다. 그 경고만 끈다
tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.add("-Xlint:-classfile")
}
