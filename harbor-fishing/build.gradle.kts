// Harbor-Fishing: 낚시·미니게임·통발
plugins {
    id("harbor.paper-module")
}

dependencies {
    compileOnly(project(":harbor-jobs"))
    testImplementation(project(":harbor-jobs"))
    // 선택 의존: 시즌 전환 때 통발 블록 정리 (SeasonHook)
    compileOnly(project(":harbor-season"))
}

// JOML(Display 변환 행렬)은 옛 클래스 파일 형식이라 javac classfile 경고가 난다. 그 경고만 끈다
tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.add("-Xlint:-classfile")
}
