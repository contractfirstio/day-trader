import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.jetbrainsCompose)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlinxSerialization)
}

kotlin {
    // Only targeting Desktop JVM
    jvm("desktop")

    // ============================================================================
    // FIXED: Keeps the desktop compiler happy regarding experimental testing hooks
    // ============================================================================
    targets.all {
        compilations.all {
            compileTaskProvider.configure {
                compilerOptions {
                    freeCompilerArgs.add("-opt-in=androidx.compose.ui.ExperimentalComposeUiApi")
                    freeCompilerArgs.add("-opt-in=org.jetbrains.compose.ui.test.ExperimentalComposeUiTestApi")
                    freeCompilerArgs.add("-opt-in=androidx.compose.ui.test.ExperimentalTestApi")
                }
            }
        }
    }

    sourceSets {
        // Shared logic and UI layout core
        named("commonMain").configure {
            dependencies {
                implementation("org.jetbrains.kotlin:kotlin-stdlib")
                implementation(compose.foundation)
                implementation(libs.compose.material3)
                implementation(compose.runtime)
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.serialization.json)

                // UI Component & Icon packs
                implementation(libs.compose.icons.core)
                implementation(libs.compose.icons.extended)
            }
        }

        // Desktop specific wrappers (windowing, OS-level hooks)
        named("desktopMain").configure {
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation(libs.kotlinx.coroutines.swing)
                implementation(files("libs/TwsApi.jar"))
                implementation(libs.protobuf.java)
            }
        }

        // Shared cross-platform testing suite
        named("commonTest").configure {
            dependencies {
                implementation(kotlin("test"))
                implementation("org.jetbrains.compose.ui:ui-test:1.8.1")
            }
        }

        val desktopTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation("org.junit.jupiter:junit-jupiter:5.11.4")
                implementation(libs.junit)
                implementation("io.cucumber:cucumber-java:7.20.1")
                implementation("io.cucumber:cucumber-junit:7.20.1")
                // CucumberTestSuite uses JUnit 4 @RunWith — vintage engine runs it on JUnit Platform.
                runtimeOnly("org.junit.vintage:junit-vintage-engine:5.11.4")
            }
        }
    }
}

tasks.named<Test>("desktopTest") {
    configureTestDefaults()
    description = "Full desktop test suite (~500+ tests, several minutes). Do not pipe through tail/rg."
}

fun Test.configureTestDefaults() {
    useJUnitPlatform()
    maxParallelForks = 1
    // Recycle JVM every 50 classes so coroutine leaks cannot accumulate across the full suite.
    forkEvery = 50
    systemProperty("junit.jupiter.execution.timeout.default", "30s")
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = false
        showExceptions = true
        showCauses = true
        showStackTraces = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.SHORT
    }
}

/** Convenience alias — the KMP desktop target exposes `desktopTest`, not `test`. */
tasks.register("test") {
    group = "verification"
    description = "Runs unit and E2E tests (alias for desktopTest)."
    dependsOn(tasks.named("desktopTest"))
}

compose.desktop {
    application {
        mainClass = "MainKt"
        jvmArgs += listOf(
            "-Xmx32g",
            "-Dapple.awt.application.name=Day Trader",
            "-Dapple.laf.useScreenMenuBar=true",
        )

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "Day Trader"
            packageVersion = "1.0.0"
            macOS {
                dockName = "Day Trader"
            }
        }
    }
}