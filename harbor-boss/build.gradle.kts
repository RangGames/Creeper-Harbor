// Harbor-Boss: 보스전 (docs 20 10장)
plugins {
    id("harbor.paper-module")
}

dependencies {
    compileOnly(project(":harbor-fishing"))
    compileOnly(project(":harbor-season"))
    compileOnly(project(":harbor-npc"))
    compileOnly(project(":harbor-shop"))
    testImplementation(project(":harbor-fishing"))
    testImplementation(project(":harbor-season"))
}

// JOML(Display 변환 행렬, 예고 바닥 판)은 옛 클래스 파일 형식이라 javac classfile 경고가 난다. 그 경고만 끈다
tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.add("-Xlint:-classfile")
}
