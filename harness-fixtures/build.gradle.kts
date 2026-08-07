dependencies {
    implementation(project(":harness-core"))
    implementation(project(":harness-scene2d"))
    implementation(project(":harness-lwjgl3"))
    implementation(project(":harness-protocol"))
    implementation(project(":harness-mcp"))
    implementation(project(":harness-agent-runtime"))
    implementation(libs.markup.core)
    // The markup adapter depends on published harness-core/scene2d; the fixture must exercise
    // this repository's project modules, so the published transitive copies are excluded.
    implementation(libs.markup.harness) {
        exclude(group = "io.github.teemuki8", module = "harness-core")
        exclude(group = "io.github.teemuki8", module = "harness-scene2d")
    }
    implementation(libs.markup.runtime)
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
