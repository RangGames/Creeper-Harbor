plugins {
    `kotlin-dsl`
}

dependencies {
    implementation(libs.errorprone.gradle)
    implementation(libs.nullaway.gradle)
    implementation(libs.spotless.gradle)
    implementation(libs.shadow.gradle)
    implementation(libs.run.paper.gradle)
    implementation(libs.run.velocity.gradle)
}
