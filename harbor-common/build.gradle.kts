// 순수 Java 라이브러리. Paper·Velocity API를 쓰지 않는다 (서버 없이 단위 테스트 가능).
plugins {
    id("harbor.java")
}

dependencies {
    api(libs.hikaricp)
    api(libs.lettuce)
    api(libs.flyway.core)
    runtimeOnly(libs.flyway.mysql)
    runtimeOnly(libs.mariadb)

    // Paper·Velocity가 런타임에 제공
    compileOnlyApi(libs.snakeyaml)
    compileOnlyApi(libs.gson)

    testImplementation(libs.snakeyaml)
    testImplementation(libs.gson)
    testImplementation(platform(libs.testcontainers.bom))
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.mariadb)
}
