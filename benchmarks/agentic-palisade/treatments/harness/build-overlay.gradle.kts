import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.testing.Test
import org.gradle.api.artifacts.ExternalModuleDependency
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
    if (!Files.isDirectory(candidateRepository)) {
        throw GradleException("required candidate Maven repository is missing")
    }
    dependencyResolutionManagement.repositories.maven {
        name = "qualifiedHarnessCandidate"
        url = uri(candidateRepository)
    }
}

gradle.beforeProject {
    if (path != ":") return@beforeProject
    val harnessTreatment = harnessTreatment(projectDir)
    val versionFile = harnessTreatment.resolve("candidate-version.txt")
    if (!Files.isRegularFile(versionFile)) {
        throw GradleException("required candidate version file is missing")
    }
    val harnessVersion = Files.readString(versionFile).trim()
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
        val markupAdapter = dependencies.create(
            "io.github.teemuki8:libgdx-ui-markup-harness:0.4.1",
        ) as ExternalModuleDependency
        markupAdapter.exclude(mapOf(
            "group" to "io.github.teemuki8",
            "module" to "harness-scene2d",
        ))
        dependencies.add("implementation", markupAdapter)

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
