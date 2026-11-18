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
    // World·Boss는 선택 의존이라 클래스를 직접 쓰지 않는다 (OptionalBridges가 이벤트 이름으로 연결)
}

// Fishing api가 JOML(Display 변환 행렬)을 끌고 와서 javac classfile 경고가 난다. 그 경고만 끈다
tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.add("-Xlint:-classfile")
}
