dependencies {
    api(project(":harness-core"))
    implementation(libs.gdx)
    testRuntimeOnly("com.badlogicgames.gdx:gdx-platform:${libs.versions.gdx.get()}:natives-desktop")
}

tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}
