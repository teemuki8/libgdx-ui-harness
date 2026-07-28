dependencies {
    implementation(project(":harness-core"))
    implementation(project(":harness-scene2d"))
    implementation(project(":harness-lwjgl3"))
    implementation(project(":harness-protocol"))
    implementation(project(":harness-mcp"))
    implementation(libs.gdx.backend.lwjgl3)
    implementation(libs.jackson.databind)
    runtimeOnly("com.badlogicgames.gdx:gdx-platform:${libs.versions.gdx.get()}:natives-desktop")
    runtimeOnly("org.slf4j:slf4j-nop:2.0.17")
    testImplementation(libs.jackson.databind)
}

tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    systemProperty("reference.app.classpath", sourceSets.main.get().runtimeClasspath.asPath)
}
