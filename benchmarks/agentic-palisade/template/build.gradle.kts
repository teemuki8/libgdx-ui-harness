plugins {
    application
    java
}

group = "benchmark.palisade"
version = "1.0.0"

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

dependencies {
    implementation("com.badlogicgames.gdx:gdx:1.14.2")
    implementation("com.badlogicgames.gdx:gdx-backend-lwjgl3:1.14.2")
    implementation("io.github.teemuki8:libgdx-ui-markup:0.4.1")
    runtimeOnly("com.badlogicgames.gdx:gdx-platform:1.14.2:natives-desktop")
    runtimeOnly("com.badlogicgames.gdx:gdx-freetype-platform:1.14.2:natives-desktop")

    testImplementation("org.junit.jupiter:junit-jupiter:6.1.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:6.1.2")
}

application {
    mainClass.set("benchmark.palisade.CandidateLauncher")
    if (System.getProperty("os.name").lowercase().contains("mac")) {
        applicationDefaultJvmArgs = listOf("-XstartOnFirstThread")
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(25)
}

tasks.test {
    useJUnitPlatform()
    systemProperty("template.runtimeClasspath", sourceSets.main.get().runtimeClasspath.asPath)
}
