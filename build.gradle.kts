import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.plugins.quality.CheckstyleExtension
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.tasks.bundling.AbstractArchiveTask
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.javadoc.Javadoc
import org.gradle.external.javadoc.StandardJavadocDocletOptions
import org.gradle.api.tasks.testing.Test
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.plugins.signing.SigningExtension

val publishableModules = setOf(
    "harness-core",
    "harness-scene2d",
    "harness-lwjgl3",
    "harness-protocol",
    "harness-mcp",
)

val junitJupiter = libs.junit.jupiter
val junitPlatformLauncher = libs.junit.platform.launcher

subprojects {
    group = "dev.gdx"

    pluginManager.apply("java-library")
    pluginManager.apply("checkstyle")
    pluginManager.apply("jacoco")

    dependencies {
        add("testImplementation", junitJupiter)
        add("testRuntimeOnly", junitPlatformLauncher)
    }

    extensions.configure<JavaPluginExtension> {
        toolchain.languageVersion.set(JavaLanguageVersion.of(25))
        withSourcesJar()
        withJavadocJar()
    }

    extensions.configure<CheckstyleExtension> {
        configFile = rootProject.file("config/checkstyle/checkstyle.xml")
        maxWarnings = 0
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.release.set(25)
        options.compilerArgs.add("-Xlint:all")
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
    }

    tasks.withType<Javadoc>().configureEach {
        (options as StandardJavadocDocletOptions).encoding = "UTF-8"
    }

    tasks.withType<AbstractArchiveTask>().configureEach {
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
    }

    if (name in publishableModules) {
        pluginManager.apply("maven-publish")
        pluginManager.apply("signing")

        extensions.configure<PublishingExtension> {
            publications.create<MavenPublication>("mavenJava") {
                from(components["java"])
            }
        }

        val publishing = extensions.getByType<PublishingExtension>()
        extensions.configure<SigningExtension> {
            sign(publishing.publications)
        }
    }
}
