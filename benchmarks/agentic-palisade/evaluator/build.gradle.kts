plugins {
    application
    java
}

group = "benchmark.palisade"
version = "1.0.0"

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("com.fasterxml.jackson.core:jackson-databind:2.20.1")

    testImplementation("org.junit.jupiter:junit-jupiter:6.1.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:6.1.2")
}

application {
    mainClass.set("benchmark.palisade.eval.CandidateEvaluator")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(25)
}

tasks.test {
    useJUnitPlatform()
    systemProperty("palisade.corpus", layout.projectDirectory.dir("../corpus").asFile.absolutePath)
    systemProperty("palisade.rootGradle", layout.projectDirectory.file("../../../gradlew").asFile.absolutePath)
}
