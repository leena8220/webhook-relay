import com.github.benmanes.gradle.versions.updates.DependencyUpdatesTask
import org.gradle.api.tasks.testing.Test
import org.gradle.testing.jacoco.plugins.JacocoPluginExtension
import org.gradle.testing.jacoco.tasks.JacocoReport
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask

plugins {
    base
    kotlin("jvm") version "2.4.10" apply false
    kotlin("plugin.spring") version "2.4.10" apply false
    id("org.springframework.boot") version "4.1.0" apply false
    id("org.jlleitschuh.gradle.ktlint") version "14.2.0" apply false
    id("io.github.ben-manes.versions") version "0.59.0"
}

group = "dev.gdguesser.webhookrelay"
version = "0.1.0-SNAPSHOT"

subprojects {
    group = rootProject.group
    version = rootProject.version

    apply(plugin = "org.jetbrains.kotlin.jvm")
    apply(plugin = "org.jlleitschuh.gradle.ktlint")
    apply(plugin = "jacoco")

    extensions.configure<JacocoPluginExtension> {
        toolVersion = "0.8.14"
    }

    extensions.configure<KotlinJvmProjectExtension> {
        jvmToolchain(21)
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_21)
            javaParameters.set(true)
            allWarningsAsErrors.set(true)
            freeCompilerArgs.addAll(
                "-Xjsr305=strict",
            )
        }
    }

    tasks.withType<KotlinCompilationTask<*>>().configureEach {
        compilerOptions {
            progressiveMode.set(true)
        }
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        testLogging {
            events("failed", "skipped", "passed")
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        }
    }

    tasks.named<JacocoReport>("jacocoTestReport") {
        dependsOn(tasks.named("test"))
        reports {
            xml.required.set(true)
            html.required.set(true)
        }
    }

    tasks.named("check") {
        dependsOn(tasks.named("jacocoTestReport"))
    }
}

tasks.named("check") {
    dependsOn(subprojects.map { it.tasks.named("check") })
}

fun String.isNonStable(): Boolean {
    val stableKeywords = listOf("RELEASE", "FINAL", "GA")
    val hasStableKeyword = stableKeywords.any { uppercase().contains(it) }
    val stablePattern = Regex("^[0-9,.v-]+(-r)?$")
    return !hasStableKeyword && !stablePattern.matches(this)
}

tasks.named<DependencyUpdatesTask>("dependencyUpdates") {
    rejectVersionIf {
        candidate.version.isNonStable() && !currentVersion.isNonStable()
    }
    checkForGradleUpdate = true
    outputFormatter = "json"
    outputDir = layout.buildDirectory.dir("dependencyUpdates").get().asFile.path
}
