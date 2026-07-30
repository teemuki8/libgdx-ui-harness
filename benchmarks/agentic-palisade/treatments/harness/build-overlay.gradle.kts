import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.named
import java.nio.file.Files


fun harnessTreatment(root: java.io.File) = sequenceOf(
    root.parentFile.resolve("treatments/harness"),
    root.resolve("benchmarks/agentic-palisade/treatments/harness"),
)
    .map { it.toPath().toAbsolutePath().normalize() }
    .firstOrNull { it.resolve("build-overlay.gradle.kts").toFile().isFile }
    ?: throw GradleException(
        "Harness overlay could not locate treatments/harness from $root",
    )

gradle.settingsEvaluated {
    val candidateRepository = harnessTreatment(settingsDir).resolve("candidate-maven")
    if (Files.isDirectory(candidateRepository)) {
        dependencyResolutionManagement.repositories.maven {
            name = "qualifiedHarnessCandidate"
            url = uri(candidateRepository)
        }
    }
}

gradle.beforeProject {
    if (path != ":") return@beforeProject
    val harnessTreatment = harnessTreatment(projectDir)
    val versionFile = harnessTreatment.resolve("candidate-version.txt")
    val harnessVersion = if (Files.isRegularFile(versionFile)) {
        Files.readString(versionFile).trim()
    } else {
        "1.0.0"
    }
    if (harnessVersion.isBlank()) {
        throw GradleException("Harness candidate version is blank")
    }

    pluginManager.withPlugin("java") {
        dependencies.add(
            "implementation",
            "io.github.teemuki8:harness-lwjgl3:$harnessVersion",
        )
        dependencies.add(
            "implementation",
            "io.github.teemuki8:harness-mcp:$harnessVersion",
        )

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
            tasks.named<JavaExec>("run") {
                standardInput = System.`in`
            }
        }
    }
}
