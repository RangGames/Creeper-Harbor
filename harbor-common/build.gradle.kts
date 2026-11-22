// Pure Java library. Does not use the Paper or Velocity API (unit-testable without a server).
plugins {
    id("harbor.java")
}

dependencies {
    api(libs.hikaricp)
    api(libs.lettuce)
    api(libs.flyway.core)
    runtimeOnly(libs.flyway.mysql)
    runtimeOnly(libs.mariadb)

    // Provided by Paper/Velocity at runtime
    compileOnlyApi(libs.snakeyaml)
    compileOnlyApi(libs.gson)

    testImplementation(libs.snakeyaml)
    testImplementation(libs.gson)
    testImplementation(platform(libs.testcontainers.bom))
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.mariadb)
}
