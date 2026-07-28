import org.gradle.api.GradleException
import org.gradle.api.attributes.Usage
import org.gradle.api.attributes.java.TargetJvmEnvironment
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.plugins.quality.CheckstyleExtension
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.bundling.AbstractArchiveTask
import org.gradle.api.tasks.bundling.Jar
import org.gradle.api.tasks.bundling.Zip
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.javadoc.Javadoc
import org.gradle.api.tasks.testing.Test
import org.gradle.external.javadoc.StandardJavadocDocletOptions
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.plugins.signing.SigningExtension

val publishableModules = listOf(
    "harness-core",
    "harness-scene2d",
    "harness-lwjgl3",
    "harness-protocol",
    "harness-mcp",
)
val mavenGroup = "io.github.teemuki8"
val mavenGroupPath = mavenGroup.replace('.', '/')
val releaseVersion = providers.gradleProperty("releaseVersion").orElse("1.0.0-SNAPSHOT")
val repositoryUrl = providers.gradleProperty("repositoryUrl")
    .orElse("https://github.com/teemuki8/libgdx-ui-harness")
val releaseBuild = providers.gradleProperty("release").map(String::toBoolean).orElse(false)
val junitJupiter = libs.junit.jupiter
val junitPlatformLauncher = libs.junit.platform.launcher

allprojects {
    group = mavenGroup
    version = releaseVersion.get()
}

dependencyLocking {
    lockAllConfigurations()
}

subprojects {
    pluginManager.apply("java-library")
    pluginManager.apply("checkstyle")
    pluginManager.apply("jacoco")

    dependencyLocking {
        lockAllConfigurations()
    }

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
        isFailOnError = true
        (options as StandardJavadocDocletOptions).apply {
            encoding = "UTF-8"
            addStringOption("Xmaxwarns", "1000")
            addBooleanOption("Xdoclint:all,-missing", true)
            addBooleanOption("Werror", true)
        }
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
                pom {
                    name.set("libGDX UI Harness ${project.name}")
                    description.set(
                        "Semantic inspection and deterministic automation for libGDX Scene2D UI",
                    )
                    url.set(repositoryUrl)
                    licenses {
                        license {
                            name.set("The Apache License, Version 2.0")
                            url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                            distribution.set("repo")
                        }
                    }
                    developers {
                        developer {
                            id.set("maintainers")
                            name.set("libGDX UI Harness maintainers")
                        }
                    }
                    scm {
                        connection.set("scm:git:${repositoryUrl.get()}.git")
                        developerConnection.set("scm:git:ssh://git@github.com/teemuki8/libgdx-ui-harness.git")
                        url.set(repositoryUrl)
                    }
                }
            }
            repositories.maven {
                name = "centralStaging"
                url = rootProject.layout.buildDirectory.dir("central-staging")
                    .get().asFile.toURI()
            }
        }

        val publishing = extensions.getByType<PublishingExtension>()
        extensions.configure<SigningExtension> {
            val signingKey = providers.environmentVariable("MAVEN_SIGNING_KEY")
            val signingPassword = providers.environmentVariable("MAVEN_SIGNING_PASSWORD")
            if (signingKey.isPresent && signingPassword.isPresent) {
                useInMemoryPgpKeys(signingKey.get(), signingPassword.get())
            }
            isRequired = releaseBuild.get()
            sign(publishing.publications["mavenJava"])
        }
    }
}

val japicmp = configurations.create("japicmp") {
    isCanBeConsumed = false
    isCanBeResolved = true
    attributes.attribute(
        Usage.USAGE_ATTRIBUTE,
        objects.named(Usage.JAVA_RUNTIME),
    )
    attributes.attribute(
        TargetJvmEnvironment.TARGET_JVM_ENVIRONMENT_ATTRIBUTE,
        objects.named(TargetJvmEnvironment.STANDARD_JVM),
    )
}
dependencies {
    add(japicmp.name, libs.japicmp)
}

val apiCompatibilityTasks = publishableModules.map { moduleName ->
    tasks.register<JavaExec>(
        "apiCompatibility${moduleName.split('-').joinToString("") { it.replaceFirstChar(Char::uppercase) }}",
    ) {
        group = "verification"
        description = "Checks $moduleName against the supplied released Maven repository"
        classpath = japicmp
        mainClass.set("japicmp.JApiCmp")
        dependsOn(project(":$moduleName").tasks.named("jar"))
        doFirst {
            val baselineRepository = providers.gradleProperty("apiBaselineRepository")
                .orNull ?: throw GradleException("apiBaselineRepository is required")
            val baselineVersion = providers.gradleProperty("apiBaselineVersion")
                .orNull ?: throw GradleException("apiBaselineVersion is required")
            val oldJar = file(
                "$baselineRepository/$mavenGroupPath/$moduleName/$baselineVersion/"
                    + "$moduleName-$baselineVersion.jar",
            )
            if (!oldJar.isFile) {
                throw GradleException("Missing API baseline artifact: $oldJar")
            }
            val newJar = project(":$moduleName").tasks.named<Jar>("jar")
                .get().archiveFile.get().asFile
            setArgs(
                listOf(
                    "--old", oldJar.absolutePath,
                    "--new", newJar.absolutePath,
                    "--only-modified",
                    "--error-on-binary-incompatibility",
                    "--error-on-source-incompatibility",
                    "--ignore-missing-classes",
                ),
            )
        }
    }
}

tasks.register("apiCompatibility") {
    group = "verification"
    description = "Checks binary and source compatibility with a released Maven baseline"
    dependsOn(apiCompatibilityTasks)
}

tasks.register("javadoc") {
    group = "documentation"
    description = "Generates warning-free Javadocs for all published modules"
    dependsOn(publishableModules.map { project(":$it").tasks.named("javadoc") })
}

val verifyReleaseConfiguration = tasks.register("verifyReleaseConfiguration") {
    group = "publishing"
    description = "Fails closed unless release version, Central credentials, and PGP secrets exist"
    doLast {
        val versionText = releaseVersion.get()
        val semanticVersion = Regex("[0-9]+\\.[0-9]+\\.[0-9]+(?:-[0-9A-Za-z.-]+)?")
        if (!semanticVersion.matches(versionText) || versionText.endsWith("-SNAPSHOT")) {
            throw GradleException("releaseVersion must be a non-SNAPSHOT semantic version")
        }
        val requiredSecrets = listOf(
            "MAVEN_CENTRAL_USERNAME",
            "MAVEN_CENTRAL_PASSWORD",
            "MAVEN_SIGNING_KEY",
            "MAVEN_SIGNING_PASSWORD",
        )
        val missing = requiredSecrets.filter { System.getenv(it).isNullOrBlank() }
        if (missing.isNotEmpty()) {
            throw GradleException("Missing release secrets: ${missing.joinToString()}")
        }
    }
}

val stageRelease = tasks.register("stageRelease") {
    group = "publishing"
    description = "Publishes only the five signed modules to a local Central bundle layout"
    dependsOn(verifyReleaseConfiguration)
    dependsOn(publishableModules.map {
        project(":$it").tasks.named("publishMavenJavaPublicationToCentralStagingRepository")
    })
}

val verifyCentralStaging = tasks.register("verifyCentralStaging") {
    group = "publishing"
    description = "Verifies the signed Central staging layout and unpublished module exclusions"
    dependsOn(stageRelease)
    doLast {
        val stagingRoot = layout.buildDirectory.dir("central-staging").get().asFile
        val versionText = releaseVersion.get()
        for (moduleName in publishableModules) {
            val moduleDirectory = stagingRoot.resolve("$mavenGroupPath/$moduleName/$versionText")
            for (suffix in listOf(".jar", "-sources.jar", "-javadoc.jar", ".pom")) {
                val artifact = moduleDirectory.resolve("$moduleName-$versionText$suffix")
                if (!artifact.isFile || artifact.length() == 0L) {
                    throw GradleException("Missing staged artifact: $artifact")
                }
                val signature = moduleDirectory.resolve("${artifact.name}.asc")
                if (!signature.isFile || signature.length() == 0L) {
                    throw GradleException("Missing staged signature: $signature")
                }
            }
        }
        val forbidden = stagingRoot.walkTopDown().filter { file ->
            file.isFile && (file.path.contains("harness-fixtures")
                || file.path.contains("benchmarks"))
        }.toList()
        if (forbidden.isNotEmpty()) {
            throw GradleException("Unpublished modules entered staging: $forbidden")
        }
    }
}

tasks.register<Zip>("centralBundle") {
    group = "publishing"
    description = "Packages the verified Maven Central Portal deployment bundle"
    dependsOn(verifyCentralStaging)
    from(layout.buildDirectory.dir("central-staging"))
    archiveFileName.set("central-bundle-${releaseVersion.get()}.zip")
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}
