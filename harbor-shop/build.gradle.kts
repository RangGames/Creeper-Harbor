// Harbor-Shop: 진주(유료 재화)·코스메틱·시즌 패스·후원 등급·Tebex 결제 수신 (docs 20 12장)
plugins {
    id("harbor.paper-module")
}

dependencies {
    // 필수 의존: 포획 연출(Fishing api), 기념 스킨 조건·도감 치장품(Collection api), 시즌 시계·전환 훅·칭호(Season api)
    compileOnly(project(":harbor-fishing"))
    compileOnly(project(":harbor-jobs"))
    compileOnly(project(":harbor-collection"))
    compileOnly(project(":harbor-season"))
    // 선택: World api (선박 스킨 장착 여부를 ShipSkinProvider로 등록)
    compileOnly(project(":harbor-world"))
    testImplementation(project(":harbor-fishing"))
    testImplementation(project(":harbor-jobs"))
    testImplementation(project(":harbor-collection"))
    testImplementation(project(":harbor-season"))
}

// JOML(Display 변환 행렬)은 옛 클래스 파일 형식이라 javac classfile 경고가 난다. 그 경고만 끈다
tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.add("-Xlint:-classfile")
}
