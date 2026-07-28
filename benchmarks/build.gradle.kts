plugins {
    application
}

dependencies {
    implementation(project(":harness-fixtures"))
    implementation(project(":harness-protocol"))
    implementation(libs.jackson.databind)
    runtimeOnly("com.badlogicgames.gdx:gdx-platform:${libs.versions.gdx.get()}:natives-desktop")
    runtimeOnly("org.slf4j:slf4j-nop:2.0.17")
}

application {
    mainClass.set("dev.gdx.uiharness.benchmarks.BenchmarkRunner")
}

tasks.named<JavaExec>("run") {
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    systemProperty("benchmark.runtime.classpath", sourceSets.main.get().runtimeClasspath.asPath)
    systemProperty("benchmark.project.dir", rootProject.projectDir.absolutePath)
}
