import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.named


gradle.beforeProject {
    if (path != ":") return@beforeProject
    val harnessTreatment = sequenceOf(
        projectDir.parentFile.resolve("treatments/harness"),
        projectDir.resolve("benchmarks/agentic-palisade/treatments/harness"))
        .map { it.toPath().toAbsolutePath().normalize() }
        .firstOrNull { it.resolve("build-overlay.gradle.kts").toFile().isFile }
        ?: throw GradleException(
            "Harness overlay could not locate treatments/harness from $projectDir")


    pluginManager.withPlugin("java") {
        dependencies.add("implementation", "io.github.teemuki8:harness-lwjgl3:1.0.0")
        dependencies.add("implementation", "io.github.teemuki8:harness-mcp:1.0.0")

        extensions.configure<SourceSetContainer> {
            named("main") {
                java.srcDir(harnessTreatment.resolve("src/main/java"))
            }
            named("test") {
                java.srcDir(harnessTreatment.resolve("src/test/java"))
            }
        }

        tasks.withType(Test::class.java).configureEach {
            jvmArgs("--enable-native-access=ALL-UNNAMED")
        }
    }

    pluginManager.withPlugin("application") {
        afterEvaluate {
            extensions.getByType<org.gradle.api.plugins.JavaApplication>()
                .mainClass.set("benchmark.palisade.HarnessCli")
        }
    }
}
